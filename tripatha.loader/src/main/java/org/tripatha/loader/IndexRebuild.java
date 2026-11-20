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
package org.tripatha.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.api.resolver.triple.TripleRow;
import org.tripatha.loader.bulk.SpillBuffer;
import org.tripatha.loader.bulk.Spills;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.index.IndexManager;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.RebuildLog;
import org.tripatha.store.index.SecondaryIndex;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.BulkImport;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.WriterOptions;

/**
 * Builds a store's indexes that aren't ready (SCALE-STUDY.md O5) as a batch job: {@code spo} read
 * by split range on worker threads (and processes, with {@code --part}), the indexes' cells
 * sorted into runs, merged into files in the backend's format and imported, as the bulk build
 * does ({@link BulkFiles}). Every phase resumes. Any store will do, whatever wrote it.
 * <ol>
 * <li><b>start:</b> the store, opened with this job's id, marks the indexes that aren't ready
 * BUILDING for the job (clearing a redefined one) and records the job. From then on its writers
 * record their deletes ({@link RebuildLog}), and graph drops wait for the job.</li>
 * <li><b>sort:</b> each range of {@code spo} (its split rows, fixed the first time) read with every
 * label the account holds, each statement's index cells spilled to sorted runs, recorded with the
 * range in one write, with a sample of the range's rows.</li>
 * <li><b>merge:</b> the index tables split as their sizes ask (from the samples; the entity index
 * at each shard), then each table's runs merged per row range.</li>
 * <li><b>import</b> every file once.</li>
 * <li><b>finish</b>, once every part imported: every recorded delete whose cell is no longer in
 * {@code spo} has its index cells deleted (the import may have brought them back), then the
 * indexes become READY and the job's record and deletes go.</li>
 * </ol>
 * Statements added meanwhile need nothing: writers maintain a BUILDING index as they add.
 */
public final class IndexRebuild {
    /** The phases {@code --phase} selects. */
    public enum Phase {
        START, SORT, MERGE, IMPORT, FINISH;
    }

    /** A worker merges its runs of a table into one when it has this many. */
    static final int FAN_IN = 64;
    /** {@link #FAN_IN}, lowered by tests. */
    static volatile int fanIn = FAN_IN;
    /** Rows kept per table and range to plan the index tables' splits. */
    static final int SAMPLE = 512;
    /** Called after a range's runs are written and before they are recorded: tests make it throw. */
    static volatile Runnable beforeCommit;

    private final Store store;
    private final LoaderOptions o;
    private final PrintStream out;
    private final Path work;
    private final Spills spills;
    private final TableLayoutStrategy tables;

    public IndexRebuild(final Store store, final LoaderOptions o, final PrintStream out) {
        this.store = store;
        this.o = o;
        this.out = out;
        this.work = Path.of(o.workDir());
        this.spills = new Spills(work.toAbsolutePath().toString());
        this.tables = o.rdfConf().getTableLayoutStrategy();
    }

    /** @return true when every phase asked for is done */
    public boolean run() throws Exception {
        if (!store.tables().exists(tables.getSpo())) {
            throw new StoreCheck.Mismatch("there is no store with the prefix " + o.tablePrefix() + " (no " + tables.getSpo() + ")");
        }
        final BulkImport bulk = store.bulk().orElseThrow(() -> new IllegalArgumentException("the " + store.backend()
                + " backend can't import files"));
        Files.createDirectories(work);
        final Path jobFile = work.resolve("rebuild.properties");
        final Properties job = BulkFiles.load(jobFile);
        if (job.getProperty("job") == null) {
            job.setProperty("job", "rebuild-" + o.tablePrefix() + "-" + System.currentTimeMillis());
            job.setProperty("prefix", o.tablePrefix());
            job.setProperty("time", Long.toString(System.currentTimeMillis()));
            BulkFiles.save(jobFile, job);
        } else if (!o.tablePrefix().equals(job.getProperty("prefix"))) {
            throw new StoreCheck.Mismatch("the work directory " + work.toAbsolutePath() + " belongs to the rebuild of "
                    + job.getProperty("prefix") + ": give this one a directory of its own");
        }
        final String id = job.getProperty("job");
        final StoreRdfConfiguration conf = o.rdfConf();
        if (!o.given("entity-shards")) {
            // The entity index's stored shard count, if it has one.
            conf.unset(RdfCloudTripleStoreConfiguration.CONF_INDEX_ENTITY_SHARDS);
        }
        conf.setRebuildIndexes(false);
        conf.setIndexRebuildJob(id);
        final Set<Phase> phases = o.phases(Phase.class);

        final RyaStoreDAO dao = new RyaStoreDAO(store, conf);
        dao.init();
        try {
            if (!(dao.getIndexCatalog() instanceof IndexManager indexes)) {
                throw new IllegalStateException("the store has no index manager");
            }
            final Map<IndexType, SecondaryIndex> building = indexes.building();
            if (building.isEmpty()) {
                if (id.equals(IndexManager.readJob(store, tables))) {
                    // A finish that stopped after making the indexes READY: end it.
                    indexes.finishRebuild(id, Set.of());
                    out.println("Rebuild job " + id + " is done.");
                } else {
                    out.println("Nothing to rebuild: every enabled index of " + o.tablePrefix() + " is READY.");
                }
                return true;
            }
            final String definitions = describe(building);
            if (job.getProperty("indexes") == null) {
                job.setProperty("indexes", definitions);
                BulkFiles.save(jobFile, job);
            } else if (!job.getProperty("indexes").equals(definitions)) {
                throw new StoreCheck.Mismatch("rebuild job " + id + " builds " + job.getProperty("indexes") + ", but these options "
                        + "build " + definitions + ": run it with its own index options");
            }
            out.printf(Locale.ROOT, "Rebuild job %s: building %s of %s from %s, with every label of %s.%n", id, building.keySet(),
                    o.tablePrefix(), tables.getSpo(), store.access().whoami());
            out.println("Until it finishes, writers must run with these index settings; they record their deletes, "
                    + "and graph drops are refused.");
            final long time = Long.parseLong(job.getProperty("time"));
            if (phases.contains(Phase.SORT) && !sort(conf, building, time)) {
                return false;
            }
            final List<String> indexTables = building.keySet().stream().map(t -> t.table(tables)).toList();
            final BulkFiles files = new BulkFiles(store, spills, work, o.part(), o.parts(), o.threads(), out);
            if (phases.contains(Phase.MERGE)) {
                final Map<String, List<String>> runs = sortedRuns();
                split(building, job, jobFile);
                files.merge(bulk, runs, indexTables, t -> false, (family, total) -> false, time);
            }
            if (phases.contains(Phase.IMPORT)) {
                files.importFiles(bulk, indexTables);
            }
            if (phases.contains(Phase.FINISH)) {
                if (o.parts() > 1 && !o.phasesListed()) {
                    out.println("Run --phase finish once, when every part has imported.");
                    return true;
                }
                finish(conf, indexes, building, id);
                out.println("The work directory " + work.toAbsolutePath() + " can be deleted.");
            }
            return true;
        } finally {
            dao.destroy();
        }
    }

    private static String describe(final Map<IndexType, SecondaryIndex> building) {
        final StringBuilder b = new StringBuilder();
        building.forEach((type, index) -> b.append(b.isEmpty() ? "" : "; ").append(type.id()).append('=').append(index.definition()));
        return b.toString();
    }

    // ------------------------------------------------------------------ sort

    private String partTag() {
        return "p" + o.part() + "of" + o.parts();
    }

    /** The split rows of spo, fixed the first time: they change as tables split. */
    private List<byte[]> plan() throws IOException {
        final Path file = work.resolve("rebuild-plan.properties");
        final Properties p = BulkFiles.load(file);
        if (p.getProperty("spo") == null) {
            final List<String> rows = new ArrayList<>();
            for (final byte[] row : store.tables().splitRows(tables.getSpo(), Integer.MAX_VALUE)) {
                rows.add(HexFormat.of().formatHex(row));
            }
            p.setProperty("spo", String.join(",", rows));
            BulkFiles.save(file, p);
        }
        final List<byte[]> rows = new ArrayList<>();
        for (final String row : p.getProperty("spo").isEmpty() ? new String[0] : p.getProperty("spo").split(",")) {
            rows.add(HexFormat.of().parseHex(row));
        }
        return rows;
    }

    private final Object commitLock = new Object();
    /** Each worker's runs, by table; their union is what the progress file holds. */
    private final Map<Integer, Map<String, List<String>>> runs = new TreeMap<>();
    private final AtomicLong sortedStatements = new AtomicLong();

    private boolean sort(final StoreRdfConfiguration conf, final Map<IndexType, SecondaryIndex> building, final long time)
            throws Exception {
        final List<byte[]> splits = plan();
        final List<Integer> mine = new ArrayList<>();
        for (int i = 0; i <= splits.size(); i++) {
            if (i % o.parts() == o.part()) {
                mine.add(i);
            }
        }
        final String fingerprint = "rebuild|" + o.tablePrefix() + "|" + splits.size() + "|" + describe(building);
        final Path progressFile = work.resolve("sort-" + partTag() + ".progress");
        final Progress progress = Progress.open(progressFile, fingerprint, mine.size(),
                o.resume() || Files.exists(progressFile) && complete(progressFile, fingerprint, mine.size()));
        final Set<String> kept = new LinkedHashSet<>(progress.files());
        final List<String> orphans = new ArrayList<>();
        for (final String f : spills.listSpills()) {
            if (f.contains("/" + partTag() + "-") && !kept.contains(f)) {
                orphans.add(f);
            }
        }
        // Runs of ranges that weren't recorded: they are read again.
        spills.delete(orphans);
        final Map<String, List<String>> resumed = new LinkedHashMap<>();
        for (final String f : progress.files()) {
            resumed.computeIfAbsent(table(f), k -> new ArrayList<>()).add(f);
        }
        runs.put(-1, resumed);
        if (progress.complete()) {
            out.println("Sort: done already (" + progress.files().size() + " runs).");
            return true;
        }
        final long t0 = System.currentTimeMillis();
        final AtomicLong cursor = new AtomicLong();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final long memory = Math.max(1L << 20, o.spillMemoryBytes() / o.threads());
        out.printf(Locale.ROOT, "Sorting %d ranges of %s (part %d of %d) with %d threads, %d MB of cells each per spill%n",
                mine.size(), tables.getSpo(), o.part(), o.parts(), o.threads(), memory >> 20);
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < o.threads(); i++) {
            final int worker = i;
            final Thread th = new Thread(() -> {
                try {
                    sortWorker(worker, conf, building, splits, mine, cursor, progress, memory, time, failure);
                } catch (final Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "tripatha-rebuild-" + i);
            threads.add(th);
            th.start();
        }
        for (final Thread th : threads) {
            th.join();
        }
        if (failure.get() != null) {
            throw new IllegalStateException("the sort failed (resume with --resume)", failure.get());
        }
        final long millis = Math.max(1, System.currentTimeMillis() - t0);
        out.printf(Locale.ROOT, "Sort: %,d statements in %s (%,d statements/s), %d runs.%n", sortedStatements.get(),
                BulkFiles.duration(millis), sortedStatements.get() * 1000 / millis, progress.files().size());
        return progress.complete();
    }

    private static boolean complete(final Path file, final String fingerprint, final long blocks) throws IOException {
        try {
            return Progress.open(file, fingerprint, blocks, true).complete();
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private void sortWorker(final int worker, final StoreRdfConfiguration conf, final Map<IndexType, SecondaryIndex> building,
            final List<byte[]> splits, final List<Integer> mine, final AtomicLong cursor, final Progress progress, final long memory,
            final long time, final AtomicReference<Throwable> failure) throws Exception {
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);
        final SpillBuffer buffer = new SpillBuffer(time);
        int spill = 0;
        while (failure.get() == null) {
            final long k = cursor.getAndIncrement();
            if (k >= mine.size()) {
                break;
            }
            if (progress.isCommitted(k)) {
                continue;
            }
            final int range = mine.get((int) k);
            final byte[] from = range == 0 ? null : splits.get(range - 1);
            final byte[] to = range == splits.size() ? null : splits.get(range);
            final KeyRange rows = from == null && to == null ? KeyRange.all() : KeyRange.rows(from, true, to, false);
            final Sample sample = new Sample(range);
            final Map<String, List<String>> written = new LinkedHashMap<>();
            long statements = 0;
            try (CellScanner cells = store.scan(tables.getSpo(), rows, ScanSpec.of(Auths.ALL_HELD))) {
                while (cells.hasNext() && failure.get() == null) {
                    final Cell c = cells.next();
                    final RyaStatement st = context.deserializeTriple(TABLE_LAYOUT.SPO,
                            new TripleRow(c.row(), c.family(), c.qualifier(), c.timestamp(), c.visibility(), c.value()));
                    for (final Map.Entry<IndexType, SecondaryIndex> e : building.entrySet()) {
                        final String table = e.getKey().table(tables);
                        for (final RowMutation m : e.getValue().puts(st)) {
                            buffer.accept(table, m);
                            sample.add(table, m);
                        }
                    }
                    statements++;
                    if (buffer.bytes() >= memory) {
                        spillTo(buffer, worker, spill++, written);
                    }
                }
            }
            if (failure.get() != null) {
                break;
            }
            spillTo(buffer, worker, spill++, written);
            commit(worker, (int) k, sample, written, progress, statements);
        }
    }

    private void spillTo(final SpillBuffer buffer, final int worker, final int spill, final Map<String, List<String>> written) {
        if (buffer.isEmpty()) {
            return;
        }
        final String name = partTag() + "-t" + worker + "-" + spill + "-" + System.nanoTime();
        buffer.spill(spills, name).forEach((table, file) -> written.computeIfAbsent(table, t -> new ArrayList<>()).add(file));
    }

    /**
     * Writes the range's sample, then records the range with its runs in one write; merges a
     * worker's runs of a table past {@link #fanIn}. The worker's list changes only with that write.
     */
    private void commit(final int worker, final int k, final Sample sample, final Map<String, List<String>> written,
            final Progress progress, final long statements) throws IOException {
        sample.save(work);
        final Map<String, List<String>> mineRuns = new LinkedHashMap<>();
        synchronized (commitLock) {
            runs.getOrDefault(worker, Map.of()).forEach((table, files) -> mineRuns.put(table, new ArrayList<>(files)));
        }
        written.forEach((table, files) -> mineRuns.computeIfAbsent(table, t -> new ArrayList<>()).addAll(files));
        final List<String> replaced = new ArrayList<>();
        for (final Map.Entry<String, List<String>> e : mineRuns.entrySet()) {
            if (e.getValue().size() >= fanIn) {
                final List<String> inputs = List.copyOf(e.getValue());
                final String merged;
                try (Spills.Reader cells = spills.read(inputs, null, null, false)) {
                    merged = spills.write(e.getKey(), partTag() + "-t" + worker + "-m" + k + "-" + System.nanoTime(), cells);
                }
                replaced.addAll(inputs);
                e.setValue(new ArrayList<>(List.of(merged)));
            }
        }
        final Runnable hook = beforeCommit;
        if (hook != null) {
            hook.run();
        }
        synchronized (commitLock) {
            runs.put(worker, mineRuns);
            final List<String> all = new ArrayList<>();
            for (final Map<String, List<String>> byTable : runs.values()) {
                byTable.values().forEach(all::addAll);
            }
            progress.commit(List.of((long) k), statements, all);
            sortedStatements.addAndGet(statements);
        }
        spills.delete(replaced);
    }

    private static String table(final String run) {
        return run.split("/")[1];
    }

    /** Every part's runs, by table; every part must have sorted. */
    private Map<String, List<String>> sortedRuns() throws IOException {
        final Map<String, List<String>> byTable = new LinkedHashMap<>();
        for (int part = 0; part < o.parts(); part++) {
            final Path file = work.resolve("sort-p" + part + "of" + o.parts() + ".progress");
            if (!Files.exists(file)) {
                throw new IllegalStateException("part " + part + " of " + o.parts() + " hasn't sorted yet (" + file + ")");
            }
            final Properties p = BulkFiles.load(file);
            if (!Long.toString(Long.parseLong(p.getProperty("blocks", "0"))).equals(p.getProperty("watermark"))) {
                throw new IllegalStateException("part " + part + " of " + o.parts() + " hasn't finished sorting (" + file + ")");
            }
            final String files = p.getProperty("files", "");
            for (final String f : files.isEmpty() ? new String[0] : files.split("\n")) {
                byTable.computeIfAbsent(table(f), k -> new ArrayList<>()).add(f);
            }
        }
        return byTable;
    }

    // ------------------------------------------------------------------ splits

    /**
     * A range's sample, per index table: how many cells and bytes it wrote, and up to
     * {@link #SAMPLE} of its rows, picked uniformly (each stands for cells / rows kept).
     */
    static final class Sample {
        private final int range;
        private final Random random;
        private final Map<String, long[]> sizes = new TreeMap<>();
        private final Map<String, List<byte[]>> rows = new TreeMap<>();

        Sample(final int range) {
            this.range = range;
            this.random = new Random(range * 0x9E3779B97F4A7C15L);
        }

        void add(final String table, final RowMutation m) {
            final long[] size = sizes.computeIfAbsent(table, t -> new long[2]);
            final List<byte[]> kept = rows.computeIfAbsent(table, t -> new ArrayList<>());
            for (final RowMutation.Update u : m.updates()) {
                size[0]++;
                size[1] += m.row().length + length(u.family()) + length(u.qualifier()) + length(u.visibility()) + Long.BYTES
                        + length(u.value());
                if (kept.size() < SAMPLE) {
                    kept.add(m.row());
                } else {
                    final long j = (long) (random.nextDouble() * size[0]);
                    if (j < SAMPLE) {
                        kept.set((int) j, m.row());
                    }
                }
            }
        }

        private static int length(final byte[] b) {
            return b == null ? 0 : b.length;
        }

        void save(final Path work) throws IOException {
            final Path dir = work.resolve("samples");
            Files.createDirectories(dir);
            final Path file = dir.resolve("range-" + range + ".txt");
            final Path tmp = dir.resolve("range-" + range + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (final Map.Entry<String, long[]> e : sizes.entrySet()) {
                    w.write("T\t" + e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
                    for (final byte[] row : rows.get(e.getKey())) {
                        w.write("R\t" + e.getKey() + "\t" + HexFormat.of().formatHex(row) + "\n");
                    }
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** A sampled row and the cells it stands for. */
    record Weighted(byte[] row, double weight) {
    }

    /**
     * Splits the index tables once, before the merge fixes its ranges, as prepare splits a store:
     * each table's size on disk (its cells' bytes over {@code --compression}) over
     * {@code --tablet-size}, at least 4 per server (geo half that), at the weighted quantiles of
     * the sampled rows; the entity index at each of its shards. Then the tables' split threshold.
     * On RocksDB the points are only hints, which the merge cuts its ranges at: at least one range
     * per thread, so the merge runs on all of them.
     */
    private void split(final Map<IndexType, SecondaryIndex> building, final Properties job, final Path jobFile) throws IOException {
        if (job.getProperty("split") != null) {
            return;
        }
        {
            final boolean local = store.backend().equals("rocksdb");
            final Map<String, List<Weighted>> rows = new TreeMap<>();
            final Map<String, Long> bytes = new TreeMap<>();
            readSamples(rows, bytes);
            final int servers = Math.max(1, store.tables().serverCount());
            for (final Map.Entry<IndexType, SecondaryIndex> e : building.entrySet()) {
                final String table = e.getKey().table(tables);
                final SortedSet<byte[]> points = new TreeSet<>(Arrays::compareUnsigned);
                if (e.getValue() instanceof EntityIndex entity) {
                    final List<byte[]> shards = entity.shardRows();
                    if (shards.size() > 1) {
                        points.addAll(shards.subList(1, shards.size()));
                    }
                } else {
                    final double disk = bytes.getOrDefault(table, 0L) / o.compression();
                    final long bySize = Math.max(0, (long) Math.ceil(disk / o.tabletSize()) - 1);
                    final int perServers = e.getKey() == IndexType.GEO ? (4 * servers - 1) / 2 : 4 * servers - 1;
                    final int floor = local ? Math.max(0, o.threads() - 1) : perServers;
                    points.addAll(quantiles(rows.getOrDefault(table, List.of()), (int) Math.min(1_000_000, Math.max(floor, bySize))));
                }
                if (store.tables().splitCount(table) < points.size()) {
                    store.tables().addSplits(table, points);
                    out.printf(Locale.ROOT, "Split %s at %,d rows.%n", table, points.size());
                }
                store.tables().setTargetSize(table, o.tabletSize());
            }
        }
        job.setProperty("split", "done");
        BulkFiles.save(jobFile, job);
    }

    private void readSamples(final Map<String, List<Weighted>> rows, final Map<String, Long> bytes) throws IOException {
        final Path dir = work.resolve("samples");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (final Path file : files.filter(f -> f.getFileName().toString().endsWith(".txt")).toList()) {
                final Map<String, long[]> sizes = new TreeMap<>();
                final Map<String, List<byte[]>> kept = new TreeMap<>();
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        final String[] f = line.split("\t");
                        if (f[0].equals("T")) {
                            sizes.put(f[1], new long[] {Long.parseLong(f[2]), Long.parseLong(f[3])});
                        } else {
                            kept.computeIfAbsent(f[1], t -> new ArrayList<>()).add(HexFormat.of().parseHex(f[2]));
                        }
                    }
                }
                sizes.forEach((table, size) -> {
                    bytes.merge(table, size[1], Long::sum);
                    final List<byte[]> sampled = kept.getOrDefault(table, List.of());
                    for (final byte[] row : sampled) {
                        rows.computeIfAbsent(table, t -> new ArrayList<>()).add(new Weighted(row, (double) size[0] / sampled.size()));
                    }
                });
            }
        }
    }

    /** Up to {@code n} distinct split points at the weighted quantiles of the rows. */
    static SortedSet<byte[]> quantiles(final List<Weighted> rows, final int n) {
        final SortedSet<byte[]> points = new TreeSet<>(Arrays::compareUnsigned);
        if (n <= 0 || rows.size() < 2) {
            return points;
        }
        final List<Weighted> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Arrays.compareUnsigned(a.row(), b.row()));
        double total = 0;
        for (final Weighted w : sorted) {
            total += w.weight();
        }
        double seen = 0;
        int j = 1;
        for (int i = 1; i < sorted.size() && j <= n; i++) {
            seen += sorted.get(i - 1).weight();
            if (seen >= total * j / (n + 1)) {
                if (Arrays.compareUnsigned(sorted.get(i - 1).row(), sorted.get(i).row()) < 0) {
                    points.add(SplitPlanner.separator(sorted.get(i - 1).row(), sorted.get(i).row()));
                }
                while (j <= n && seen >= total * j / (n + 1)) {
                    j++;
                }
            }
        }
        return points;
    }

    // ------------------------------------------------------------------ finish

    /**
     * Replays the deletes recorded since the job started, in two passes, then makes the indexes
     * READY and removes the job's record and the deletes.
     * <ol>
     * <li>Every recorded cell: if it is no longer in {@code spo}, its index cells are deleted at
     * its own timestamp (an imported file may hold them). If it is there (added again, or its
     * delete never happened), its index cells are written again from the stored cell: an imported
     * file may have replaced them with an older version (RocksDB keeps the last write).</li>
     * <li>The cells recorded since the first pass read the record: a delete during the first pass
     * may have come between its read of {@code spo} and its write. Those no longer in {@code spo}
     * are deleted again; nothing is written. A delete recorded after this pass read the record
     * came after the first pass's writes, and is right as its writer made it.</li>
     * </ol>
     */
    private void finish(final StoreRdfConfiguration conf, final IndexManager indexes, final Map<IndexType, SecondaryIndex> building,
            final String id) throws Exception {
        final long t0 = System.currentTimeMillis();
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);
        final List<RebuildLog.Entry> first = RebuildLog.read(store, tables, Auths.ALL_HELD);
        final long[] firstPass = replay(first, building, context, true);
        final Set<String> seen = new HashSet<>();
        for (final RebuildLog.Entry e : first) {
            seen.add(key(e.row(), e.graph(), e.qualifier(), e.visibility()) + "@" + e.timestamp());
        }
        final List<RebuildLog.Entry> later = new ArrayList<>();
        for (final RebuildLog.Entry e : RebuildLog.read(store, tables, Auths.ALL_HELD)) {
            if (!seen.contains(key(e.row(), e.graph(), e.qualifier(), e.visibility()) + "@" + e.timestamp())) {
                later.add(e);
            }
        }
        final long[] secondPass = replay(later, building, context, false);
        final List<IndexType> ready = indexes.finishRebuild(id, building.keySet());
        out.printf(Locale.ROOT, "Finish: %,d deletes recorded during the job, %,d replayed, %,d written again (%,d more in a second "
                + "pass), in %s; %s READY.%n", first.size(), firstPass[0] + secondPass[0], firstPass[1], later.size(),
                BulkFiles.duration(Math.max(1, System.currentTimeMillis() - t0)), ready);
    }

    /**
     * Deletes the index cells of the recorded cells that are no longer in {@code spo}, and with
     * {@code rewrite} writes again those of the ones that are.
     *
     * @return the cells deleted, and written again
     */
    private long[] replay(final List<RebuildLog.Entry> recorded, final Map<IndexType, SecondaryIndex> building,
            final RyaTripleContext context, final boolean rewrite) throws Exception {
        final long[] done = new long[2];
        if (recorded.isEmpty()) {
            return done;
        }
        final SortedSet<byte[]> rows = new TreeSet<>(Arrays::compareUnsigned);
        for (final RebuildLog.Entry e : recorded) {
            rows.add(e.row());
        }
        final Map<String, Cell> present = new java.util.HashMap<>();
        final List<KeyRange> ranges = rows.stream().map(KeyRange::row).toList();
        try (CellScanner cells = store.batchScan(tables.getSpo(), ranges, ScanSpec.of(Auths.ALL_HELD))) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                present.put(key(c.row(), c.family(), c.qualifier(), c.visibility()), c);
            }
        }
        try (CellWriter writer = store.newWriter(WriterOptions.DEFAULT)) {
            for (final RebuildLog.Entry e : recorded) {
                final Cell stored = present.get(key(e.row(), e.graph(), e.qualifier(), e.visibility()));
                if (stored != null && !rewrite) {
                    continue;
                }
                final RyaStatement st = stored == null
                        ? context.deserializeTriple(TABLE_LAYOUT.SPO,
                                new TripleRow(e.row(), e.graph(), e.qualifier(), e.timestamp(), e.visibility(), e.value()))
                        : context.deserializeTriple(TABLE_LAYOUT.SPO, new TripleRow(stored.row(), stored.family(), stored.qualifier(),
                                stored.timestamp(), stored.visibility(), stored.value()));
                for (final Map.Entry<IndexType, SecondaryIndex> b : building.entrySet()) {
                    final List<RowMutation> mutations = stored == null ? b.getValue().deletes(st) : b.getValue().puts(st);
                    if (!mutations.isEmpty()) {
                        writer.table(b.getKey().table(tables)).write(mutations);
                    }
                }
                done[stored == null ? 0 : 1]++;
            }
            writer.flush();
        }
        return done;
    }

    private static String key(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility) {
        final HexFormat hex = HexFormat.of();
        return hex.formatHex(row) + "|" + hex.formatHex(family == null ? new byte[0] : family) + "|"
                + hex.formatHex(qualifier == null ? new byte[0] : qualifier) + "|"
                + hex.formatHex(visibility == null ? new byte[0] : visibility);
    }
}
