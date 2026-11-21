/*
 * Copyright 2026 David Greco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tripatha.spark;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaHadoopRDD;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.util.LongAccumulator;

import org.tripatha.accumulo.AccumuloRFiles;
import org.tripatha.accumulo.AccumuloStore;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.loader.ClientFactory;
import org.tripatha.loader.LoaderOptions;
import org.tripatha.loader.SplitPlanner;
import org.tripatha.loader.StoreCheck;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.store.EvalStatsMutations.CounterPolicy;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.BulkImport;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;

import scala.Tuple2;

/**
 * The bulk build as a Spark job, for Accumulo (SCALE-STUDY.md D5): statements from the loader's
 * generator or from N-Triples and N-Quads files become the cells {@code load} writes, Spark's
 * shuffle combines and sorts them per file, the executors write them as RFiles into a work
 * directory on one of Accumulo's volumes (S3 or HDFS), and the driver imports them.
 *
 * <p>Two phases, {@code write} and {@code import}, recorded in the work directory: a write run again
 * writes the same files again (one timestamp per job, files named after their table and range,
 * never after a task), and once an import has started the files are never written again, since
 * Accumulo adds imported counters to what a table holds.
 */
public final class SparkBuild {
    /** Files per tablet: Accumulo refuses an import that gives a tablet too many ({@code table.bulk.max.tablet.files}, 100). */
    static final int MAX_FILES_PER_TABLET = 50;

    enum Command {
        GENERATE, FILES, SMOKE
    }

    private final JavaSparkContext sc;
    private final Store store;
    private final Options opts;
    private final LoaderOptions o;
    private final PrintStream out;

    SparkBuild(final JavaSparkContext sc, final Store store, final Options opts, final PrintStream out) {
        this.sc = sc;
        this.store = store;
        this.opts = opts;
        this.o = opts.loader;
        this.out = out;
    }

    public static void main(final String[] args) {
        final int code = run(args, System.getenv(), System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** @return 0 when done, 1 when it failed, 2 for a mistake in the options or a store that doesn't fit them */
    static int run(final String[] args, final Map<String, String> env, final PrintStream out, final PrintStream err) {
        final Options opts;
        try {
            opts = Options.parse(args, env);
        } catch (final IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println();
            err.println(usage());
            return 2;
        }
        if (opts.help) {
            out.println(usage());
            return 0;
        }
        final SparkConf conf = configure(new SparkConf().setAppName("tripatha " + opts.command.name().toLowerCase(Locale.ROOT)
                + " " + opts.loader.tablePrefix()));
        try (JavaSparkContext sc = new JavaSparkContext(conf);
                AccumuloStore store = AccumuloStore.owning(ClientFactory.create(opts.loader))) {
            new SparkBuild(sc, store, opts, out).run();
            return 0;
        } catch (final StoreCheck.Mismatch | IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        } catch (final Exception e) {
            e.printStackTrace(err);
            return 1;
        }
    }

    /** Kryo for the shuffle, and no speculative tasks (two attempts would write one file at once). */
    static SparkConf configure(final SparkConf conf) {
        conf.setIfMissing("spark.serializer", "org.apache.spark.serializer.KryoSerializer");
        conf.registerKryoClasses(new Class<?>[] {Shuffle.Key.class, Shuffle.Value.class, Tasks.FileWritten.class, Tasks.Bytes.class,
            Smoke.Result.class, byte[].class});
        if (conf.getBoolean("spark.speculation", false)) {
            throw new IllegalArgumentException("spark.speculation must be off: two attempts of a task would write one file at once");
        }
        return conf;
    }

    void run() throws Exception {
        if (!o.backend().equals("accumulo")) {
            throw new IllegalArgumentException("the Spark build writes Accumulo's files: --backend accumulo (got " + o.backend() + ")");
        }
        if (opts.command == Command.SMOKE) {
            new Smoke(sc, (AccumuloStore) store, work(), o.tablePrefix(), out).run();
            return;
        }
        build();
    }

    private WorkDir work() {
        if (!o.given("work-dir")) {
            throw new IllegalArgumentException("give --work-dir: a directory on one of Accumulo's volumes (s3a://bucket/…, hdfs://…)");
        }
        return new WorkDir(o.workDir(), sc.hadoopConfiguration());
    }

    // ------------------------------------------------------------------ the build

    private void build() throws Exception {
        final StoreRdfConfiguration conf = o.rdfConf();
        final WorkDir work = work();
        final boolean files = opts.command == Command.FILES;
        final List<FileStatus> inputs = files ? inputs() : List.of();
        if (files) {
            openForFiles(conf);
        } else {
            StoreCheck.forLoad(store, o);
        }
        final List<String> tables = tables(conf);
        final String fingerprint = files ? filesFingerprint(inputs) : generatorFingerprint();
        final Properties job = work.read("job.properties");
        if (job.getProperty("build") != null && !job.getProperty("build").equals(fingerprint)) {
            throw new StoreCheck.Mismatch("the work directory " + work.uri() + " belongs to another build (other input, prefix or "
                    + "settings): give this one a directory of its own, or delete that one");
        }
        if (job.getProperty("build") == null) {
            job.setProperty("build", fingerprint);
            job.setProperty("timestamp", Long.toString(System.currentTimeMillis()));
            job.setProperty("state", "new");
            if (files) {
                final boolean fresh = isEmpty(conf.getTableLayoutStrategy().getSpo()) && isEmpty(conf.getTableLayoutStrategy().getEval());
                if (!fresh && !opts.append) {
                    throw new StoreCheck.Mismatch("store " + o.tablePrefix() + " already holds statements: add --append to add "
                            + "these to them");
                }
                job.setProperty("fresh", Boolean.toString(fresh));
            }
            work.write("job.properties", job);
        }
        final long jobTime = Long.parseLong(job.getProperty("timestamp"));
        if (opts.phases.contains("write")) {
            write(conf, work, job, tables, inputs, jobTime);
        }
        if (opts.phases.contains("import")) {
            importFiles(work, job);
        }
    }

    /** The tables whose cells go into files: statements, counters, the indexes that exist, and the graph markers. */
    private List<String> tables(final StoreRdfConfiguration conf) {
        final TableLayoutStrategy t = conf.getTableLayoutStrategy();
        final List<String> tables = new ArrayList<>();
        for (final String name : List.of(t.getSpo(), t.getPo(), t.getOsp(), t.getEval(), t.getText(), t.getGeo(), t.getEntity(),
                t.getTextCount(), t.getGraphs())) {
            if (store.tables().exists(name)) {
                tables.add(name);
            }
        }
        return tables;
    }

    /** The store's tables that exist, all of them: a DAO checks its index tables against its settings. */
    private java.util.HashSet<String> existing(final StoreRdfConfiguration conf) {
        final java.util.HashSet<String> out = new java.util.HashSet<>();
        for (final String name : new RyaStoreDAO(store, conf).getTables()) {
            if (store.tables().exists(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private boolean isCounters(final String table) {
        final TableLayoutStrategy t = o.rdfConf().getTableLayoutStrategy();
        return table.equals(t.getEval()) || table.equals(t.getTextCount());
    }

    private boolean isEmpty(final String table) {
        try (CellScanner cells = store.scan(table, KeyRange.all(), ScanSpec.of(Auths.ALL_HELD))) {
            return !cells.hasNext();
        }
    }

    /**
     * For files: a store that exists must have the indexes asked for; then a DAO opens it, which
     * creates a new one with its tables, version and READY indexes, and leaves one that is set up as
     * it is.
     */
    private void openForFiles(final StoreRdfConfiguration conf) throws Exception {
        if (store.tables().exists(conf.getTableLayoutStrategy().getSpo())) {
            StoreCheck.indexes(store, o);
        }
        final RyaStoreDAO dao = new RyaStoreDAO(store, conf);
        try {
            dao.init();
        } finally {
            dao.destroy();
        }
    }

    private String generatorFingerprint() {
        final long[] range = o.partRange();
        return "generate|" + o.genConfig().fingerprint() + "|" + o.tablePrefix() + "|" + range[0] + "-" + range[1] + "|" + o.blockSize()
                + "|" + o.textIndex() + o.geoIndex() + o.textPredicates() + (o.entityIndex() ? "|entity" + o.entityShards() : "") + "|"
                + o.stats() + "|" + o.statsOnes();
    }

    private String filesFingerprint(final List<FileStatus> inputs) throws Exception {
        final MessageDigest d = MessageDigest.getInstance("SHA-256");
        for (final FileStatus f : inputs) {
            d.update((RdfLines.fileId(f) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return "files|" + HexFormat.of().formatHex(d.digest(), 0, 16) + "|" + o.tablePrefix() + "|" + opts.visibility + "|"
                + opts.distinct + "|" + o.textIndex() + o.geoIndex() + o.textPredicates()
                + (o.entityIndex() ? "|entity" + o.entityShards() : "") + "|" + o.stats();
    }

    /** The input files: each path or glob, directories listed recursively, N-Triples and N-Quads only (compressed or not). */
    private List<FileStatus> inputs() throws IOException {
        if (opts.input.isEmpty()) {
            throw new IllegalArgumentException("files needs --input PATH[,PATH…]: N-Triples or N-Quads files, globs or directories");
        }
        final List<FileStatus> found = new ArrayList<>();
        for (final String in : opts.input) {
            final Path p = new Path(in);
            final FileSystem fs = p.getFileSystem(sc.hadoopConfiguration());
            final FileStatus[] matches = fs.globStatus(p);
            if (matches == null || matches.length == 0) {
                throw new IllegalArgumentException("no input at " + in);
            }
            for (final FileStatus m : matches) {
                if (m.isDirectory()) {
                    final var it = fs.listFiles(m.getPath(), true);
                    while (it.hasNext()) {
                        found.add(it.next());
                    }
                } else {
                    found.add(m);
                }
            }
        }
        final List<FileStatus> rdf = new ArrayList<>();
        for (final FileStatus f : found) {
            if (f.getPath().getName().matches("(?i).*\\.(nt|nq|ntriples|nquads)(\\.gz|\\.bz2)?")) {
                rdf.add(f);
            } else if (!f.getPath().getName().startsWith("_") && !f.getPath().getName().startsWith(".")) {
                out.println("Left out (not .nt or .nq): " + f.getPath());
            }
        }
        if (rdf.isEmpty()) {
            throw new IllegalArgumentException("no .nt or .nq file in " + opts.input);
        }
        rdf.sort((a, b) -> a.getPath().toString().compareTo(b.getPath().toString()));
        return rdf;
    }

    // ------------------------------------------------------------------ write

    private void write(final StoreRdfConfiguration conf, final WorkDir work, final Properties job, final List<String> tables,
            final List<FileStatus> inputs, final long jobTime) throws Exception {
        final String state = job.getProperty("state");
        if (state.equals("importing") || state.equals("done")) {
            throw new StoreCheck.Mismatch("the files of " + work.uri() + " are " + (state.equals("done") ? "imported" : "being imported")
                    + ": writing them again would import them twice (" + (state.equals("done") ? "nothing left to do"
                            : "finish with --phase import") + ")");
        }
        final boolean files = opts.command == Command.FILES;
        final long t0 = System.currentTimeMillis();
        // A write run again starts over: the files of a write that didn't finish may follow another layout.
        work.delete("files");
        work.delete("manifest.properties");
        job.setProperty("state", "writing");
        work.write("job.properties", job);

        final JobSpec spec = new JobSpec();
        spec.loaderArgs = opts.executorArgs(o);
        spec.jobTime = jobTime;
        spec.visibility = opts.visibility == null ? null : opts.visibility.getBytes(StandardCharsets.UTF_8);
        spec.maxBadLines = opts.maxBadLines;
        spec.hadoop = JobSpec.entries(sc.hadoopConfiguration());
        spec.filesDir = work.filesDir();
        final long[] range = o.partRange();
        spec.first = range[0];
        spec.last = range[1];
        spec.blockSize = o.blockSize();
        if (files) {
            final boolean fresh = Boolean.parseBoolean(job.getProperty("fresh"));
            spec.addPolicy = CounterPolicy.ALL;
            spec.leftOutPolicy = fresh ? CounterPolicy.SKIP_SUBJECT_ONES : CounterPolicy.ALL;
        } else {
            spec.addPolicy = o.statsOnes();
            spec.leftOutPolicy = o.statsOnes();
        }
        spec.existing = existing(conf);
        spec.tableProperties = new HashMap<>();
        for (final String t : tables) {
            spec.tableProperties.put(t, new HashMap<>(AccumuloRFiles.tableProperties(((AccumuloStore) store).client(), t)));
        }

        final Sample sample = files ? sampleFiles(conf, tables, inputs, spec) : sampleGenerator(conf, tables, spec);
        if (files && Boolean.parseBoolean(job.getProperty("fresh"))) {
            splitFreshStore(conf, sample);
        }
        spec.layout = layout(conf, tables, sample);
        final long t1 = System.currentTimeMillis();

        final LongAccumulator statements = sc.sc().longAccumulator("statements");
        final LongAccumulator bad = sc.sc().longAccumulator("bad lines");
        final JavaRDD<Tuple2<Shuffle.Key, Shuffle.Value>> cells = files ? fileCells(spec, inputs, statements, bad)
                : generatedCells(spec, statements);
        final List<Tasks.FileWritten> written = writeFiles(spec, cells);
        final long t2 = System.currentTimeMillis();

        final Properties manifest = new Properties();
        long total = 0;
        for (final Tasks.FileWritten w : written) {
            manifest.setProperty(w.table + "/" + new Path(w.path).getName(), w.path + "\t" + HexFormat.of().formatHex(w.firstRow) + "\t"
                    + HexFormat.of().formatHex(w.lastRow) + "\t" + w.cells);
            total += w.cells;
        }
        work.write("manifest.properties", manifest);
        job.setProperty("state", "written");
        job.setProperty("statements", Long.toString(statements.value()));
        job.setProperty("badLines", Long.toString(bad.value()));
        work.write("job.properties", job);
        out.printf(Locale.ROOT, "Write: %,d statements, %,d cells in %,d files, in %s (plan %s)%s.%n", statements.value(), total,
                written.size(), duration(t2 - t0), duration(t1 - t0), bad.value() > 0 ? String.format(Locale.ROOT,
                        "; %,d bad lines left out", bad.value()) : "");
    }

    private JavaRDD<Tuple2<Shuffle.Key, Shuffle.Value>> generatedCells(final JobSpec spec, final LongAccumulator statements) {
        final long blocks = (spec.last - spec.first + spec.blockSize - 1) / spec.blockSize;
        final List<Long> ids = new ArrayList<>(LongStream.range(0, blocks).boxed().toList());
        final int tasks = opts.tasks > 0 ? opts.tasks : (int) Math.max(1, Math.min(blocks, sc.defaultParallelism() * 4L));
        sc.setJobDescription("generate " + (spec.last - spec.first) + " entities");
        return sc.parallelize(ids, tasks).mapPartitions(it -> Tasks.generated(spec, it, statements));
    }

    @SuppressWarnings("unchecked")
    private JavaHadoopRDD<LongWritable, Text> lines(final List<FileStatus> inputs) {
        final String paths = inputs.stream().map(f -> f.getPath().toString()).collect(Collectors.joining(","));
        final int splits = opts.inputSplits > 0 ? opts.inputSplits : sc.defaultMinPartitions();
        return (JavaHadoopRDD<LongWritable, Text>) sc.hadoopFile(paths, TextInputFormat.class, LongWritable.class, Text.class, splits);
    }

    private JavaRDD<Tuple2<Shuffle.Key, Shuffle.Value>> fileCells(final JobSpec spec, final List<FileStatus> inputs,
            final LongAccumulator statements, final LongAccumulator bad) {
        final JavaHadoopRDD<LongWritable, Text> lines = lines(inputs);
        sc.setJobDescription("read " + inputs.size() + " files");
        if (!opts.distinct) {
            return lines.mapPartitionsWithInputSplit((split, it) -> Tasks.fromLines(spec, split, it, statements, bad), false);
        }
        return lines.mapPartitionsWithInputSplit((split, it) -> Tasks.spoCells(spec, split, it, bad), false).distinct()
                .mapPartitions(it -> Tasks.fromSpoCells(spec, it, statements, bad));
    }

    /**
     * The shuffle: each partition sorted in Accumulo's key order, then written as one file. The
     * tasks have added up their counters already, and the writer adds up what is left: a sort
     * alone costs less than Spark's own combining, which hashes every cell.
     */
    private List<Tasks.FileWritten> writeFiles(final JobSpec spec, final JavaRDD<Tuple2<Shuffle.Key, Shuffle.Value>> cells) {
        sc.setJobDescription("write " + spec.layout.partitions + " files");
        return new ArrayList<>(JavaPairRDD.fromJavaRDD(cells)
                .repartitionAndSortWithinPartitions(new Shuffle.FilePartitioner(spec.layout.partitions), new Shuffle.Order())
                .mapPartitionsWithIndex((i, it) -> Tasks.write(spec, i, it), true).collect());
    }

    // ------------------------------------------------------------------ the plan

    /** A random sample of the generator's entities, written on the driver as the tasks write them. */
    private Sample sampleGenerator(final StoreRdfConfiguration conf, final List<String> tables, final JobSpec spec) {
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final long n = spec.last - spec.first;
        final int k = (int) Math.min(n, opts.sampleEntities);
        final Sample sample = new Sample(tables);
        final SplittableRandom random = new SplittableRandom(o.genConfig().seed() ^ 0x5EED_5917L);
        try (CellMaker maker = new CellMaker(conf, Set.copyOf(tables), spec.existing, sample, spec.jobTime)) {
            final List<RyaStatement> batch = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                batch.addAll(generator.generate(spec.first + (k == n ? i : random.nextLong(n))));
                if (i % 100 == 99 || i == k - 1) {
                    maker.add(batch, spec.addPolicy);
                    batch.clear();
                }
            }
        }
        sample.scale = k == 0 ? 1 : (double) n / k;
        return sample;
    }

    /** A sample of the input's lines, read by the executors and written on the driver. */
    private Sample sampleFiles(final StoreRdfConfiguration conf, final List<String> tables, final List<FileStatus> inputs,
            final JobSpec spec) {
        long estimate = 0;
        for (final FileStatus f : inputs) {
            final boolean compressed = f.getPath().getName().matches("(?i).*\\.(gz|bz2)");
            estimate += f.getLen() / 100 * (compressed ? 8 : 1);
        }
        final double fraction = Math.min(1, opts.sampleLines / (double) Math.max(1, estimate));
        sc.setJobDescription("sample " + inputs.size() + " files");
        final List<Tuple2<String, List<String>>> chunks = new ArrayList<>(lines(inputs).mapPartitionsWithInputSplit((split, it) -> {
            final Path file = ((FileSplit) split).getPath();
            final String fileId = RdfLines.fileId(file.getFileSystem(spec.hadoopConf()).getFileStatus(file));
            final List<String> kept = new ArrayList<>();
            long total = 0;
            while (it.hasNext()) {
                final Tuple2<LongWritable, Text> line = it.next();
                total++;
                if (Tasks.sampled(fileId, line._1().get(), fraction)) {
                    kept.add(line._2().toString());
                }
            }
            kept.add(Long.toString(total));
            final List<Tuple2<String, List<String>>> one = new ArrayList<>(1);
            one.add(new Tuple2<>(fileId, kept));
            return one.iterator();
        }, false).collect());
        final Sample sample = new Sample(tables);
        long lines = 0;
        long sampled = 0;
        try (CellMaker maker = new CellMaker(conf, Set.copyOf(tables), spec.existing, sample, spec.jobTime)) {
            for (final Tuple2<String, List<String>> chunk : chunks) {
                final List<String> kept = chunk._2();
                lines += Long.parseLong(kept.get(kept.size() - 1));
                final RdfLines parser = new RdfLines(chunk._1(), spec.jobTime, spec.visibility);
                final List<RyaStatement> batch = new ArrayList<>();
                for (final String line : kept.subList(0, kept.size() - 1)) {
                    sampled++;
                    try {
                        parser.parse(line, batch);
                    } catch (final RuntimeException e) {
                        // a bad line: the job counts it
                    }
                    if (batch.size() >= 5_000) {
                        addSample(maker, batch);
                    }
                }
                addSample(maker, batch);
            }
        }
        sample.scale = sampled == 0 ? 1 : (double) lines / sampled;
        out.printf(Locale.ROOT, "Sample: %,d of %,d lines.%n", sampled, lines);
        return sample;
    }

    private static void addSample(final CellMaker maker, final List<RyaStatement> batch) {
        try {
            maker.add(batch, CounterPolicy.ALL);
        } catch (final RuntimeException e) {
            for (final RyaStatement st : batch) {
                try {
                    maker.add(List.of(st), CounterPolicy.ALL);
                } catch (final RuntimeException ignored) {
                    // a statement the store refuses: the job counts it
                }
            }
        }
        batch.clear();
    }

    /**
     * A new store split as {@code prepare} splits one: each table's estimated size on disk over
     * {@code --tablet-size}, at least {@code 4 × servers − 1}, or {@code --splits} ({@code geo} half
     * either way); {@code --tablet-size} made the tables' own split size; the entity index split at
     * its shards.
     */
    private void splitFreshStore(final StoreRdfConfiguration conf, final Sample sample) throws Exception {
        final TableLayoutStrategy t = conf.getTableLayoutStrategy();
        final int floor = Math.max(0, 4 * Math.max(1, store.tables().serverCount()) - 1);
        final Map<String, SortedSet<org.apache.hadoop.io.Text>> plan = new LinkedHashMap<>();
        final List<String> data = List.of(t.getSpo(), t.getPo(), t.getOsp(), t.getEval(), t.getText(), t.getGeo());
        for (final String table : data) {
            if (!store.tables().exists(table)) {
                continue;
            }
            final int n;
            if (o.splits() >= 0) {
                n = table.equals(t.getGeo()) ? o.splits() / 2 : o.splits();
            } else {
                final long bySize = Math.max(0, (long) Math.ceil(sample.bytes(table) / o.compression() / o.tabletSize()) - 1);
                n = (int) Math.min(Integer.MAX_VALUE, Math.max(table.equals(t.getGeo()) ? floor / 2 : floor, bySize));
            }
            final SortedSet<org.apache.hadoop.io.Text> points = SplitPlanner.quantiles(sample.sortedRows(table), n);
            if (!points.isEmpty()) {
                plan.put(table, points);
            }
        }
        SplitPlanner.apply(store, plan);
        // So Accumulo doesn't split them smaller as they grow (its default is 1 GB), as prepare does.
        for (final String table : data) {
            if (store.tables().exists(table)) {
                store.tables().setTargetSize(table, o.tabletSize());
            }
        }
        // A shard is one row: split at every shard but the first, as prepare does (unless --splits 0).
        final List<byte[]> shards = conf.isEntityIndex() ? new EntityIndex(o.entityShards()).shardRows() : List.of();
        if (o.splits() != 0 && shards.size() > 1 && store.tables().splitCount(t.getEntity()) < shards.size() - 1) {
            final SortedSet<byte[]> points = new TreeSet<>(Arrays::compareUnsigned);
            points.addAll(shards.subList(1, shards.size()));
            store.tables().addSplits(t.getEntity(), points);
        }
    }

    /**
     * Each table's file boundaries: its tablets' split rows, plus quantiles of the sampled rows
     * within each tablet so that a file holds about {@code --file-size} of cells; the entity
     * index's shard rows are spread by column instead.
     */
    private Shuffle.Layout layout(final StoreRdfConfiguration conf, final List<String> tables, final Sample sample) {
        final double fileBytes = opts.fileSizeMb * (double) (1L << 20);
        final byte[][][] points = new byte[tables.size()][][];
        final int[] buckets = new int[tables.size()];
        final boolean[] counters = new boolean[tables.size()];
        out.println("Plan (estimated cells, tablets, files):");
        for (int i = 0; i < tables.size(); i++) {
            final String table = tables.get(i);
            counters[i] = isCounters(table);
            final List<byte[]> tablets = store.tables().splitRows(table, Integer.MAX_VALUE);
            final double bytes = sample.bytes(table);
            final TreeSet<byte[]> all = new TreeSet<>(Arrays::compareUnsigned);
            all.addAll(tablets);
            buckets[i] = 1;
            if (table.equals(conf.getTableLayoutStrategy().getEntity())) {
                buckets[i] = (int) Math.max(1, Math.min(MAX_FILES_PER_TABLET, Math.ceil(bytes / (tablets.size() + 1) / fileBytes)));
            } else {
                final List<byte[]> rows = sample.sortedRows(table);
                int r = 0;
                for (int j = 0; j <= tablets.size(); j++) {
                    final byte[] end = j < tablets.size() ? tablets.get(j) : null;
                    final List<byte[]> in = new ArrayList<>();
                    while (r < rows.size() && (end == null || Arrays.compareUnsigned(rows.get(r), end) <= 0)) {
                        in.add(rows.get(r++));
                    }
                    if (rows.isEmpty() || in.size() < 2) {
                        continue;
                    }
                    final int files = (int) Math.max(1, Math.min(MAX_FILES_PER_TABLET,
                            Math.ceil(bytes * in.size() / rows.size() / fileBytes)));
                    for (final org.apache.hadoop.io.Text p : SplitPlanner.quantiles(in, files - 1)) {
                        all.add(Arrays.copyOf(p.getBytes(), p.getLength()));
                    }
                }
            }
            points[i] = all.toArray(new byte[0][]);
            out.printf(Locale.ROOT, "  %-28s %10s %6d %6d%n", table, bytesText(bytes), tablets.size() + 1,
                    (points[i].length + 1) * buckets[i]);
        }
        return new Shuffle.Layout(tables, counters, points, buckets);
    }

    // ------------------------------------------------------------------ import

    private void importFiles(final WorkDir work, final Properties job) {
        final String state = job.getProperty("state");
        if (state.equals("done")) {
            out.println("Import: done already.");
            return;
        }
        if (!state.equals("written") && !state.equals("importing")) {
            throw new StoreCheck.Mismatch("the files of " + work.uri() + " aren't written yet: run --phase write first");
        }
        final long bad = Long.parseLong(job.getProperty("badLines", "0"));
        if (bad > opts.maxBadLines) {
            throw new StoreCheck.Mismatch(bad + " lines of the input were bad and left out, more than --max-bad-lines "
                    + opts.maxBadLines + ": the files are written; import them anyway with --phase import --max-bad-lines " + bad);
        }
        final BulkImport bulk = store.bulk().orElseThrow();
        job.setProperty("state", "importing");
        work.write("job.properties", job);
        final long t0 = System.currentTimeMillis();
        final Map<String, List<BulkImport.Written>> byTable = new TreeMap<>();
        final Properties manifest = work.read("manifest.properties");
        for (final String key : new TreeSet<>(manifest.stringPropertyNames())) {
            final String[] f = manifest.getProperty(key).split("\t");
            byTable.computeIfAbsent(key.substring(0, key.lastIndexOf('/')), k -> new ArrayList<>())
                    .add(new BulkImport.Written(f[0], HexFormat.of().parseHex(f[1]), HexFormat.of().parseHex(f[2]), Long.parseLong(f[3])));
        }
        int imported = 0;
        for (final Map.Entry<String, List<BulkImport.Written>> e : byTable.entrySet()) {
            final String table = e.getKey();
            if (work.importStarted(table)) {
                throw new StoreCheck.Mismatch("an import of " + work.tableDir(table) + " started and didn't finish moving its files. "
                        + "If Accumulo's FATE list (accumulo admin fate --print) still shows a bulk import of " + table + ", wait for "
                        + "it and run --phase import again; if it shows none, delete " + WorkDir.LOAD_MAPPING + " from that directory "
                        + "and run --phase import again");
            }
            final Set<String> left = new LinkedHashSet<>(work.files(table));
            final List<BulkImport.Written> pending = new ArrayList<>();
            for (final BulkImport.Written w : e.getValue()) {
                // A file that is gone was imported: the import moves its files into the table.
                if (left.contains(w.path()) || work.exists(w.path())) {
                    pending.add(w);
                }
            }
            if (!pending.isEmpty()) {
                bulk.importFiles(table, pending);
                imported += pending.size();
            }
        }
        job.setProperty("state", "done");
        work.write("job.properties", job);
        out.printf(Locale.ROOT, "Import: %,d files in %s. The work directory %s can be deleted.%n", imported,
                duration(System.currentTimeMillis() - t0), work.uri());
    }

    // ------------------------------------------------------------------ text

    static String duration(final long millis) {
        final long s = millis / 1000;
        return s < 60 ? String.format(Locale.ROOT, "%.1f s", millis / 1000.0)
                : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    private static String bytesText(final double b) {
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = b;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }

    static String usage() {
        return """
                Usage: spark-submit --class org.tripatha.spark.SparkBuild tripatha.spark-job.jar COMMAND [options]

                Commands:
                  generate  the loader's generated dataset, into a store prepared by the loader (run.sh prepare)
                  files     N-Triples and N-Quads files (.nt, .nq, optionally .gz or .bz2), into a new store or, with
                            --append, one that holds statements
                  smoke     checks the cluster: executors on Java 25 write an RFile under --work-dir, the driver imports
                            it into a scratch table and reads it back

                Options of this job:
                  --work-dir DIR       the job's directory, on one of Accumulo's volumes (required)
                  --phase write,import the phases to run (both by default); each is recorded in the work directory
                  --file-size MB       cells per file, uncompressed (1024)
                  --input PATHS        files: paths, globs or directories, comma-separated
                  --visibility EXPR    files: every statement's label expression (none by default)
                  --max-bad-lines N    files: lines left out before the job stops (0)
                  --distinct           files: leave out repeated statements (one more shuffle); otherwise a repeated
                                       statement is stored once but counted twice, as with load
                  --append             files: add to a store that holds statements (counters of 1 are then kept)
                  --input-splits N     files: at least this many input splits
                  --sample-lines N     files: lines sampled for the plan (200000)
                  --tasks N            generate: tasks writing the entities (4 per core by default)
                  --sample-entities N  generate: entities sampled for the plan (20000)

                The loader's options apply as for its build: --table-prefix, --accumulo-properties (password in
                TRIPATHA_ACCUMULO_PASSWORD), --text-index, --geo-index, --entity-index, --entity-shards,
                --text-predicates, --stats, --tablet-size, --compression, --splits, and the dataset's options for generate.
                """;
    }

    // ------------------------------------------------------------------ options

    /** This job's own options; the rest are the loader's, parsed by {@link LoaderOptions} as for its build. */
    static final class Options {
        private static final Set<String> VALUED = Set.of("input", "visibility", "max-bad-lines", "file-size", "phase", "input-splits",
                "tasks", "sample-lines", "sample-entities");
        private static final Set<String> FLAGS = Set.of("distinct", "append");

        Command command;
        boolean help;
        LoaderOptions loader;
        List<String> loaderArgs = new ArrayList<>();
        List<String> input = new ArrayList<>();
        String visibility;
        long maxBadLines;
        double fileSizeMb = 1024;
        Set<String> phases = Set.of("write", "import");
        int inputSplits;
        int tasks;
        long sampleLines = 200_000;
        int sampleEntities = 20_000;
        boolean distinct;
        boolean append;

        static Options parse(final String[] args, final Map<String, String> env) {
            final Options p = new Options();
            if (args.length == 0 || Set.of("help", "--help", "-h").contains(args[0])) {
                p.help = true;
                return p;
            }
            try {
                p.command = Command.valueOf(args[0].toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown command '" + args[0] + "' (generate, files, smoke)");
            }
            for (int i = 1; i < args.length; i++) {
                final String arg = args[i];
                if (!arg.startsWith("--")) {
                    // A value of one of the loader's options.
                    p.loaderArgs.add(arg);
                    continue;
                }
                String name = arg.substring(2);
                String value = null;
                final int eq = name.indexOf('=');
                if (eq >= 0) {
                    value = name.substring(eq + 1);
                    name = name.substring(0, eq);
                }
                if (VALUED.contains(name)) {
                    if (value == null) {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--" + name + " needs a value");
                        }
                        value = args[++i];
                    }
                    p.set(name, value);
                } else if (FLAGS.contains(name)) {
                    p.set(name, value == null ? "true" : value);
                } else {
                    p.loaderArgs.add(arg);
                }
            }
            final List<String> all = new ArrayList<>();
            all.add("build");
            all.addAll(p.loaderArgs);
            p.loader = LoaderOptions.parse(all.toArray(new String[0]), env);
            if (p.command != Command.FILES && (!p.input.isEmpty() || p.visibility != null || p.distinct || p.append)) {
                throw new IllegalArgumentException("--input, --visibility, --distinct and --append are for files");
            }
            if (p.command == Command.FILES && p.loader.given("triples") || p.command == Command.FILES && p.loader.given("entities")) {
                throw new IllegalArgumentException("--triples and --entities are for generate");
            }
            return p;
        }

        private void set(final String name, final String value) {
            switch (name) {
                case "input" -> Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(input::add);
                case "visibility" -> {
                    org.tripatha.store.visibility.VisibilityExpression.parse(value);
                    visibility = value;
                }
                case "max-bad-lines" -> maxBadLines = Long.parseLong(value);
                case "file-size" -> fileSizeMb = Double.parseDouble(value);
                case "phase" -> {
                    final Set<String> ph = new LinkedHashSet<>();
                    for (final String s : value.split(",")) {
                        final String x = s.trim().toLowerCase(Locale.ROOT);
                        if (!x.equals("write") && !x.equals("import")) {
                            throw new IllegalArgumentException("--phase takes write, import or both (got " + s + ")");
                        }
                        ph.add(x);
                    }
                    phases = ph;
                }
                case "input-splits" -> inputSplits = Integer.parseInt(value);
                case "tasks" -> tasks = Integer.parseInt(value);
                case "sample-lines" -> sampleLines = Long.parseLong(value);
                case "sample-entities" -> sampleEntities = Integer.parseInt(value);
                case "distinct" -> distinct = Boolean.parseBoolean(value);
                case "append" -> append = Boolean.parseBoolean(value);
                default -> throw new IllegalArgumentException("unknown option --" + name);
            }
            if (!(fileSizeMb > 0) || maxBadLines < 0) {
                throw new IllegalArgumentException("--file-size must be above 0 and --max-bad-lines at least 0");
            }
        }

        /**
         * The loader's arguments for the tasks: a dataset size in triples becomes entities, so a
         * task doesn't sample the generator to find it.
         */
        String[] executorArgs(final LoaderOptions o) {
            final List<String> a = new ArrayList<>();
            a.add("build");
            for (int i = 0; i < loaderArgs.size(); i++) {
                final String arg = loaderArgs.get(i);
                if (arg.equals("--triples")) {
                    i++;
                } else if (!arg.startsWith("--triples=")) {
                    a.add(arg);
                }
            }
            if (o.given("triples")) {
                a.add("--entities");
                a.add(Long.toString(o.genConfig().entities()));
            }
            return a.toArray(new String[0]);
        }
    }
}
