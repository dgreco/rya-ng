package io.github.dgreco.rya.accumulo;

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
import static io.github.dgreco.rya.accumulo.AccumuloRdfConstants.NO_AUTHORIZATIONS;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.EMPTY_TEXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.INFO_NAMESPACE_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.INFO_VERSION_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.MAX_MEMORY;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.MAX_TIME;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.NUM_THREADS;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.VERSION;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import io.github.dgreco.rya.accumulo.query.AccumuloRyaQueryEngine;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.persist.RyaNamespaceManager;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores Rya statements in Accumulo.
 *
 * <p>Tables, all named {@code <prefix><suffix>}:
 * <ul>
 *   <li>{@code spo}, {@code po}, {@code osp}: every statement, once per table, so that any
 *       statement pattern is a range scan on one of them;</li>
 *   <li>{@code ns}: namespace prefixes, plus the storage version;</li>
 *   <li>{@code eval}: cardinality counters used for join ordering (see {@link EvalStatsMutations});</li>
 *   <li>{@code graphs}: one marker per named graph that has received statements.</li>
 * </ul>
 *
 * <p>The DAO does not own the {@link AccumuloClient}: whoever supplied it closes it.
 */
public class AccumuloRyaDAO implements RyaDAO<AccumuloRdfConfiguration>, RyaNamespaceManager<AccumuloRdfConfiguration>, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AccumuloRyaDAO.class);

    /** Row of the ns table that holds the storage version (column family {@code version}). */
    private static final Text VERSION_ROW = new Text("rya");

    private final AtomicBoolean isInitialized = new AtomicBoolean();
    private AccumuloClient client;
    private AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
    private BatchWriterConfig batchWriterConfig;

    private MultiTableBatchWriter mtbw;
    private BatchWriter bwSpo;
    private BatchWriter bwPo;
    private BatchWriter bwOsp;
    private BatchWriter bwNs;
    private BatchWriter bwEval;
    private BatchWriter bwGraphs;

    private boolean flushEachUpdate;
    private boolean maintainStats;
    private RyaTableMutationsFactory mutationsFactory;
    private TableLayoutStrategy tables;
    private AccumuloRyaQueryEngine queryEngine;

    public AccumuloRyaDAO() {
    }

    public AccumuloRyaDAO(final AccumuloClient client, final AccumuloRdfConfiguration conf) {
        this.client = client;
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
        checkNotNull(client, "client");
        try {
            if (batchWriterConfig == null) {
                batchWriterConfig = new BatchWriterConfig()
                        .setMaxMemory(MAX_MEMORY)
                        .setTimeout(MAX_TIME, TimeUnit.MILLISECONDS)
                        .setMaxWriteThreads(NUM_THREADS);
            }
            tables = conf.getTableLayoutStrategy();
            mutationsFactory = new RyaTableMutationsFactory(RyaTripleContext.getInstance(conf));
            flushEachUpdate = conf.flushEachUpdate();
            maintainStats = conf.isMaintainStats();

            final TableOperations ops = client.tableOperations();
            AccumuloRdfUtils.createTableIfNotExist(ops, tables.getSpo());
            AccumuloRdfUtils.createTableIfNotExist(ops, tables.getPo());
            AccumuloRdfUtils.createTableIfNotExist(ops, tables.getOsp());
            AccumuloRdfUtils.createTableIfNotExist(ops, tables.getNs());
            AccumuloRdfUtils.createTableIfNotExist(ops, tables.getGraphs());
            AccumuloRdfUtils.createTableIfNotExist(ops, tables.getEval());
            ensureStatsCombiner(ops, tables.getEval());

            mtbw = client.createMultiTableBatchWriter(batchWriterConfig);
            bwSpo = mtbw.getBatchWriter(tables.getSpo());
            bwPo = mtbw.getBatchWriter(tables.getPo());
            bwOsp = mtbw.getBatchWriter(tables.getOsp());
            bwNs = mtbw.getBatchWriter(tables.getNs());
            bwEval = mtbw.getBatchWriter(tables.getEval());
            bwGraphs = mtbw.getBatchWriter(tables.getGraphs());

            queryEngine = new AccumuloRyaQueryEngine(client, conf);
            writeVersionIfMissing();
            isInitialized.set(true);
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    private static void ensureStatsCombiner(final TableOperations ops, final String table) throws Exception {
        if (!ops.listIterators(table).containsKey(EvalStatsMutations.COMBINER_NAME)) {
            try {
                ops.attachIterator(table, EvalStatsMutations.combinerSetting(), EnumSet.allOf(IteratorScope.class));
            } catch (final IllegalArgumentException e) {
                // Another client attached it between the check and the attach.
                logger.debug("Stats combiner already attached to {}", table);
            }
        }
    }

    private void writeVersionIfMissing() throws Exception {
        if (getVersion() == null) {
            final Mutation m = new Mutation(VERSION_ROW);
            m.put(INFO_VERSION_TXT, EMPTY_TEXT, new Value(VERSION.stringValue().getBytes(StandardCharsets.UTF_8)));
            bwNs.addMutation(m);
            mtbw.flush();
        }
    }

    @Override
    public String getVersion() {
        try (Scanner scanner = client.createScanner(tables.getNs(), NO_AUTHORIZATIONS)) {
            scanner.setRange(new Range(VERSION_ROW));
            scanner.fetchColumn(INFO_VERSION_TXT, EMPTY_TEXT);
            final Iterator<Map.Entry<Key, Value>> it = scanner.iterator();
            return it.hasNext() ? new String(it.next().getValue().get(), StandardCharsets.UTF_8) : null;
        } catch (final TableNotFoundException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public void add(final RyaStatement statement) {
        add(Collections.singletonList(statement).iterator());
    }

    @Override
    public synchronized void add(final Iterator<RyaStatement> statements) {
        try {
            while (statements.hasNext()) {
                final RyaStatement stmt = statements.next();
                final Map<TABLE_LAYOUT, Collection<Mutation>> rows = mutationsFactory.serialize(stmt);
                bwSpo.addMutations(rows.get(TABLE_LAYOUT.SPO));
                bwPo.addMutations(rows.get(TABLE_LAYOUT.PO));
                bwOsp.addMutations(rows.get(TABLE_LAYOUT.OSP));
                final Mutation marker = EvalStatsMutations.graphMarker(stmt);
                if (marker != null) {
                    bwGraphs.addMutation(marker);
                }
                if (maintainStats) {
                    bwEval.addMutations(EvalStatsMutations.counters(stmt, true));
                }
            }
            if (flushEachUpdate) {
                mtbw.flush();
            }
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public void delete(final RyaStatement stmt, final AccumuloRdfConfiguration aconf) {
        delete(Collections.singletonList(stmt).iterator(), aconf);
    }

    /**
     * Deletes every stored statement matching each pattern and visible with the conf's auths.
     */
    @Override
    public synchronized void delete(final Iterator<RyaStatement> patterns, final AccumuloRdfConfiguration aconf) {
        while (patterns.hasNext()) {
            deleteMatches(patterns.next(), st -> true, aconf);
        }
        flushIfNeeded();
    }

    @Override
    public synchronized void delete(final RyaStatement pattern, final Predicate<RyaStatement> filter,
            final AccumuloRdfConfiguration aconf) {
        deleteMatches(pattern, filter, aconf);
        flushIfNeeded();
    }

    private void deleteMatches(final RyaStatement pattern, final Predicate<RyaStatement> filter,
            final AccumuloRdfConfiguration aconf) {
        try {
            // Find the exact stored cells (visibility and timestamp included) before deleting.
            final List<RyaStatement> matches = new ArrayList<>();
            try (CloseableIteration<RyaStatement> found = queryEngine.query(pattern, aconf)) {
                while (found.hasNext()) {
                    final RyaStatement st = found.next();
                    if (filter.test(st)) {
                        matches.add(st);
                    }
                }
            }
            for (final RyaStatement match : matches) {
                final Map<TABLE_LAYOUT, Collection<Mutation>> rows = mutationsFactory.serializeDelete(match);
                bwSpo.addMutations(rows.get(TABLE_LAYOUT.SPO));
                bwPo.addMutations(rows.get(TABLE_LAYOUT.PO));
                bwOsp.addMutations(rows.get(TABLE_LAYOUT.OSP));
                if (maintainStats) {
                    bwEval.addMutations(EvalStatsMutations.counters(match, false));
                }
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
    public synchronized void dropGraph(final AccumuloRdfConfiguration aconf, final RyaIRI... graphs) {
        final Authorizations auths = (aconf != null ? aconf : conf).getAuthorizations();
        try {
            mtbw.flush();
            final boolean all = graphs == null || graphs.length == 0;
            for (final String table : new String[] { tables.getSpo(), tables.getPo(), tables.getOsp() }) {
                try (BatchDeleter deleter = client.createBatchDeleter(table, auths, NUM_THREADS, batchWriterConfig)) {
                    deleter.setRanges(Collections.singleton(new Range()));
                    if (!all) {
                        for (final RyaIRI graph : graphs) {
                            deleter.fetchColumnFamily(graph == null ? EMPTY_TEXT : new Text(graph.getData()));
                        }
                    }
                    deleter.delete();
                }
            }
            final List<Range> markerRows = new ArrayList<>();
            if (all) {
                markerRows.add(new Range());
            } else {
                for (final RyaIRI graph : graphs) {
                    if (graph != null) {
                        markerRows.add(new Range(new Text(graph.getData())));
                    }
                }
            }
            if (!markerRows.isEmpty()) {
                try (BatchDeleter deleter = client.createBatchDeleter(tables.getGraphs(), auths, NUM_THREADS, batchWriterConfig)) {
                    deleter.setRanges(markerRows);
                    deleter.delete();
                }
            }
            if (all) {
                // Everything visible is gone, so are the counters for it.
                try (BatchDeleter deleter = client.createBatchDeleter(tables.getEval(), auths, NUM_THREADS, batchWriterConfig)) {
                    deleter.setRanges(Collections.singleton(new Range()));
                    deleter.delete();
                }
            }
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public CloseableIteration<RyaIRI> getContextIDs(final AccumuloRdfConfiguration aconf) {
        final Authorizations auths = (aconf != null ? aconf : conf).getAuthorizations();
        try {
            flush();
            // Graph IRI -> marker keys seen for it (one per visibility label).
            final Map<String, List<Key>> markers = new LinkedHashMap<>();
            try (Scanner scanner = client.createScanner(tables.getGraphs(), auths)) {
                scanner.fetchColumnFamily(EvalStatsMutations.GRAPH_CF_TXT);
                for (final Map.Entry<Key, Value> e : scanner) {
                    markers.computeIfAbsent(e.getKey().getRow().toString(), k -> new ArrayList<>()).add(e.getKey());
                }
            }
            final List<RyaIRI> graphs = new ArrayList<>();
            boolean removedStale = false;
            for (final Map.Entry<String, List<Key>> graph : markers.entrySet()) {
                if (graphHasData(graph.getKey(), auths)) {
                    graphs.add(new RyaIRI(graph.getKey()));
                } else {
                    // Nothing visible is left in this graph: forget the markers we can see.
                    for (final Key key : graph.getValue()) {
                        final Mutation del = new Mutation(key.getRow());
                        del.putDelete(key.getColumnFamily(), key.getColumnQualifier(),
                                new ColumnVisibility(key.getColumnVisibility()), key.getTimestamp());
                        bwGraphs.addMutation(del);
                    }
                    removedStale = true;
                }
            }
            if (removedStale) {
                mtbw.flush();
            }
            return new CloseableIteratorIteration<>(graphs.iterator());
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    private boolean graphHasData(final String graph, final Authorizations auths) throws TableNotFoundException {
        try (Scanner scanner = client.createScanner(tables.getSpo(), auths)) {
            scanner.fetchColumnFamily(new Text(graph));
            scanner.setBatchSize(1);
            return scanner.iterator().hasNext();
        }
    }

    @Override
    public long size(final AccumuloRdfConfiguration aconf, final RyaIRI... contexts) {
        final Authorizations auths = (aconf != null ? aconf : conf).getAuthorizations();
        try {
            flush();
            try (BatchScanner scanner = client.createBatchScanner(tables.getSpo(), auths, NUM_THREADS)) {
                scanner.setRanges(Collections.singleton(new Range()));
                for (final RyaIRI context : contexts) {
                    scanner.fetchColumnFamily(context == null ? EMPTY_TEXT : new Text(context.getData()));
                }
                long count = 0;
                for (final Iterator<Map.Entry<Key, Value>> it = scanner.iterator(); it.hasNext(); it.next()) {
                    count++;
                }
                return count;
            }
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public synchronized void addNamespace(final String pfx, final String namespace) {
        try {
            final Mutation m = new Mutation(new Text(pfx));
            m.put(INFO_NAMESPACE_TXT, EMPTY_TEXT, new Value(namespace.getBytes(StandardCharsets.UTF_8)));
            bwNs.addMutation(m);
            if (flushEachUpdate) {
                mtbw.flush();
            }
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public String getNamespace(final String pfx) {
        try (Scanner scanner = client.createScanner(tables.getNs(), NO_AUTHORIZATIONS)) {
            scanner.fetchColumn(INFO_NAMESPACE_TXT, EMPTY_TEXT);
            scanner.setRange(new Range(new Text(pfx)));
            final Iterator<Map.Entry<Key, Value>> it = scanner.iterator();
            return it.hasNext() ? new String(it.next().getValue().get(), StandardCharsets.UTF_8) : null;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public synchronized void removeNamespace(final String pfx) {
        try {
            final Mutation del = new Mutation(new Text(pfx));
            del.putDelete(INFO_NAMESPACE_TXT, EMPTY_TEXT);
            bwNs.addMutation(del);
            if (flushEachUpdate) {
                mtbw.flush();
            }
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public CloseableIteration<Namespace> iterateNamespace() {
        try {
            final Scanner scanner = client.createScanner(tables.getNs(), NO_AUTHORIZATIONS);
            scanner.fetchColumnFamily(INFO_NAMESPACE_TXT);
            return new AccumuloNamespaceTableIterator(scanner);
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public RyaNamespaceManager<AccumuloRdfConfiguration> getNamespaceManager() {
        return this;
    }

    /**
     * Deletes all data visible with the configuration's auths from every Rya table, then
     * rewrites the storage version.
     */
    @Override
    public synchronized void purge(final RdfCloudTripleStoreConfiguration configuration) {
        final Authorizations auths = new Authorizations(configuration.getAuths());
        try {
            flush();
            for (final String table : getTables()) {
                if (client.tableOperations().exists(table)) {
                    try (BatchDeleter deleter = client.createBatchDeleter(table, auths, NUM_THREADS, batchWriterConfig)) {
                        deleter.setRanges(Collections.singleton(new Range()));
                        deleter.delete();
                    }
                }
            }
            if (isInitialized.get()) {
                writeVersionIfMissing();
            }
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public synchronized void dropAndDestroy() {
        destroy();
        for (final String table : getTables()) {
            try {
                if (client.tableOperations().exists(table)) {
                    client.tableOperations().delete(table);
                }
            } catch (final TableNotFoundException e) {
                logger.debug("Table {} already gone", table);
            } catch (final Exception e) {
                throw new RyaDAOException(e);
            }
        }
    }

    @Override
    public synchronized void flush() {
        try {
            if (mtbw != null) {
                mtbw.flush();
            }
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
            mtbw.close();
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        } finally {
            mtbw = null;
        }
    }

    /** Same as {@link #destroy()}. */
    @Override
    public void close() {
        destroy();
    }

    /** @return the names of every table this DAO uses. */
    public List<String> getTables() {
        return List.of(tables.getSpo(), tables.getPo(), tables.getOsp(), tables.getNs(), tables.getEval(),
                tables.getGraphs());
    }

    public AccumuloClient getClient() {
        return client;
    }

    public void setClient(final AccumuloClient client) {
        this.client = client;
    }

    public BatchWriterConfig getBatchWriterConfig() {
        return batchWriterConfig;
    }

    public void setBatchWriterConfig(final BatchWriterConfig batchWriterConfig) {
        this.batchWriterConfig = batchWriterConfig;
    }

    @Override
    public synchronized AccumuloRdfConfiguration getConf() {
        return conf;
    }

    @Override
    public synchronized void setConf(final AccumuloRdfConfiguration conf) {
        this.conf = conf;
    }

    @Override
    public AccumuloRyaQueryEngine getQueryEngine() {
        return queryEngine;
    }
}
