package org.tripatha.store;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static com.google.common.base.Preconditions.checkNotNull;
import static org.tripatha.api.RdfCloudTripleStoreConstants.INFO_NAMESPACE;
import static org.tripatha.api.RdfCloudTripleStoreConstants.INFO_VERSION;
import static org.tripatha.api.RdfCloudTripleStoreConstants.NUM_THREADS;
import static org.tripatha.api.RdfCloudTripleStoreConstants.VERSION;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.api.persist.RyaDAO;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.api.persist.RyaNamespaceManager;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.api.RdfCloudTripleStoreConstants;
import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.index.IndexCatalog;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.IndexManager;
import org.tripatha.store.query.RyaStoreQueryEngine;
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
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceText;

/**
 * Stores Tripatha statements in a {@link Store} (Accumulo, HBase, ...).
 *
 * <p>Tables, all named {@code <prefix><suffix>}:
 * <ul>
 *   <li>{@code spo}, {@code po}, {@code osp}: every statement, once per table, so that any
 *       statement pattern is a range scan on one of them;</li>
 *   <li>{@code ns}: namespace prefixes, plus the storage version and whether the store's
 *       datatype markers are complete;</li>
 *   <li>{@code eval}: cardinality counters used for join ordering (see {@link EvalStatsMutations}),
 *       and the datatype markers (see {@link DatatypeMarkers});</li>
 *   <li>{@code graphs}: one marker per named graph that has received statements;</li>
 *   <li>{@code text}, {@code geo}: the full-text and geospatial indexes, when enabled
 *       (see {@link IndexManager}).</li>
 * </ul>
 *
 * <p>The DAO does not own the {@link Store}: whoever supplied it closes it.
 *
 * <p>A DAO writes only to a store whose storage version is at most its own ({@code VERSION}):
 * a newer store may rely on writes this code doesn't make, such as datatype markers. It can
 * still read one, and every write method throws.
 */
public class RyaStoreDAO implements RyaDAO<StoreRdfConfiguration>, RyaNamespaceManager<StoreRdfConfiguration>, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RyaStoreDAO.class);

    /** Row of the ns table that holds the storage version (column family {@code version}). */
    private static final byte[] VERSION_ROW = utf8("tripatha");
    private static final byte[] VERSION_CF = utf8(INFO_VERSION);
    private static final byte[] NAMESPACE_CF = utf8(INFO_NAMESPACE);
    private static final byte[] DATATYPES_CF = utf8(DatatypeMarkers.FLAG_CF);
    private static final byte[] EMPTY = new byte[0];

    /** Counters summed in memory before they are written by one {@link #add(Iterator)} call. */
    private static final int MAX_PENDING_COUNTERS = 50_000;

    private final AtomicBoolean isInitialized = new AtomicBoolean();

    private Store store;
    private StoreRdfConfiguration conf = new StoreRdfConfiguration();
    private WriterOptions writerOptions;
    private CellWriter writer;
    private TableWriter bwSpo;
    private TableWriter bwPo;
    private TableWriter bwOsp;
    private TableWriter bwNs;
    private TableWriter bwEval;
    private TableWriter bwGraphs;
    private boolean flushEachUpdate;
    private boolean maintainStats;
    private RyaTableMutationsFactory mutationsFactory;
    private TableLayoutStrategy tables;
    private RyaStoreQueryEngine queryEngine;
    private IndexManager indexes;
    /** Whether the store's datatype markers are complete: it was created by code that writes them. */
    private volatile boolean datatypeMarkers;
    /** Why writes are refused (the store is newer than this code), or null. */
    private volatile String writesRefused;
    /** The last subject boundaries read, with how many were asked for and when. */
    private volatile Boundaries boundaries;

    private record Boundaries(int max, long nanos, List<byte[]> subjects) {
    }

    /** How long subject boundaries are kept: split points change rarely, and stale ones only balance worse. */
    private static final long BOUNDARIES_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);

    public RyaStoreDAO() {
    }

    public RyaStoreDAO(final Store store, final StoreRdfConfiguration conf) {
        this.store = store;
        this.conf = conf;
    }

    @Override
    public boolean isInitialized() {
        return isInitialized.get();
    }

    @Override
    public synchronized void init() {
        if (isInitialized.get()) {
            return;
        }
        checkNotNull(conf, "conf");
        checkNotNull(store, "store");
        try {
            if (writerOptions == null) {
                writerOptions = WriterOptions.DEFAULT;
            }
            tables = conf.getTableLayoutStrategy();
            mutationsFactory = new RyaTableMutationsFactory(RyaTripleContext.getInstance(conf));
            flushEachUpdate = conf.flushEachUpdate();
            maintainStats = conf.isMaintainStats();
            final TableAdmin ops = store.tables();
            final boolean storeCreated = ops.create(tables.getSpo(), TableKind.STATEMENTS);
            ops.create(tables.getPo(), TableKind.STATEMENTS);
            ops.create(tables.getOsp(), TableKind.STATEMENTS);
            ops.create(tables.getNs(), TableKind.METADATA);
            ops.create(tables.getGraphs(), TableKind.METADATA);
            ops.create(tables.getEval(), TableKind.COUNTERS);
            ops.ensureSettings(tables.getEval(), TableKind.COUNTERS);

            writer = store.newWriter(writerOptions);
            bwSpo = writer.table(tables.getSpo());
            bwPo = writer.table(tables.getPo());
            bwOsp = writer.table(tables.getOsp());
            bwNs = writer.table(tables.getNs());
            bwEval = writer.table(tables.getEval());
            bwGraphs = writer.table(tables.getGraphs());

            queryEngine = new RyaStoreQueryEngine(store, conf);
            final String version = getVersion();
            writesRefused = isNewer(version, VERSION.stringValue()) ? "This store has storage version " + version
                    + ", newer than " + VERSION.stringValue() + ", the newest this code writes: it may rely on writes"
                    + " this code doesn't make, so it is read-only here. Use a newer Tripatha to write to it." : null;
            if (version == null && storeCreated) {
                // Every statement of a store created now gets its datatype markers, and its
                // entity postings when that index is on: older code writes neither.
                bwNs.write(new RowMutation(VERSION_ROW).put(DATATYPES_CF, EMPTY, EMPTY, utf8("1")));
                bwNs.write(new RowMutation(VERSION_ROW).put(VERSION_CF, EMPTY, EMPTY, utf8(conf.isEntityIndex()
                        ? RdfCloudTripleStoreConstants.VERSION_WITH_ENTITY_INDEX : RdfCloudTripleStoreConstants.VERSION_WITH_MARKERS)));
                writer.flush();
            }
            datatypeMarkers = readDatatypesFlag();
            if (!datatypeMarkers) {
                logger.info("The datatype markers of the store {} are incomplete (it was created before them):"
                        + " comparison filters read their predicates' whole ranges", tables.getSpo());
            }
            indexes = new IndexManager(store, conf, writer, storeCreated);
            raiseVersionForEntityIndex();
            isInitialized.set(true);
        } catch (final Exception e) {
            if (writer != null) {
                // destroy() skips a DAO that never finished init, so close the writers here.
                try {
                    writer.close();
                } catch (final Exception closing) {
                    e.addSuppressed(closing);
                }
                writer = null;
            }
            throw e instanceof RyaDAOException r ? r : new RyaDAOException(e);
        }
    }

    /**
     * With the entity index on, raises the store's version to 1.2.0: code from before the index
     * would add statements without their postings, and the index's matches are answers.
     */
    private void raiseVersionForEntityIndex() throws Exception {
        if (!indexes.isActive(IndexType.ENTITY) || writesRefused != null) {
            return;
        }
        final String version = getVersion();
        if (version == null || isNewer(RdfCloudTripleStoreConstants.VERSION_WITH_ENTITY_INDEX, version)) {
            bwNs.write(new RowMutation(VERSION_ROW).put(VERSION_CF, EMPTY, EMPTY,
                    utf8(RdfCloudTripleStoreConstants.VERSION_WITH_ENTITY_INDEX)));
            writer.flush();
        }
    }

    private boolean readDatatypesFlag() {
        final ScanSpec spec = ScanSpec.builder(Auths.NONE).column(DATATYPES_CF, EMPTY).build();
        try (CellScanner scanner = store.scan(tables.getNs(), KeyRange.row(VERSION_ROW), spec)) {
            return scanner.hasNext();
        }
    }

    /**
     * Whether {@code stored} is a newer storage version than {@code own}, comparing the numbers
     * between dots. A version that isn't such numbers is taken as newer.
     */
    static boolean isNewer(final String stored, final String own) {
        if (stored == null) {
            return false;
        }
        final String[] a = stored.trim().split("\\.");
        final String[] b = own.split("\\.");
        try {
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                final long x = i < a.length ? Long.parseLong(a[i]) : 0;
                final long y = i < b.length ? Long.parseLong(b[i]) : 0;
                if (x != y) {
                    return x > y;
                }
            }
            return false;
        } catch (final NumberFormatException e) {
            return true;
        }
    }

    private void checkWritable() {
        final String refused = writesRefused;
        if (refused != null) {
            throw new RyaDAOException(refused);
        }
    }

    /**
     * Subjects that divide the store's subjects into parts of similar size, sorted by their UTF-8
     * bytes: {@code spo}'s split points cut to their subjects (SCALE-STUDY.md O24). Merge joins read
     * their lists in the slices between them. Kept a minute.
     *
     * @param slices how many slices are wanted; one fewer boundaries are returned, at most
     * @return the boundaries, or none (one slice) when the backend reports no split points
     */
    public List<byte[]> subjectBoundaries(final int slices) {
        if (slices <= 1) {
            return List.of();
        }
        final Boundaries cached = boundaries;
        if (cached != null && cached.max() == slices && System.nanoTime() - cached.nanos() < BOUNDARIES_NANOS) {
            return cached.subjects();
        }
        final java.util.TreeSet<byte[]> subjects = new java.util.TreeSet<>(Arrays::compareUnsigned);
        for (final byte[] row : store.tables().splitRows(tables.getSpo(), slices - 1)) {
            int end = 0;
            while (end < row.length && row[end] != 0) {
                end++;
            }
            if (end > 0) {
                subjects.add(Arrays.copyOf(row, end));
            }
        }
        final List<byte[]> found = List.copyOf(subjects);
        boundaries = new Boundaries(slices, System.nanoTime(), found);
        return found;
    }

    /**
     * Whether the store's datatype markers are complete, so that {@link #objectDatatypes} can be
     * trusted: the store was created by code that writes them.
     */
    public boolean hasDatatypeMarkers() {
        return datatypeMarkers;
    }

    /**
     * The datatypes of the objects of {@code predicate}, among the statements the configuration's
     * labels can see (SCALE-STUDY.md O14).
     *
     * @return the datatype IRIs, read from the markers; empty when they are unknown, in a store
     *         created before the markers
     */
    public Optional<Set<String>> objectDatatypes(final RyaIRI predicate, final StoreRdfConfiguration aconf) {
        if (!datatypeMarkers) {
            return Optional.empty();
        }
        final Auths auths = (aconf != null ? aconf : conf).getStoreAuths();
        return Optional.of(DatatypeMarkers.read(store, tables.getEval(), predicate.getData(), auths));
    }

    @Override
    public String getVersion() {
        final ScanSpec spec = ScanSpec.builder(Auths.NONE).column(VERSION_CF, EMPTY).build();
        try (CellScanner scanner = store.scan(tables.getNs(), KeyRange.row(VERSION_ROW), spec)) {
            return scanner.hasNext() ? new String(scanner.next().value(), StandardCharsets.UTF_8) : null;
        }
    }

    @Override
    public void add(final RyaStatement statement) {
        add(Collections.singletonList(statement).iterator());
    }

    /**
     * Adds statements. Within one call the statistics counters are summed in memory and written
     * as one cell per counter, and each graph marker is written once, so passing many statements
     * per call is much cheaper than one call per statement.
     */
    @Override
    public synchronized void add(final Iterator<RyaStatement> statements) {
        add(statements, EvalStatsMutations.CounterPolicy.ALL);
    }

    /**
     * Adds statements like {@link #add(Iterator)}, leaving out the counters {@code policy} skips.
     *
     * <p>With {@link EvalStatsMutations.CounterPolicy#ALL} the counters are written early once
     * 50,000 are pending, so memory stays bounded. With any other policy
     * the counters of the whole call are summed before any is written, so the policy sees each
     * counter's total for the call: pass bounded batches that hold every statement of their
     * subjects (the loader passes whole blocks of entities).
     */
    public synchronized void add(final Iterator<RyaStatement> statements, final EvalStatsMutations.CounterPolicy policy) {
        checkWritable();
        final boolean writeEarly = policy == EvalStatsMutations.CounterPolicy.ALL;
        final EvalStatsMutations.CounterBatch counts = maintainStats ? new EvalStatsMutations.CounterBatch() : null;
        final Set<GraphMarkerKey> markers = new HashSet<>();
        final Set<DatatypeMarkers.Key> datatypes = new HashSet<>();
        Exception failure = null;
        try {
            while (statements.hasNext()) {
                final RyaStatement stmt = statements.next();
                checkStorable(stmt);
                indexes.check(stmt);
                final Map<TABLE_LAYOUT, RowMutation> rows = mutationsFactory.serialize(stmt);
                // Buffered before the rows it stands for. Not a guarantee that it lands first: a
                // failed flush can leave rows without it, until they are added again.
                final DatatypeMarkers.Key datatype = DatatypeMarkers.Key.of(stmt);
                if (datatypes.add(datatype)) {
                    bwEval.write(datatype.mutation());
                }
                bwSpo.write(rows.get(TABLE_LAYOUT.SPO));
                bwPo.write(rows.get(TABLE_LAYOUT.PO));
                bwOsp.write(rows.get(TABLE_LAYOUT.OSP));
                if (stmt.getContext() != null && markers.add(GraphMarkerKey.of(stmt))) {
                    bwGraphs.write(EvalStatsMutations.graphMarker(stmt));
                }
                if (counts != null) {
                    counts.add(stmt, 1);
                    if (writeEarly && counts.size() >= MAX_PENDING_COUNTERS) {
                        bwEval.write(counts.drain());
                    }
                }
                indexes.add(stmt);
            }
        } catch (final Exception e) {
            failure = e;
        }
        // Count what was handed to the writers, even if a later statement failed.
        if (counts != null && counts.size() > 0) {
            try {
                bwEval.write(counts.drain(policy));
            } catch (final Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure == null && flushEachUpdate) {
            try {
                writer.flush();
            } catch (final Exception e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure instanceof RyaDAOException r ? r : new RyaDAOException(failure);
        }
    }

    /**
     * The graph IRI and the qualifier become column names; the byte 00 separates column names on
     * HBase, so neither may contain it, whatever the backend (statements stay portable).
     */
    private static void checkStorable(final RyaStatement stmt) {
        if (stmt.getContext() != null && stmt.getContext().getData().indexOf('\u0000') >= 0) {
            throw new RyaDAOException("A graph IRI may not contain the character U+0000: " + stmt.getContext().getData().replace('\u0000', '?'));
        }
        if (stmt.getQualifer() != null && stmt.getQualifer().indexOf('\u0000') >= 0) {
            throw new RyaDAOException("A statement qualifier may not contain the character U+0000");
        }
    }

    /** A graph marker cell: written once per call for each graph and visibility. */
    private record GraphMarkerKey(String graph, String visibility) {
        static GraphMarkerKey of(final RyaStatement stmt) {
            final byte[] cv = stmt.getColumnVisibility();
            return new GraphMarkerKey(stmt.getContext().getData(), cv == null ? "" : new String(cv, StandardCharsets.UTF_8));
        }
    }

    @Override
    public void delete(final RyaStatement stmt, final StoreRdfConfiguration aconf) {
        delete(Collections.singletonList(stmt).iterator(), aconf);
    }

    /**
     * Deletes every stored statement matching each pattern and visible with the conf's auths.
     */
    @Override
    public synchronized void delete(final Iterator<RyaStatement> patterns, final StoreRdfConfiguration aconf) {
        checkWritable();
        while (patterns.hasNext()) {
            deleteMatches(patterns.next(), st -> true, aconf);
        }
        flushIfNeeded();
    }

    @Override
    public synchronized void delete(final RyaStatement pattern, final Predicate<RyaStatement> filter,
            final StoreRdfConfiguration aconf) {
        checkWritable();
        deleteMatches(pattern, filter, aconf);
        flushIfNeeded();
    }

    private void deleteMatches(final RyaStatement pattern, final Predicate<RyaStatement> filter,
            final StoreRdfConfiguration aconf) {
        try {
            // Find the exact stored cells (visibility and timestamp included) before deleting.
            final List<RyaStatement> matches = new ArrayList<>();
            final StoreTrace.Reason why = StoreTrace.reason(() -> "delete: find the stored matches of "
                    + TraceText.pattern(pattern));
            try (why; CloseableIteration<RyaStatement> found = queryEngine.query(pattern, aconf)) {
                while (found.hasNext()) {
                    final RyaStatement st = found.next();
                    if (filter.test(st)) {
                        matches.add(st);
                    }
                }
            }
            for (final RyaStatement match : matches) {
                final Map<TABLE_LAYOUT, RowMutation> rows = mutationsFactory.serializeDelete(match);
                bwSpo.write(rows.get(TABLE_LAYOUT.SPO));
                bwPo.write(rows.get(TABLE_LAYOUT.PO));
                bwOsp.write(rows.get(TABLE_LAYOUT.OSP));
                if (maintainStats) {
                    bwEval.write(EvalStatsMutations.counters(match, false));
                }
                indexes.delete(match);
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    private void flushIfNeeded() {
        if (flushEachUpdate) {
            flush();
        }
    }

    @Override
    public synchronized void dropGraph(final StoreRdfConfiguration aconf, final RyaIRI... graphs) {
        checkWritable();
        final Auths auths = (aconf != null ? aconf : conf).getStoreAuths();
        try {
            writer.flush();
            final boolean all = graphs == null || graphs.length == 0;
            final StoreTrace.Reason why = StoreTrace.reason(() -> all ? "drop every graph" : "drop graph"
                    + (graphs.length == 1 ? " " : "s ") + Arrays.stream(graphs)
                            .map(g -> g == null ? "(default)" : "<" + g.getData() + ">").collect(Collectors.joining(", ")));
            try (why) {
                // The text and geo indexes use the graph as column family too, so the same deletes
                // clear them. The entity index files postings under their term: they are found by
                // the graph in their qualifier instead.
                final List<String> statementTables = new ArrayList<>(List.of(tables.getSpo(), tables.getPo(), tables.getOsp()));
                statementTables.addAll(indexes.activeTables());
                if (!all && indexes.isActive(IndexType.ENTITY)) {
                    statementTables.remove(tables.getEntity());
                    for (final RyaIRI graph : graphs) {
                        final ScanSpec postings = ScanSpec.builder(auths).threads(NUM_THREADS)
                                .qualifierRegex(EntityIndex.graphQualifierRegex(graph)).build();
                        store.deleteVisible(tables.getEntity(), List.of(KeyRange.all()), postings, writerOptions);
                    }
                }
                final ScanSpec.Builder statements = ScanSpec.builder(auths).threads(NUM_THREADS);
                if (!all) {
                    for (final RyaIRI graph : graphs) {
                        statements.family(graph == null ? EMPTY : utf8(graph.getData()));
                    }
                }
                final ScanSpec statementSpec = statements.build();
                for (final String table : statementTables) {
                    store.deleteVisible(table, List.of(KeyRange.all()), statementSpec, writerOptions);
                }
                final List<KeyRange> markerRows = new ArrayList<>();
                if (all) {
                    markerRows.add(KeyRange.all());
                } else {
                    for (final RyaIRI graph : graphs) {
                        if (graph != null) {
                            markerRows.add(KeyRange.row(utf8(graph.getData())));
                        }
                    }
                }
                final ScanSpec plain = ScanSpec.builder(auths).threads(NUM_THREADS).build();
                if (!markerRows.isEmpty()) {
                    store.deleteVisible(tables.getGraphs(), markerRows, plain, writerOptions);
                }
                if (all) {
                    // Everything visible is gone, so are the counters for it. Not the datatype
                    // markers: a writer adding statements meanwhile may have written its own already.
                    final ScanSpec.Builder counters = ScanSpec.builder(auths).threads(NUM_THREADS);
                    for (final String family : EvalStatsMutations.COUNTER_FAMILIES) {
                        counters.family(utf8(family));
                    }
                    store.deleteVisible(tables.getEval(), List.of(KeyRange.all()), counters.build(), writerOptions);
                }
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public CloseableIteration<RyaIRI> getContextIDs(final StoreRdfConfiguration aconf) {
        final Auths auths = (aconf != null ? aconf : conf).getStoreAuths();
        try {
            flush();
            final StoreTrace.Reason why = StoreTrace.reason(() -> "list the named graphs");
            try (why) {
                // Graph IRI -> marker cells seen for it (one per visibility label).
                final Map<String, List<Cell>> markers = new LinkedHashMap<>();
                final ScanSpec spec = ScanSpec.builder(auths).family(EvalStatsMutations.GRAPH_CF_BYTES).build();
                try (CellScanner scanner = store.scan(tables.getGraphs(), KeyRange.all(), spec)) {
                    while (scanner.hasNext()) {
                        final Cell c = scanner.next();
                        markers.computeIfAbsent(new String(c.row(), StandardCharsets.UTF_8), k -> new ArrayList<>()).add(c);
                    }
                }
                final List<RyaIRI> graphs = new ArrayList<>();
                boolean removedStale = false;
                for (final Map.Entry<String, List<Cell>> graph : markers.entrySet()) {
                    if (graphHasData(graph.getKey(), auths)) {
                        graphs.add(new RyaIRI(graph.getKey()));
                    } else if (writesRefused == null) {
                        // Nothing visible is left in this graph: forget the markers we can see.
                        for (final Cell key : graph.getValue()) {
                            bwGraphs.write(new RowMutation(key.row()).delete(key.family(), key.qualifier(), key.visibility(),
                                    key.timestamp()));
                        }
                        removedStale = true;
                    }
                }
                if (removedStale && writesRefused == null) {
                    writer.flush();
                }
                return new CloseableIteratorIteration<>(graphs.iterator());
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    private boolean graphHasData(final String graph, final Auths auths) {
        final ScanSpec spec = ScanSpec.builder(auths).family(utf8(graph)).batchSize(1).build();
        try (CellScanner scanner = store.scan(tables.getSpo(), KeyRange.all(), spec)) {
            return scanner.hasNext();
        }
    }

    @Override
    public long size(final StoreRdfConfiguration aconf, final RyaIRI... contexts) {
        final Auths auths = (aconf != null ? aconf : conf).getStoreAuths();
        try {
            flush();
            final ScanSpec.Builder spec = ScanSpec.builder(auths).threads(NUM_THREADS);
            for (final RyaIRI context : contexts) {
                spec.family(context == null ? EMPTY : utf8(context.getData()));
            }
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "count the statements (a full scan of spo)");
                    CellScanner scanner = store.batchScan(tables.getSpo(), List.of(KeyRange.all()), spec.build())) {
                long count = 0;
                while (scanner.hasNext()) {
                    scanner.next();
                    count++;
                }
                return count;
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public synchronized void addNamespace(final String pfx, final String namespace) {
        checkWritable();
        try {
            bwNs.write(new RowMutation(utf8(pfx)).put(NAMESPACE_CF, EMPTY, EMPTY, utf8(namespace)));
            if (flushEachUpdate) {
                writer.flush();
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public String getNamespace(final String pfx) {
        final ScanSpec spec = ScanSpec.builder(Auths.NONE).column(NAMESPACE_CF, EMPTY).build();
        try (StoreTrace.Reason why = StoreTrace.reason(() -> "namespace of the prefix '" + pfx + "'");
                CellScanner scanner = store.scan(tables.getNs(), KeyRange.row(utf8(pfx)), spec)) {
            return scanner.hasNext() ? new String(scanner.next().value(), StandardCharsets.UTF_8) : null;
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public synchronized void removeNamespace(final String pfx) {
        checkWritable();
        try {
            bwNs.write(new RowMutation(utf8(pfx)).delete(NAMESPACE_CF, EMPTY, EMPTY));
            if (flushEachUpdate) {
                writer.flush();
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public CloseableIteration<Namespace> iterateNamespace() {
        try {
            return new NamespaceIterator(store.scan(tables.getNs(), KeyRange.all(),
                    ScanSpec.builder(Auths.NONE).family(NAMESPACE_CF).build()));
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public RyaNamespaceManager<StoreRdfConfiguration> getNamespaceManager() {
        return this;
    }

    /**
     * Deletes all data visible with the configuration's auths from every Tripatha table, then
     * rewrites the storage version and the datatype-marker flag as they were. Statements under
     * labels the auths don't cover stay, and so do their markers.
     */
    @Override
    public synchronized void purge(final RdfCloudTripleStoreConfiguration configuration) {
        checkWritable();
        final ScanSpec spec = ScanSpec.builder(Auths.of(configuration.getAuths())).threads(NUM_THREADS).build();
        try {
            flush();
            final String version = isInitialized.get() ? getVersion() : null;
            final boolean flagged = isInitialized.get() && readDatatypesFlag();
            for (final String table : getTables()) {
                if (store.tables().exists(table)) {
                    store.deleteVisible(table, List.of(KeyRange.all()), spec,
                            writerOptions == null ? WriterOptions.DEFAULT : writerOptions);
                }
            }
            if (isInitialized.get()) {
                if (flagged) {
                    bwNs.write(new RowMutation(VERSION_ROW).put(DATATYPES_CF, EMPTY, EMPTY, utf8("1")));
                }
                if (version != null) {
                    bwNs.write(new RowMutation(VERSION_ROW).put(VERSION_CF, EMPTY, EMPTY, utf8(version)));
                }
                writer.flush();
                indexes.rewriteRecords();
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public synchronized void dropAndDestroy() {
        destroy();
        for (final String table : getTables()) {
            try {
                store.tables().delete(table);
            } catch (final RyaDAOException e) {
                throw e;
            } catch (final Exception e) {
                throw new RyaDAOException(e);
            }
        }
    }

    @Override
    public synchronized void flush() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public synchronized void destroy() {
        if (!isInitialized.getAndSet(false)) {
            return;
        }
        try {
            writer.close();
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        } finally {
            writer = null;
        }
    }

    /** Same as {@link #destroy()}. */
    @Override
    public void close() {
        destroy();
    }

    /** @return the names of every table this DAO uses (index tables included, enabled or not). */
    public List<String> getTables() {
        final TableLayoutStrategy t = tables != null ? tables : conf.getTableLayoutStrategy();
        return List.of(t.getSpo(), t.getPo(), t.getOsp(), t.getNs(), t.getEval(), t.getGraphs(), t.getText(), t.getGeo(),
                t.getEntity());
    }

    /** The secondary indexes, for queries. */
    public IndexCatalog getIndexCatalog() {
        final IndexManager current = indexes;
        return current == null ? IndexCatalog.NONE : current;
    }

    /** Rebuilds every enabled index from the statement tables. */
    public synchronized void rebuildIndexes() {
        checkWritable();
        try {
            flush();
            indexes.rebuildAll();
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    public Store getStore() {
        return store;
    }

    public void setStore(final Store store) {
        this.store = store;
    }

    public WriterOptions getWriterOptions() {
        return writerOptions;
    }

    /** How writes are buffered; set it before {@link #init()}. */
    public void setWriterOptions(final WriterOptions writerOptions) {
        this.writerOptions = writerOptions;
    }

    @Override
    public synchronized StoreRdfConfiguration getConf() {
        return conf;
    }

    @Override
    public synchronized void setConf(final StoreRdfConfiguration conf) {
        this.conf = conf;
    }

    @Override
    public RyaStoreQueryEngine getQueryEngine() {
        return queryEngine;
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
