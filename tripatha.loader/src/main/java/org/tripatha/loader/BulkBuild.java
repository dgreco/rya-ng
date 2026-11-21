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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.tripatha.api.RdfCloudTripleStoreConstants;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.loader.bulk.CapturingStore;
import org.tripatha.loader.bulk.SpillBuffer;
import org.tripatha.loader.bulk.Spills;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.store.EvalStatsMutations;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.BulkImport;
import org.tripatha.store.spi.Store;

/**
 * The bulk build (SCALE-STUDY.md 6.2, O3): the dataset written as sorted files in each backend's
 * own format and imported, instead of through the write path. Three phases, each resumable:
 * <ol>
 * <li><b>sort:</b> worker threads generate their blocks and write them with a DAO of their own
 * over a {@link CapturingStore}, so every cell is the one {@code load} writes. The cells of the
 * statement, index and counter tables are combined in memory and spilled to sorted runs per
 * table ({@link Spills}); the few others (graph markers) go to the store as {@code load} writes
 * them. A run is recorded in the part's progress file with the blocks it holds, in one write,
 * so a resume neither loses nor repeats a block. A worker merges its runs of a table once it has
 * {@link #FAN_IN} of them, so the next phase reads a bounded number of files per tablet.</li>
 * <li><b>merge:</b> for each table and each of its tablets' row ranges, the runs of every part
 * are merged into one file in the backend's format ({@link BulkImport}). Counter totals of 1 are
 * left out except in family {@code p} (D6, section 6.3), with {@code --stats-ones skip}; the
 * datatype markers always stay.</li>
 * <li><b>import:</b> every file once. Backends move the files they import, so a file that is gone
 * after a failure was imported.</li>
 * </ol>
 */
public final class BulkBuild {
    /** A worker merges its runs of a table into one when it has this many. */
    static final int FAN_IN = 64;
    /** {@link #FAN_IN}, lowered by tests to merge runs of a small dataset. */
    static volatile int fanIn = FAN_IN;
    /** Called after a spill is written and before it is recorded: tests make it throw, as a crash there would. */
    static volatile Runnable beforeCommit;

    /** The phases {@code --phase} selects. */
    public enum Phase {
        SORT, MERGE, IMPORT;
    }

    private final Store store;
    private final LoaderOptions o;
    private final PrintStream out;
    private final Spills spills;
    private final Path work;

    public BulkBuild(final Store store, final LoaderOptions o, final PrintStream out) {
        this.store = store;
        this.o = o;
        this.out = out;
        this.work = Path.of(o.workDir());
        this.spills = new Spills(work.toAbsolutePath().toString());
    }

    /** @return true when every phase asked for is done */
    public boolean run() throws Exception {
        StoreCheck.forLoad(store, o);
        final BulkImport bulk = store.bulk().orElseThrow(() -> new IllegalArgumentException("the " + store.backend()
                + " backend can't import files yet: use load"));
        final StoreRdfConfiguration conf = o.rdfConf();
        final List<String> tables = bulkTables(conf);
        final String eval = conf.getTableLayoutStrategy().getEval();
        // Counters: the statistics, and the text index's posting counts (O6).
        final List<String> counterTables = new java.util.ArrayList<>();
        if (conf.isMaintainStats()) {
            counterTables.add(eval);
        }
        if (tables.contains(conf.getTableLayoutStrategy().getTextCount())) {
            counterTables.add(conf.getTableLayoutStrategy().getTextCount());
        }
        for (final String counters : counterTables) {
            final var refused = bulk.refusesCounters(counters);
            if (refused.isPresent()) {
                throw new StoreCheck.Mismatch(refused.get());
            }
        }
        Files.createDirectories(work);
        final Properties build = buildProperties();
        final Set<Phase> phases = o.phases();
        if (phases.contains(Phase.SORT) && !sort(conf, tables)) {
            return false;
        }
        if (phases.contains(Phase.MERGE)) {
            merge(bulk, tables, eval, Long.parseLong(build.getProperty("importTime")));
        }
        if (phases.contains(Phase.IMPORT)) {
            importFiles(bulk, tables);
        }
        return true;
    }

    /** The tables whose cells go into files: statements, counters and the indexes that exist. */
    private List<String> bulkTables(final StoreRdfConfiguration conf) {
        final TableLayoutStrategy t = conf.getTableLayoutStrategy();
        final List<String> tables = new ArrayList<>();
        for (final String name : List.of(t.getSpo(), t.getPo(), t.getOsp(), t.getEval(), t.getText(), t.getGeo(), t.getEntity(),
                t.getTextCount())) {
            if (store.tables().exists(name)) {
                tables.add(name);
            }
        }
        return tables;
    }

    /**
     * The build's own settings, written once: which build the work directory belongs to, and the
     * timestamp of its imported counters (HBase's coprocessor mode needs one per build: a second
     * build reusing it would write counter cells that collapse into the first one's).
     */
    private Properties buildProperties() throws IOException {
        final Path file = work.resolve("build.properties");
        final Properties p = load(file);
        final String build = o.genConfig().fingerprint() + "|" + o.tablePrefix() + "|" + o.parts();
        if (p.getProperty("build") != null && !p.getProperty("build").equals(build)) {
            throw new StoreCheck.Mismatch("the work directory " + work.toAbsolutePath() + " belongs to another build (another "
                    + "dataset, prefix or number of parts): give this one a directory of its own, or delete that one");
        }
        if (p.getProperty("importTime") == null) {
            p.setProperty("build", build);
            p.setProperty("importTime", Long.toString(System.currentTimeMillis()));
            save(file, p);
        }
        return p;
    }

    // ------------------------------------------------------------------ sort

    private final Object commitLock = new Object();
    /** Each worker's runs, by table; their union is what the progress file holds. */
    private final Map<Integer, Map<String, List<String>>> runs = new TreeMap<>();
    private final AtomicLong sortedTriples = new AtomicLong();

    private String partTag() {
        return "p" + o.part() + "of" + o.parts();
    }

    private boolean sort(final StoreRdfConfiguration conf, final List<String> tables) throws Exception {
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final long[] range = o.partRange();
        final int blockSize = o.blockSize();
        final long blocks = (range[1] - range[0] + blockSize - 1) / blockSize;
        final String fingerprint = o.genConfig().fingerprint() + "|" + o.tablePrefix() + "|" + range[0] + "-" + range[1] + "|"
                + blockSize + "|" + o.textIndex() + o.geoIndex() + o.textPredicates()
                + (o.entityIndex() ? "|entity" + o.entityShards() : "") + "|" + o.stats() + "|" + o.statsOnes() + "|bulk";
        final Path progressFile = work.resolve("sort-" + partTag() + ".progress");
        final Progress progress = Progress.open(progressFile, fingerprint, blocks,
                o.resume() || Files.exists(progressFile) && complete(progressFile, fingerprint, blocks));
        // Runs no progress file lists are from blocks that weren't committed: they will be written again.
        final Set<String> kept = new LinkedHashSet<>(progress.files());
        final List<String> orphans = new ArrayList<>();
        for (final String f : spills.listSpills()) {
            if (f.contains("/" + partTag() + "-") && !kept.contains(f)) {
                orphans.add(f);
            }
        }
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
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final long perThread = Math.max(1L << 20, o.spillMemoryBytes() / o.threads());
        out.printf(Locale.ROOT, "Sorting entities [%,d, %,d) (part %d of %d) with %d threads, %d MB of cells each per spill%n",
                range[0], range[1], o.part(), o.parts(), o.threads(), perThread >> 20);
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < o.threads(); i++) {
            final int worker = i;
            final Thread th = new Thread(() -> {
                try {
                    sortWorker(worker, conf, tables, generator, range[0], range[1], blockSize, blocks, cursor, progress, perThread,
                            failure);
                } catch (final Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "tripatha-build-" + i);
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
        out.printf(Locale.ROOT, "Sort: %,d triples in %s (%,d triples/s), %d runs.%n", sortedTriples.get(), duration(millis),
                sortedTriples.get() * 1000 / millis, progress.files().size());
        return progress.complete();
    }

    private static boolean complete(final Path file, final String fingerprint, final long blocks) throws IOException {
        try {
            return Progress.open(file, fingerprint, blocks, true).complete();
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private void sortWorker(final int worker, final StoreRdfConfiguration ignored, final List<String> tables,
            final EntityGenerator generator, final long first, final long last, final int blockSize, final long blocks,
            final AtomicLong cursor, final Progress progress, final long memory,
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure) throws Exception {
        final StoreRdfConfiguration conf = o.rdfConf();
        conf.setFlush(false);
        final SpillBuffer buffer = new SpillBuffer(System.currentTimeMillis());
        final RyaStoreDAO dao = new RyaStoreDAO(new CapturingStore(store, Set.copyOf(tables), buffer), conf);
        int spill = 0;
        final List<Long> pending = new ArrayList<>();
        long pendingTriples = 0;
        try {
            dao.init();
            while (failure.get() == null) {
                final long block = cursor.getAndIncrement();
                if (block >= blocks) {
                    break;
                }
                if (progress.isCommitted(block)) {
                    continue;
                }
                final long from = first + block * blockSize;
                final long end = Math.min(from + blockSize, last);
                final List<RyaStatement> statements = new ArrayList<>((int) (end - from) * 9);
                for (long e = from; e < end; e++) {
                    statements.addAll(generator.generate(e));
                }
                // A block holds whole entities, so its subject counters are final totals.
                dao.add(statements.iterator(), o.statsOnes());
                pending.add(block);
                pendingTriples += statements.size();
                if (buffer.bytes() >= memory) {
                    commit(worker, spill++, buffer, dao, progress, pending, pendingTriples);
                    pending.clear();
                    pendingTriples = 0;
                }
            }
            if (failure.get() == null) {
                commit(worker, spill, buffer, dao, progress, pending, pendingTriples);
            }
        } finally {
            dao.destroy();
        }
    }

    /**
     * Spills the buffer, then records its blocks and runs in one write; merges a table's runs past
     * {@link #FAN_IN}. The worker's list of runs changes only with that write, so no other worker
     * records a run whose blocks aren't recorded.
     */
    private void commit(final int worker, final int spill, final SpillBuffer buffer, final RyaStoreDAO dao, final Progress progress,
            final List<Long> blocks, final long triples) throws IOException {
        if (blocks.isEmpty() && buffer.isEmpty()) {
            return;
        }
        // The graph markers went to the store: flushed before the blocks count as written.
        dao.flush();
        final String name = partTag() + "-t" + worker + "-" + spill + "-" + System.nanoTime();
        final Map<String, String> written = buffer.spill(spills, name);
        final Map<String, List<String>> mine = new LinkedHashMap<>();
        synchronized (commitLock) {
            runs.getOrDefault(worker, Map.of()).forEach((table, files) -> mine.put(table, new ArrayList<>(files)));
        }
        written.forEach((table, file) -> mine.computeIfAbsent(table, t -> new ArrayList<>()).add(file));
        // Past fanIn runs of a table, this worker's runs are merged into one.
        final List<String> replaced = new ArrayList<>();
        for (final Map.Entry<String, List<String>> e : mine.entrySet()) {
            if (e.getValue().size() >= fanIn) {
                final List<String> inputs = List.copyOf(e.getValue());
                final String merged;
                try (Spills.Reader cells = spills.read(inputs, null, null, isCounters(e.getKey()))) {
                    merged = spills.write(e.getKey(), name + "-m", cells);
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
            runs.put(worker, mine);
            progress.commit(blocks, triples, allRuns());
            sortedTriples.addAndGet(triples);
        }
        // Recorded without them: now they can go.
        spills.delete(replaced);
    }

    private List<String> allRuns() {
        final List<String> all = new ArrayList<>();
        for (final Map<String, List<String>> byTable : runs.values()) {
            byTable.values().forEach(all::addAll);
        }
        return all;
    }

    private static String table(final String run) {
        final String[] parts = run.split("/");
        return parts[1];
    }

    /** The counter tables: the statistics, and the text index's posting counts (O6). */
    private boolean isCounters(final String table) {
        return table.equals(o.tablePrefix() + "eval") || table.equals(o.rdfConf().getTableLayoutStrategy().getTextCount());
    }

    // ------------------------------------------------------------------ merge

    private void merge(final BulkImport bulk, final List<String> tables, final String eval, final long importTime) throws Exception {
        final Map<String, List<String>> byTable = new LinkedHashMap<>();
        for (int part = 0; part < o.parts(); part++) {
            final Path file = work.resolve("sort-p" + part + "of" + o.parts() + ".progress");
            if (!Files.exists(file)) {
                throw new IllegalStateException("part " + part + " of " + o.parts() + " hasn't sorted yet (" + file + ")");
            }
            final Properties p = load(file);
            if (!Long.toString(Long.parseLong(p.getProperty("blocks", "0"))).equals(p.getProperty("watermark"))) {
                throw new IllegalStateException("part " + part + " of " + o.parts() + " hasn't finished sorting (" + file + ")");
            }
            final String files = p.getProperty("files", "");
            for (final String f : files.isEmpty() ? new String[0] : files.split("\n")) {
                byTable.computeIfAbsent(table(f), k -> new ArrayList<>()).add(f);
            }
        }
        files().merge(bulk, byTable, tables, this::isCounters, this::leftOut, importTime);
    }

    private BulkFiles files() {
        return new BulkFiles(store, spills, work, o.part(), o.parts(), o.threads(), out);
    }

    /**
     * Whether a counter's total stays out of {@code eval}: 0 always; 1 in the six counter families
     * but {@code p} (graph sizes sum it), with {@code --stats-ones skip}. The datatype markers
     * (family {@code t}) always stay: a missing marker makes a value range skip that datatype.
     */
    boolean leftOut(final byte[] family, final long total) {
        return leftOut(family, total, o.statsOnes());
    }

    /**
     * The rule of {@link #leftOut(byte[], long)} for a counter's final total, shared with the
     * Spark build (tripatha.spark): with {@code SKIP_SUBJECT_ONES}, totals of 1 are left out in
     * the six counter families but {@code p}, which is exact only when the total is the store's.
     */
    public static boolean leftOut(final byte[] family, final long total, final EvalStatsMutations.CounterPolicy ones) {
        if (total == 0) {
            return true;
        }
        if (total != 1 || ones != EvalStatsMutations.CounterPolicy.SKIP_SUBJECT_ONES) {
            return false;
        }
        final String f = new String(family, StandardCharsets.UTF_8);
        return EvalStatsMutations.COUNTER_FAMILIES.contains(f) && !f.equals(RdfCloudTripleStoreConstants.PRED_CF);
    }

    // ------------------------------------------------------------------ import

    private void importFiles(final BulkImport bulk, final List<String> tables) throws Exception {
        files().importFiles(bulk, tables);
        out.println("The work directory " + work.toAbsolutePath() + " can be deleted.");
    }

    private static String duration(final long millis) {
        return BulkFiles.duration(millis);
    }

    // ------------------------------------------------------------------ small files

    private static Properties load(final Path file) throws IOException {
        return BulkFiles.load(file);
    }

    private static void save(final Path file, final Properties p) {
        BulkFiles.save(file, p);
    }
}
