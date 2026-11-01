package io.github.dgreco.rya.store;

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
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.INFO_NAMESPACE;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.INFO_VERSION;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.NUM_THREADS;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.VERSION;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.persist.RyaNamespaceManager;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.store.index.IndexCatalog;
import io.github.dgreco.rya.store.index.IndexManager;
import io.github.dgreco.rya.store.query.RyaStoreQueryEngine;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.spi.TableAdmin;
import io.github.dgreco.rya.store.spi.TableKind;
import io.github.dgreco.rya.store.spi.TableWriter;
import io.github.dgreco.rya.store.spi.WriterOptions;
import io.github.dgreco.rya.store.trace.StoreTrace;
import io.github.dgreco.rya.store.trace.TraceText;

/**
 * Stores Rya statements in a {@link Store} (Accumulo, HBase, ...).
 *
 * <p>Tables, all named {@code <prefix><suffix>}:
 * <ul>
 *   <li>{@code spo}, {@code po}, {@code osp}: every statement, once per table, so that any
 *       statement pattern is a range scan on one of them;</li>
 *   <li>{@code ns}: namespace prefixes, plus the storage version;</li>
 *   <li>{@code eval}: cardinality counters used for join ordering (see {@link EvalStatsMutations});</li>
 *   <li>{@code graphs}: one marker per named graph that has received statements;</li>
 *   <li>{@code text}, {@code geo}: the full-text and geospatial indexes, when enabled
 *       (see {@link IndexManager}).</li>
 * </ul>
 *
 * <p>The DAO does not own the {@link Store}: whoever supplied it closes it.
 */
public class RyaStoreDAO implements RyaDAO<StoreRdfConfiguration>, RyaNamespaceManager<StoreRdfConfiguration>, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RyaStoreDAO.class);

    /** Row of the ns table that holds the storage version (column family {@code version}). */
    private static final byte[] VERSION_ROW = utf8("rya");
    private static final byte[] VERSION_CF = utf8(INFO_VERSION);
    private static final byte[] NAMESPACE_CF = utf8(INFO_NAMESPACE);
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
            writeVersionIfMissing();
            indexes = new IndexManager(store, conf, writer, storeCreated);
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

    private void writeVersionIfMissing() throws Exception {
        if (getVersion() == null) {
            bwNs.write(new RowMutation(VERSION_ROW).put(VERSION_CF, EMPTY, EMPTY, utf8(VERSION.stringValue())));
            writer.flush();
        }
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
        final EvalStatsMutations.CounterBatch counts = maintainStats ? new EvalStatsMutations.CounterBatch() : null;
        final Set<GraphMarkerKey> markers = new HashSet<>();
        Exception failure = null;
        try {
            while (statements.hasNext()) {
                final RyaStatement stmt = statements.next();
                checkStorable(stmt);
                final Map<TABLE_LAYOUT, RowMutation> rows = mutationsFactory.serialize(stmt);
                bwSpo.write(rows.get(TABLE_LAYOUT.SPO));
                bwPo.write(rows.get(TABLE_LAYOUT.PO));
                bwOsp.write(rows.get(TABLE_LAYOUT.OSP));
                if (stmt.getContext() != null && markers.add(GraphMarkerKey.of(stmt))) {
                    bwGraphs.write(EvalStatsMutations.graphMarker(stmt));
                }
                if (counts != null) {
                    counts.add(stmt, 1);
                    if (counts.size() >= MAX_PENDING_COUNTERS) {
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
                bwEval.write(counts.drain());
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
        while (patterns.hasNext()) {
            deleteMatches(patterns.next(), st -> true, aconf);
        }
        flushIfNeeded();
    }

    @Override
    public synchronized void delete(final RyaStatement pattern, final Predicate<RyaStatement> filter,
            final StoreRdfConfiguration aconf) {
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
        final Auths auths = (aconf != null ? aconf : conf).getStoreAuths();
        try {
            writer.flush();
            final boolean all = graphs == null || graphs.length == 0;
            final StoreTrace.Reason why = StoreTrace.reason(() -> all ? "drop every graph" : "drop graph"
                    + (graphs.length == 1 ? " " : "s ") + Arrays.stream(graphs)
                            .map(g -> g == null ? "(default)" : "<" + g.getData() + ">").collect(Collectors.joining(", ")));
            try (why) {
                // Index entries use the graph as column family too, so the same deletes clear them.
                final List<String> statementTables = new ArrayList<>(List.of(tables.getSpo(), tables.getPo(), tables.getOsp()));
                statementTables.addAll(indexes.activeTables());
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
                    // Everything visible is gone, so are the counters for it.
                    store.deleteVisible(tables.getEval(), List.of(KeyRange.all()), plain, writerOptions);
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
                    } else {
                        // Nothing visible is left in this graph: forget the markers we can see.
                        for (final Cell key : graph.getValue()) {
                            bwGraphs.write(new RowMutation(key.row()).delete(key.family(), key.qualifier(), key.visibility(),
                                    key.timestamp()));
                        }
                        removedStale = true;
                    }
                }
                if (removedStale) {
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
     * Deletes all data visible with the configuration's auths from every Rya table, then
     * rewrites the storage version.
     */
    @Override
    public synchronized void purge(final RdfCloudTripleStoreConfiguration configuration) {
        final ScanSpec spec = ScanSpec.builder(Auths.of(configuration.getAuths())).threads(NUM_THREADS).build();
        try {
            flush();
            for (final String table : getTables()) {
                if (store.tables().exists(table)) {
                    store.deleteVisible(table, List.of(KeyRange.all()), spec,
                            writerOptions == null ? WriterOptions.DEFAULT : writerOptions);
                }
            }
            if (isInitialized.get()) {
                writeVersionIfMissing();
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
        return List.of(t.getSpo(), t.getPo(), t.getOsp(), t.getNs(), t.getEval(), t.getGraphs(), t.getText(), t.getGeo());
    }

    /** The secondary indexes, for queries. */
    public IndexCatalog getIndexCatalog() {
        final IndexManager current = indexes;
        return current == null ? IndexCatalog.NONE : current;
    }

    /** Rebuilds every enabled index from the statement tables. */
    public synchronized void rebuildIndexes() {
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
