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
package org.tripatha.store.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.api.resolver.triple.TripleRow;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.TableAdmin;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.TableWriter;

/**
 * Keeps the enabled secondary indexes in step with the statement tables, and records whether
 * each one is complete.
 * <p>
 * An index is READY (queries may use it) when it was created together with the store, or after
 * a rebuild. Enabling it on a store that already has data, or changing its definition (for
 * example the indexed predicates), leaves it BUILDING until the store is started with
 * {@link RdfCloudTripleStoreConfiguration#CONF_INDEX_REBUILD}, or a loader rebuild job makes it
 * READY ({@link RdfCloudTripleStoreConfiguration#CONF_INDEX_REBUILD_JOB}, SCALE-STUDY.md O5). An
 * index table that exists while its index is switched off stops the store from starting: writes
 * would no longer reach it and it would silently become incomplete.
 * <p>
 * The states are re-read every {@link RdfCloudTripleStoreConfiguration#CONF_INDEX_STATE_REFRESH}
 * milliseconds, on a daemon thread and only while the store is used: readers start using an index
 * a rebuild job made READY, and a writer whose index another process redefined stops writing.
 * While an index is BUILDING, deletes are recorded in the {@link RebuildLog} before they are made.
 */
public final class IndexManager implements IndexCatalog {
    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);
    private static final byte[] RECORD_ROW = "tripatha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECORD_CF = "index".getBytes(StandardCharsets.UTF_8);
    /** The qualifier of the running rebuild job's record, next to the indexes' states. */
    private static final byte[] JOB_CQ = "job".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];
    /** Re-reads states off the callers' threads, so they aren't part of a query's trace. */
    private static final ExecutorService REFRESHER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "tripatha-index-states");
        t.setDaemon(true);
        return t;
    });

    private final Store store;
    private final StoreRdfConfiguration conf;
    private final TableLayoutStrategy tables;
    private final CellWriter writer;
    private final TableWriter bwNs;
    private final Map<IndexType, SecondaryIndex> active = new EnumMap<>(IndexType.class);
    private final Map<IndexType, TableWriter> writers = new EnumMap<>(IndexType.class);
    private final Map<IndexType, IndexState> states = new ConcurrentHashMap<>();
    /** Indexes another process redefined since this one started: writing them would be wrong. */
    private final Set<IndexType> redefined = ConcurrentHashMap.newKeySet();
    private final Set<String> textPredicates;
    /** This store's rebuild job, when it is one (the loader's), or null. */
    private final String job;
    private final long refreshMillis;
    private final AtomicLong nextRefresh = new AtomicLong(Long.MAX_VALUE);
    private volatile boolean closed;
    /** The text index, when it is enabled. */
    private final TextIndex text;
    /** The posting counts' table, when it exists: writes then keep the counts (O6). */
    private volatile String countsTable;
    /** Whether the counts are complete for the text index's definition, so the planner may read them. */
    private volatile boolean countsComplete;
    /** Count deltas of the current write call, summed per counter. */
    private final Map<TextCounts.CounterCell, long[]> pendingCounts = new java.util.HashMap<>();
    /** Pending counters past this are written before the call ends. */
    private static final int MAX_PENDING_COUNTS = 50_000;

    /**
     * Opens the enabled indexes, creating their tables when needed.
     *
     * @param storeCreated whether the statement tables were created just now (the store is empty)
     */
    public IndexManager(final Store store, final StoreRdfConfiguration conf, final CellWriter writer,
            final boolean storeCreated) throws Exception {
        this.store = store;
        this.conf = conf;
        this.tables = conf.getTableLayoutStrategy();
        this.writer = writer;
        this.bwNs = writer.table(tables.getNs());
        final TextIndex text = new TextIndex(conf.getTextIndexPredicates());
        this.text = conf.isTextIndex() ? text : null;
        this.textPredicates = text.predicates();
        this.job = conf.getIndexRebuildJob();
        this.refreshMillis = conf.getIndexStateRefresh();
        final String running = readJob(store, tables);
        if (job != null && running != null && !running.equals(job)) {
            throw new RyaDAOException("Another rebuild job (" + running + ") is building the indexes of " + tables.getSpo()
                    + ": finish that one first (its work directory knows it)");
        }

        final TableAdmin ops = store.tables();
        final List<SecondaryIndex> toClear = new ArrayList<>();
        for (final SecondaryIndex index : List.of(text, new GeoIndex(), entityIndex(store, conf))) {
            final IndexType type = index.type();
            final String table = type.table(tables);
            final boolean enabled = type.isEnabled(conf);
            final boolean exists = ops.exists(table);
            if (!enabled) {
                if (exists) {
                    throw new RyaDAOException("The " + type.id() + " index table " + table + " exists but "
                            + type.setting() + " is off. Turn the index back on, or delete the table to drop the index.");
                }
                if (type == IndexType.TEXT && ops.exists(tables.getTextCount())) {
                    throw new RyaDAOException("The text index's counts " + tables.getTextCount() + " exist but " + type.setting()
                            + " is off. Turn the index back on, or delete that table too.");
                }
                continue;
            }
            final IndexState stored = readState(store, tables, type);
            final IndexState state;
            if (!exists) {
                ops.create(table, TableKind.INDEX);
                if (type == IndexType.TEXT) {
                    // The counts start with the index: complete when the index is created with the store.
                    ops.create(tables.getTextCount(), TableKind.COUNTERS);
                    if (storeCreated) {
                        writeCountsFlag(index.definition());
                    }
                }
                state = new IndexState(storeCreated ? IndexState.Status.READY : IndexState.Status.BUILDING, index.definition());
            } else if (stored == null) {
                state = new IndexState(IndexState.Status.BUILDING, index.definition());
            } else if (!stored.definition().equals(index.definition())) {
                if (running != null) {
                    // Clearing it would throw away what the job built, and its files would bring back cells of the old rules.
                    throw new RyaDAOException(running.equals(job)
                            ? "The " + type.id() + " index was redefined during rebuild job " + running + " (stored " + stored.definition()
                                    + ", the job's " + index.definition() + "): run the job with the index options it started with"
                            : "Rebuild job " + running + " is building the " + type.id() + " index with other settings ("
                                    + stored.definition() + ", here " + index.definition() + "): use those, or wait for the job");
                }
                if (!conf.isRebuildIndexes() && job == null) {
                    throw new RyaDAOException("The " + type.id() + " index was built with different settings ("
                            + stored.definition() + ", now " + index.definition() + "). Rebuild it with the loader's rebuild "
                            + "command, or start once with " + RdfCloudTripleStoreConfiguration.CONF_INDEX_REBUILD + "=true.");
                }
                state = new IndexState(IndexState.Status.BUILDING, index.definition());
                toClear.add(index);
            } else {
                state = stored;
            }
            active.put(type, index);
            writers.put(type, writer.table(table));
            if (state.equals(stored)) {
                states.put(type, state); // unchanged: no write, so a read-only user can open the store
            } else {
                setState(type, state);
            }
        }
        writer.flush();
        for (final SecondaryIndex index : toClear) {
            log.info("Clearing the {} index: its definition changed", index.type().id());
            ops.deleteRows(index.type().table(tables));
            if (index.type() == IndexType.TEXT) {
                clearCounts();
            }
        }
        final List<SecondaryIndex> building = new ArrayList<>();
        for (final SecondaryIndex index : active.values()) {
            if (states.get(index.type()).status() == IndexState.Status.BUILDING) {
                building.add(index);
            }
        }
        if (job != null && !building.isEmpty() && running == null) {
            if (active.containsKey(IndexType.TEXT) && states.get(IndexType.TEXT).status() == IndexState.Status.BUILDING) {
                // The job counts every statement it reads: start from none.
                clearCounts();
            }
            bwNs.write(new RowMutation(RECORD_ROW).put(RECORD_CF, JOB_CQ, EMPTY, job.getBytes(StandardCharsets.UTF_8)));
            writer.flush();
        }
        if (active.containsKey(IndexType.TEXT)) {
            countsTable = ops.exists(tables.getTextCount()) ? tables.getTextCount() : null;
            countsComplete = countsTable != null && text.definition().equals(readRecord(store, tables).get(TextCounts.FLAG));
        }
        if (!building.isEmpty()) {
            if (job != null) {
                log.info("The {} index(es) are left to rebuild job {}", building.stream().map(i -> i.type().id()).toList(), job);
            } else if (conf.isRebuildIndexes() && running != null) {
                log.warn("Not rebuilding the {} index(es) here: rebuild job {} is building them",
                        building.stream().map(i -> i.type().id()).toList(), running);
            } else if (conf.isRebuildIndexes()) {
                rebuild(building);
            } else {
                for (final SecondaryIndex index : building) {
                    log.warn("The {} index does not cover the existing statements yet, so queries won't use it. "
                            + "Build it with the loader's rebuild command, or start once with {}=true.", index.type().id(),
                            RdfCloudTripleStoreConfiguration.CONF_INDEX_REBUILD);
                }
            }
        }
        if (refreshMillis > 0) {
            nextRefresh.set(System.currentTimeMillis() + refreshMillis);
        }
    }

    // ------------------------------------------------------------------ states seen by other processes

    /**
     * Re-reads the states on the daemon thread when they are due. The caller goes on with the
     * states it has: they change rarely, and a read here would be part of the caller's trace.
     */
    private void refreshIfDue() {
        final long due = nextRefresh.get();
        if (closed || System.currentTimeMillis() < due || !nextRefresh.compareAndSet(due, Long.MAX_VALUE)) {
            return;
        }
        try {
            REFRESHER.execute(() -> {
                try {
                    if (!closed) {
                        reread();
                    }
                } catch (final Throwable t) {
                    log.debug("Could not re-read the index states of {}", tables.getSpo(), t);
                } finally {
                    nextRefresh.set(System.currentTimeMillis() + refreshMillis);
                }
            });
        } catch (final RuntimeException e) {
            nextRefresh.set(System.currentTimeMillis() + refreshMillis);
        }
    }

    /** Reads every index's state again; one that another process redefined stops being written. */
    void reread() throws Exception {
        final Map<String, String> record = readRecord(store, tables);
        if (text != null) {
            if (countsTable == null && store.tables().exists(tables.getTextCount())) {
                countsTable = tables.getTextCount(); // a rebuild job created it
            }
            countsComplete = countsTable != null && text.definition().equals(record.get(TextCounts.FLAG));
        }
        for (final Map.Entry<IndexType, SecondaryIndex> e : active.entrySet()) {
            final String raw = record.get(e.getKey().id());
            if (raw == null) {
                continue; // purge rewrites the records it deleted
            }
            final IndexState stored = IndexState.decode(raw);
            if (stored.definition().equals(e.getValue().definition())) {
                states.put(e.getKey(), stored);
                redefined.remove(e.getKey());
            } else {
                if (redefined.add(e.getKey())) {
                    log.warn("The {} index was redefined by another process ({}): restart with its settings", e.getKey().id(),
                            stored.definition());
                }
                states.put(e.getKey(), new IndexState(IndexState.Status.BUILDING, e.getValue().definition()));
            }
        }
    }

    /** Stops the re-reads: the store may close after this. */
    public void close() {
        closed = true;
    }

    /** Refuses writes once another process redefined an index this store maintains. */
    public void checkCurrent() {
        refreshIfDue();
        if (!redefined.isEmpty()) {
            throw new RyaDAOException("The " + redefined.stream().map(IndexType::id).toList() + " index(es) of " + tables.getSpo()
                    + " were redefined by another process: restart with the store's new index settings");
        }
    }

    /** Whether an enabled index is BUILDING, as this store last read it. */
    public boolean isBuilding() {
        refreshIfDue();
        for (final IndexType type : active.keySet()) {
            if (states.get(type).status() == IndexState.Status.BUILDING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records deleted {@code spo} cells in the {@link RebuildLog} and flushes them, before the
     * caller deletes them: a rebuild that read them before the delete finds them there.
     */
    public void logDeletes(final List<RowMutation> spoDeletes) throws Exception {
        for (final RowMutation m : spoDeletes) {
            bwNs.write(RebuildLog.records(m));
        }
        writer.flush();
    }

    /** Refuses an operation a running rebuild job can't follow (graph drops, purge), read afresh. */
    public void refuseWhileRebuilding(final String what) {
        final String running = readJob(store, tables);
        if (running != null) {
            throw new RyaDAOException("Can't " + what + " while rebuild job " + running + " builds the "
                    + active.keySet().stream().filter(t -> states.get(t).status() == IndexState.Status.BUILDING).map(IndexType::id)
                            .toList() + " index(es) of " + tables.getSpo() + ": the job can't replay it. Retry once the job is done.");
        }
    }

    /** The running rebuild job, or null. */
    public static String readJob(final Store store, final TableLayoutStrategy tables) {
        final String job = readRecord(store, tables).get(new String(JOB_CQ, StandardCharsets.UTF_8));
        return job == null || job.isEmpty() ? null : job;
    }

    /** A record of the index row by its qualifier (the job, the text counts' completeness), or null. */
    public static String readFlag(final Store store, final TableLayoutStrategy tables, final String qualifier) {
        return readRecord(store, tables).get(qualifier);
    }

    /** The record row's cells: each index's state by its id, and the job. */
    private static Map<String, String> readRecord(final Store store, final TableLayoutStrategy tables) {
        final Map<String, String> out = new java.util.HashMap<>();
        if (!store.tables().exists(tables.getNs())) {
            return out;
        }
        final ScanSpec spec = ScanSpec.builder(Auths.NONE).family(RECORD_CF).build();
        try (CellScanner scanner = store.scan(tables.getNs(), KeyRange.row(RECORD_ROW), spec)) {
            while (scanner.hasNext()) {
                final Cell c = scanner.next();
                out.put(new String(c.qualifier(), StandardCharsets.UTF_8), new String(c.value(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /**
     * Ends rebuild job {@code id}: the indexes it built become READY, then its record goes, then
     * the deletes recorded for it. Refused if the job isn't the running one, or an index was
     * redefined since.
     *
     * @param built the indexes the job built
     * @param counted whether the job counted the text index's postings (not where counters can't be imported)
     * @return the indexes made READY
     */
    public List<IndexType> finishRebuild(final String id, final Set<IndexType> built, final boolean counted) throws Exception {
        final String running = readJob(store, tables);
        if (!id.equals(running)) {
            throw new RyaDAOException("Rebuild job " + id + " isn't the running one (" + running + ")");
        }
        reread();
        if (!redefined.isEmpty()) {
            throw new RyaDAOException("The " + redefined.stream().map(IndexType::id).toList()
                    + " index(es) were redefined during rebuild job " + id + ": run it again with the new settings");
        }
        if (counted && text != null && built.contains(IndexType.TEXT)) {
            writeCountsFlag(text.definition());
            countsComplete = true;
        }
        final List<IndexType> ready = new ArrayList<>();
        for (final Map.Entry<IndexType, SecondaryIndex> e : active.entrySet()) {
            if (built.contains(e.getKey()) && states.get(e.getKey()).status() == IndexState.Status.BUILDING) {
                setState(e.getKey(), new IndexState(IndexState.Status.READY, e.getValue().definition()));
                ready.add(e.getKey());
            }
        }
        writer.flush();
        bwNs.write(new RowMutation(RECORD_ROW).delete(RECORD_CF, JOB_CQ, EMPTY));
        writer.flush();
        RebuildLog.clear(store, tables, Auths.ALL_HELD);
        return ready;
    }

    /** The enabled indexes that are BUILDING, with their definitions. */
    public Map<IndexType, SecondaryIndex> building() {
        final Map<IndexType, SecondaryIndex> out = new EnumMap<>(IndexType.class);
        for (final Map.Entry<IndexType, SecondaryIndex> e : active.entrySet()) {
            if (states.get(e.getKey()).status() == IndexState.Status.BUILDING) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * The entity index as configured: with the shard count given, or else the one it was built
     * with, so that a reader needn't know it.
     */
    private static EntityIndex entityIndex(final Store store, final StoreRdfConfiguration conf) throws Exception {
        if (conf.get(RdfCloudTripleStoreConfiguration.CONF_INDEX_ENTITY_SHARDS) == null && conf.isEntityIndex()
                && store.tables().exists(IndexType.ENTITY.table(conf.getTableLayoutStrategy()))) {
            final IndexState stored = readState(store, conf.getTableLayoutStrategy(), IndexType.ENTITY);
            final int shards = stored == null ? -1 : EntityIndex.shardsOf(stored.definition());
            if (shards > 0) {
                return new EntityIndex(shards);
            }
        }
        return new EntityIndex(conf.getEntityIndexShards());
    }

    /** Refuses, before anything is written, a statement an enabled index can't take. */
    public void check(final RyaStatement statement) {
        checkCurrent();
        if (active.containsKey(IndexType.ENTITY)) {
            EntityIndex.checkIndexable(statement);
        }
    }

    /** Whether an index is enabled (maintained), ready or not. */
    public boolean isActive(final IndexType type) {
        return active.containsKey(type);
    }

    @Override
    public EntityIndex entityIndex() {
        return active.get(IndexType.ENTITY) instanceof EntityIndex entity ? entity : null;
    }

    /** Index entries for a statement being added, and its words' counts (written by {@link #flushCounts}). */
    public void add(final RyaStatement statement) throws Exception {
        for (final SecondaryIndex index : active.values()) {
            final List<RowMutation> puts = index.puts(statement);
            if (!puts.isEmpty()) {
                writers.get(index.type()).write(puts);
            }
        }
        count(statement, 1);
    }

    /** Removes the index entries of a stored statement (as read back from spo), and counts its words down. */
    public void delete(final RyaStatement stored) throws Exception {
        for (final SecondaryIndex index : active.values()) {
            final List<RowMutation> deletes = index.deletes(stored);
            if (!deletes.isEmpty()) {
                writers.get(index.type()).write(deletes);
            }
        }
        count(stored, -1);
    }

    private void count(final RyaStatement statement, final long delta) throws Exception {
        if (text != null && countsTable != null) {
            TextCounts.add(pendingCounts, text, statement, delta);
            if (pendingCounts.size() >= MAX_PENDING_COUNTS) {
                flushCounts();
            }
        }
    }

    /** Hands the counts of the current write call to the writer: one cell per word, predicate and label. */
    public void flushCounts() throws Exception {
        if (pendingCounts.isEmpty()) {
            return;
        }
        final TableWriter counts = writer.table(countsTable);
        for (final Map.Entry<TextCounts.CounterCell, long[]> e : pendingCounts.entrySet()) {
            if (e.getValue()[0] != 0) {
                counts.write(e.getKey().mutation(e.getValue()[0]));
            }
        }
        pendingCounts.clear();
    }

    /** Empties the counts and drops their completeness record: a rebuild counts again. */
    private void clearCounts() throws Exception {
        final TableAdmin ops = store.tables();
        if (ops.exists(tables.getTextCount())) {
            ops.deleteRows(tables.getTextCount());
        } else {
            ops.create(tables.getTextCount(), TableKind.COUNTERS);
        }
        bwNs.write(new RowMutation(RECORD_ROW).delete(RECORD_CF, TextCounts.FLAG.getBytes(StandardCharsets.UTF_8), EMPTY));
        writer.flush();
        countsTable = tables.getTextCount();
        countsComplete = false;
    }

    private void writeCountsFlag(final String definition) throws Exception {
        bwNs.write(new RowMutation(RECORD_ROW).put(RECORD_CF, TextCounts.FLAG.getBytes(StandardCharsets.UTF_8), EMPTY,
                definition.getBytes(StandardCharsets.UTF_8)));
    }

    /** The table of the text index's posting counts, when they are complete; null otherwise. */
    @Override
    public String textCounts() {
        refreshIfDue();
        return countsComplete ? countsTable : null;
    }

    /** The tables of the enabled indexes. */
    public List<String> activeTables() {
        final List<String> names = new ArrayList<>();
        for (final IndexType type : active.keySet()) {
            names.add(type.table(tables));
        }
        return names;
    }

    /** Rewrites the state records, after {@code purge} deleted them with everything else (the counts with them). */
    public void rewriteRecords() throws Exception {
        for (final Map.Entry<IndexType, IndexState> e : states.entrySet()) {
            writeRecord(e.getKey(), e.getValue());
        }
        if (countsComplete && text != null) {
            writeCountsFlag(text.definition());
        }
        writer.flush();
    }

    /**
     * Rebuilds every enabled index from the statement tables, reading them with every label the
     * account holds (statements under labels it lacks are not indexed).
     */
    public void rebuildAll() throws Exception {
        rebuild(new ArrayList<>(active.values()));
    }

    private void rebuild(final List<SecondaryIndex> indexes) throws Exception {
        final List<String> ids = indexes.stream().map(i -> i.type().id()).toList();
        log.info("Building the {} index(es) from {} with every label of {}", ids, tables.getSpo(), store.access().whoami());
        for (final SecondaryIndex index : indexes) {
            setState(index.type(), new IndexState(IndexState.Status.BUILDING, index.definition()));
        }
        writer.flush();
        final boolean counting = text != null && indexes.stream().anyMatch(i -> i.type() == IndexType.TEXT);
        if (counting) {
            clearCounts();
        }
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);
        long count = 0;
        try (CellScanner scanner = store.batchScan(tables.getSpo(), List.of(KeyRange.all()), ScanSpec.of(Auths.ALL_HELD))) {
            while (scanner.hasNext()) {
                final Cell key = scanner.next();
                final RyaStatement st = context.deserializeTriple(TABLE_LAYOUT.SPO,
                        new TripleRow(key.row(), key.family(), key.qualifier(), key.timestamp(), key.visibility(), key.value()));
                for (final SecondaryIndex index : indexes) {
                    final List<RowMutation> puts = index.puts(st);
                    if (!puts.isEmpty()) {
                        writers.get(index.type()).write(puts);
                    }
                }
                if (counting) {
                    count(st, 1);
                }
                count++;
            }
        }
        flushCounts();
        writer.flush();
        if (counting) {
            writeCountsFlag(text.definition());
            countsComplete = true;
        }
        for (final SecondaryIndex index : indexes) {
            setState(index.type(), new IndexState(IndexState.Status.READY, index.definition()));
        }
        writer.flush();
        // Deletes recorded while they were BUILDING: what they cover is in the index as built now.
        RebuildLog.clear(store, tables, Auths.ALL_HELD);
        log.info("Built the {} index(es) over {} statements", ids, count);
    }

    private void setState(final IndexType type, final IndexState state) throws Exception {
        states.put(type, state);
        writeRecord(type, state);
    }

    private void writeRecord(final IndexType type, final IndexState state) throws Exception {
        bwNs.write(new RowMutation(RECORD_ROW).put(RECORD_CF, type.id().getBytes(StandardCharsets.UTF_8), EMPTY,
                state.encode().getBytes(StandardCharsets.UTF_8)));
    }

    /** The stored state of an index, or null if none is recorded. */
    public static IndexState readState(final Store store, final TableLayoutStrategy tables, final IndexType type)
            throws Exception {
        final ScanSpec spec = ScanSpec.builder(Auths.NONE).column(RECORD_CF, type.id().getBytes(StandardCharsets.UTF_8)).build();
        try (CellScanner scanner = store.scan(tables.getNs(), KeyRange.row(RECORD_ROW), spec)) {
            return scanner.hasNext() ? IndexState.decode(new String(scanner.next().value(), StandardCharsets.UTF_8)) : null;
        }
    }

    /** The stored state of an enabled index; null when the index is off. */
    public IndexState state(final IndexType type) {
        return states.get(type);
    }

    @Override
    public boolean isReady(final IndexType type) {
        refreshIfDue();
        final IndexState state = states.get(type);
        return state != null && state.status() == IndexState.Status.READY;
    }

    @Override
    public Set<String> textPredicates() {
        return textPredicates;
    }

    @Override
    public String table(final IndexType type) {
        return type.table(tables);
    }
}
