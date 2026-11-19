package org.tripatha.rdftriplestore;

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

import java.util.function.Function;
import org.eclipse.rdf4j.query.algebra.evaluation.NativeTripleTermSource;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import java.util.HashSet;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.domain.RyaTypeRange;
import org.tripatha.api.domain.RyaValueRange;
import org.eclipse.rdf4j.common.iteration.UnionIteration;
import org.tripatha.api.domain.RyaSchema;
import org.tripatha.api.domain.RyaTripleTerm;
import org.tripatha.api.resolver.impl.TripleTermResolver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.RyaStoreEvalStatsDAO;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.index.IndexCatalog;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.domain.RyaAnyTypeRange;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.StatementMetadata;
import org.tripatha.api.persist.RdfEvalStatsDAO;
import org.tripatha.api.persist.RyaDAO;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.api.resolver.RdfToRyaConversions;
import org.tripatha.api.resolver.RyaToRdfConversions;
import org.tripatha.rdftriplestore.evaluation.BatchLookup;
import org.tripatha.rdftriplestore.evaluation.LookupKey;
import org.tripatha.rdftriplestore.evaluation.RdfCloudTripleStoreEvaluationStatistics;
import org.tripatha.rdftriplestore.evaluation.RyaDefaultStatistics;
import org.tripatha.rdftriplestore.evaluation.RyaEvaluationStrategy;
import org.tripatha.rdftriplestore.evaluation.RyaQueryOptimizerPipeline;
import org.tripatha.rdftriplestore.evaluation.ValueRangeOptimizer;
import org.tripatha.rdftriplestore.inference.InferenceEngine;
import org.tripatha.rdftriplestore.inference.InferenceOptimizer;
import org.tripatha.rdftriplestore.namespace.NamespaceManager;
import org.tripatha.rdftriplestore.stats.GraphSizeOptimizer;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.common.iteration.ConvertingIteration;
import org.eclipse.rdf4j.common.iteration.FilterIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.helpers.AbstractSailConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connection to a {@link RdfCloudTripleStore}. Writes go straight to the DAO: there is no
 * transaction isolation, and rollback does not undo writes.
 *
 * <p>Each connection works on its own copy of the store configuration, so settings changed
 * through {@link #getConf()} (visibility of new statements, scan authorizations, inference)
 * affect this connection only.
 */
public class RdfCloudTripleStoreConnection<C extends RdfCloudTripleStoreConfiguration> extends AbstractSailConnection {
    private static final Logger log = LoggerFactory.getLogger(RdfCloudTripleStoreConnection.class);

    private final RdfCloudTripleStore<C> store;
    private final RyaDAO<C> ryaDAO;
    private final RdfEvalStatsDAO<C> rdfEvalStatsDAO;
    private final InferenceEngine inferenceEngine;
    private final NamespaceManager namespaceManager;
    private final C conf;

    @SuppressWarnings("unchecked")
    public RdfCloudTripleStoreConnection(final RdfCloudTripleStore<C> store, final C conf) throws SailException {
        super(store);
        this.store = store;
        this.conf = (C) conf.clone();
        this.ryaDAO = store.getRyaDAO();
        this.rdfEvalStatsDAO = store.getRdfEvalStatsDAO();
        this.inferenceEngine = store.getInferenceEngine();
        this.namespaceManager = store.getNamespaceManager();
        if (ryaDAO == null || !ryaDAO.isInitialized() || namespaceManager == null) {
            throw new SailException("The Tripatha store is not initialized");
        }
    }

    /** @return this connection's configuration; changes apply to this connection only. */
    public C getConf() {
        return conf;
    }

    public InferenceEngine getInferenceEngine() {
        return inferenceEngine;
    }

    // ------------------------------------------------------------------ writes

    @Override
    protected void addStatementInternal(final Resource subject, final IRI predicate, final Value object,
            final Resource... contexts) throws SailException {
        final String cvString = conf.getCv();
        final byte[] cv = cvString == null ? null : cvString.getBytes(StandardCharsets.UTF_8);
        final List<RyaStatement> statements = new ArrayList<>();
        for (final Resource context : contextsOrDefault(contexts)) {
            statements.add(new RyaStatement(
                    RdfToRyaConversions.convertResource(subject),
                    RdfToRyaConversions.convertIRI(predicate),
                    RdfToRyaConversions.convertValue(object),
                    RdfToRyaConversions.convertResource(context),
                    null, new StatementMetadata(), cv));
        }
        try {
            ryaDAO.add(statements.iterator());
        } catch (final RyaDAOException e) {
            throw new SailException(e);
        }
    }

    @Override
    protected void removeStatementsInternal(final Resource subject, final IRI predicate, final Value object,
            final Resource... contexts) throws SailException {
        try {
            if (contexts == null || contexts.length == 0) {
                ryaDAO.delete(pattern(subject, predicate, object, null), st -> true, conf);
                return;
            }
            for (final Resource context : distinct(contexts)) {
                if (context == null) {
                    // A null context in a Tripatha pattern means "any graph", so keep only default-graph matches.
                    ryaDAO.delete(pattern(subject, predicate, object, null), st -> st.getContext() == null, conf);
                } else {
                    ryaDAO.delete(pattern(subject, predicate, object, context), st -> true, conf);
                }
            }
        } catch (final RyaDAOException e) {
            throw new SailException(e);
        }
    }

    @Override
    protected void clearInternal(final Resource... contexts) throws SailException {
        try {
            final RyaIRI[] graphs = new RyaIRI[contexts == null ? 0 : contexts.length];
            for (int i = 0; i < graphs.length; i++) {
                graphs[i] = RdfToRyaConversions.convertResource(contexts[i]);
            }
            ryaDAO.dropGraph(conf, graphs);
        } catch (final RyaDAOException e) {
            throw new SailException(e);
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    protected CloseableIteration<? extends Statement> getStatementsInternal(final Resource subject, final IRI predicate,
            final Value object, final boolean includeInferred, final Resource... contexts) throws SailException {
        // Inferred lookups go through the query engine, which only supports "all graphs" here;
        // a context-restricted lookup (ADD/COPY/MOVE, the default graph) returns stored statements.
        if (includeInferred && conf.isInfer() && inferenceEngine != null && (contexts == null || contexts.length == 0)) {
            return inferredStatements(subject, predicate, object);
        }
        return new StoreTripleSource<>(conf, ryaDAO).getStatements(subject, predicate, object, contexts);
    }

    /**
     * Evaluates the pattern, across all graphs, through the query engine so that inferred
     * statements are included.
     */
    private CloseableIteration<? extends Statement> inferredStatements(final Resource subject, final IRI predicate,
            final Value object) {
        final Var s = var("s", subject);
        final Var p = var("p", predicate);
        final Var o = var("o", object);
        final Var c = new Var("c");
        final CloseableIteration<? extends BindingSet> results =
                evaluateInternal(new StatementPattern(s, p, o, c), null, EmptyBindingSet.getInstance(), true);
        final ValueFactory vf = store.getValueFactory();
        return new ConvertingIteration<BindingSet, Statement>(results) {
            @Override
            protected Statement convert(final BindingSet bs) {
                final Resource subj = (Resource) value(s, bs);
                final IRI pred = (IRI) value(p, bs);
                final Value obj = value(o, bs);
                final Value ctx = value(c, bs);
                return ctx == null ? vf.createStatement(subj, pred, obj) : vf.createStatement(subj, pred, obj, (Resource) ctx);
            }
        };
    }

    private static Var var(final String name, final Value value) {
        return value == null ? new Var(name) : new Var(name, value);
    }

    private static Value value(final Var var, final BindingSet bs) {
        return var.hasValue() ? var.getValue() : bs.getValue(var.getName());
    }

    @Override
    protected CloseableIteration<? extends BindingSet> evaluateInternal(TupleExpr tupleExpr, final Dataset dataset,
            final BindingSet bindings, final boolean includeInferred) throws SailException {
        final C queryConf = queryConfiguration(bindings);
        if (!includeInferred) {
            queryConf.setInfer(false);
        }
        tupleExpr = tupleExpr.clone();
        if (!(tupleExpr instanceof QueryRoot)) {
            tupleExpr = new QueryRoot(tupleExpr);
        }

        final TripleSource tripleSource = new StoreTripleSource<>(queryConf, ryaDAO);
        final EvaluationStatistics statistics = queryConf.isUseStats() && rdfEvalStatsDAO != null
                ? new RdfCloudTripleStoreEvaluationStatistics<>(queryConf, rdfEvalStatsDAO)
                : new RyaDefaultStatistics();
        final RyaEvaluationStrategy strategy = new RyaEvaluationStrategy(tripleSource, dataset, inferenceEngine, statistics);
        final IndexCatalog indexes = ryaDAO instanceof RyaStoreDAO accumulo ? accumulo.getIndexCatalog() : IndexCatalog.NONE;
        // The datatypes of a predicate's objects (the markers) and the counts of value ranges,
        // read with this query's labels.
        final ValueRangeOptimizer.Store valueRangeStore = ryaDAO instanceof RyaStoreDAO dao
                && queryConf instanceof StoreRdfConfiguration storeConf ? new ValueRangeOptimizer.Store() {
                    @Override
                    public Optional<Set<String>> datatypes(final IRI predicate) {
                        return dao.objectDatatypes(RdfToRyaConversions.convertIRI(predicate), storeConf);
                    }

                    @Override
                    public long count(final IRI predicate, final List<RyaValueRange> ranges, final long cap) {
                        try (StoreTrace.Reason why = StoreTrace.reason(() -> "value ranges: count the cells, up to " + cap
                                + ", to order the joins")) {
                            return dao.getQueryEngine().countValueRanges(RdfToRyaConversions.convertIRI(predicate), ranges, cap,
                                    storeConf);
                        }
                    }
                } : null;
        // Graph sizes come from the counters, read with this query's authorizations.
        final GraphSizeOptimizer graphSizes = new GraphSizeOptimizer(
                rdfEvalStatsDAO instanceof RyaStoreEvalStatsDAO stats && queryConf instanceof StoreRdfConfiguration accumuloConf
                        ? () -> stats.graphSizes(accumuloConf)
                        : null);
        strategy.setOptimizerPipeline(new RyaQueryOptimizerPipeline(strategy, tripleSource, statistics,
                new InferenceOptimizer(queryConf, inferenceEngine), indexes, queryConf, graphSizes, valueRangeStore));
        try {
            final TupleExpr parsed = tupleExpr;
            StoreTrace.plan("parsed", parsed::toString);
            final TupleExpr optimized;
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "query planning")) {
                optimized = strategy.optimize(tupleExpr, statistics, bindings);
            }
            if (queryConf.isDisplayQueryPlan()) {
                log.info("Tripatha query plan:\n{}", optimized);
            }
            if (StoreTrace.isActive()) {
                // Prepare exactly what evaluate() would, then report the plan with the join
                // algorithms preparing chose (RyaBatchedJoin or RDF4J's own).
                final TupleExpr root = optimized instanceof QueryRoot q ? q.getArg() : optimized;
                final QueryEvaluationStep step = strategy.precompile(root);
                StoreTrace.plan("optimized", optimized::toString);
                return step.evaluate(bindings);
            }
            return strategy.evaluate(optimized, bindings);
        } catch (final QueryEvaluationException e) {
            throw new SailException(e);
        }
    }

    /**
     * Copies this connection's configuration and applies per-query overrides passed as
     * bindings named after configuration keys ({@code query.auth}, {@code query.infer}, ...).
     */
    @SuppressWarnings("unchecked")
    private C queryConfiguration(final BindingSet bindings) {
        final C queryConf = (C) conf.clone();
        if (bindings == null) {
            return queryConf;
        }
        final Binding plan = bindings.getBinding(RdfCloudTripleStoreConfiguration.CONF_QUERYPLAN_FLAG);
        if (plan != null) {
            queryConf.setDisplayQueryPlan(Boolean.parseBoolean(plan.getValue().stringValue()));
        }
        final Binding auth = bindings.getBinding(RdfCloudTripleStoreConfiguration.CONF_QUERY_AUTH);
        if (auth != null) {
            queryConf.setAuths(auth.getValue().stringValue().split(","));
        }
        final Binding ttl = bindings.getBinding(RdfCloudTripleStoreConfiguration.CONF_TTL);
        if (ttl != null) {
            queryConf.setTtl(Long.valueOf(ttl.getValue().stringValue()));
        }
        final Binding startTime = bindings.getBinding(RdfCloudTripleStoreConfiguration.CONF_STARTTIME);
        if (startTime != null) {
            queryConf.setStartTime(Long.valueOf(startTime.getValue().stringValue()));
        }
        final Binding infer = bindings.getBinding(RdfCloudTripleStoreConfiguration.CONF_INFER);
        if (infer != null) {
            queryConf.setInfer(Boolean.parseBoolean(infer.getValue().stringValue()));
        }
        final Binding useStats = bindings.getBinding(RdfCloudTripleStoreConfiguration.CONF_USE_STATS);
        if (useStats != null) {
            queryConf.setUseStats(Boolean.parseBoolean(useStats.getValue().stringValue()));
        }
        final Binding useIndex = bindings.getBinding(RdfCloudTripleStoreConfiguration.CONF_USE_INDEX);
        if (useIndex != null) {
            queryConf.setUseIndex(Boolean.parseBoolean(useIndex.getValue().stringValue()));
        }
        return queryConf;
    }

    @Override
    protected CloseableIteration<? extends Resource> getContextIDsInternal() throws SailException {
        try {
            final List<Resource> graphs = new ArrayList<>();
            try (CloseableIteration<RyaIRI> ids = ryaDAO.getContextIDs(conf)) {
                while (ids.hasNext()) {
                    graphs.add(RyaToRdfConversions.convertResource(ids.next()));
                }
            }
            return new CloseableIteratorIteration<>(graphs.iterator());
        } catch (final RyaDAOException e) {
            throw new SailException(e);
        }
    }

    @Override
    protected long sizeInternal(final Resource... contexts) throws SailException {
        final Resource[] graphs = contexts == null ? new Resource[0] : distinct(contexts);
        final RyaIRI[] ryaGraphs = new RyaIRI[graphs.length];
        for (int i = 0; i < graphs.length; i++) {
            ryaGraphs[i] = RdfToRyaConversions.convertResource(graphs[i]);
        }
        try {
            return ryaDAO.size(conf, ryaGraphs);
        } catch (final RyaDAOException e) {
            throw new SailException(e);
        }
    }

    // ------------------------------------------------------------------ namespaces

    @Override
    protected String getNamespaceInternal(final String prefix) throws SailException {
        return namespaceManager.getNamespace(prefix);
    }

    @Override
    protected CloseableIteration<? extends Namespace> getNamespacesInternal() throws SailException {
        return namespaceManager.iterateNamespace();
    }

    @Override
    protected void setNamespaceInternal(final String prefix, final String name) throws SailException {
        namespaceManager.addNamespace(prefix, name);
    }

    @Override
    protected void removeNamespaceInternal(final String prefix) throws SailException {
        namespaceManager.removeNamespace(prefix);
    }

    @Override
    protected void clearNamespacesInternal() throws SailException {
        final List<String> prefixes = new ArrayList<>();
        try (CloseableIteration<? extends Namespace> it = namespaceManager.iterateNamespace()) {
            while (it.hasNext()) {
                prefixes.add(it.next().getPrefix());
            }
        }
        for (final String prefix : prefixes) {
            namespaceManager.removeNamespace(prefix);
        }
    }

    // ------------------------------------------------------------------ transactions (none)

    @Override
    protected void startTransactionInternal() throws SailException {
    }

    @Override
    protected void commitInternal() throws SailException {
        try {
            ryaDAO.flush();
        } catch (final RyaDAOException e) {
            throw new SailException(e);
        }
    }

    @Override
    protected void rollbackInternal() throws SailException {
        log.debug("Rollback requested, but Tripatha writes are not transactional; nothing undone");
    }

    @Override
    protected void closeInternal() throws SailException {
    }

    // ------------------------------------------------------------------ helpers

    private static RyaStatement pattern(final Resource subject, final IRI predicate, final Value object,
            final Resource context) {
        return new RyaStatement(RdfToRyaConversions.convertResource(subject), RdfToRyaConversions.convertIRI(predicate),
                RdfToRyaConversions.convertValue(object), RdfToRyaConversions.convertResource(context));
    }

    /** @return the contexts to write to: the default graph when none are given. */
    private static Resource[] contextsOrDefault(final Resource[] contexts) {
        return contexts == null || contexts.length == 0 ? new Resource[] { null } : distinct(contexts);
    }

    private static Resource[] distinct(final Resource[] contexts) {
        final Set<Resource> unique = new LinkedHashSet<>(Arrays.asList(contexts));
        return unique.toArray(new Resource[0]);
    }

    /**
     * Statement lookups for query evaluation, with RDF4J context semantics: no contexts means
     * every graph; a {@code null} context means the default graph only.
     */
    public static class StoreTripleSource<C extends RdfCloudTripleStoreConfiguration> implements NativeTripleTermSource {
        private final C conf;
        private final RyaDAO<C> ryaDAO;
        /** Statements fetched ahead of time by batched joins; consulted before the store. */
        private final Deque<Map<LookupKey, List<Statement>>> prefetched = new ArrayDeque<>();
        private long prefetches;
        private long overflows;
        private long cacheHits;
        private long storeLookups;

        public StoreTripleSource(final C conf, final RyaDAO<C> ryaDAO) {
            this.conf = conf;
            this.ryaDAO = ryaDAO;
        }

        public C getConf() {
            return conf;
        }

        public RyaDAO<C> getRyaDAO() {
            return ryaDAO;
        }

        /**
         * Fetches the statements for many lookups at once and serves them from memory until the
         * returned handle is closed.
         *
         * @return the handle, or {@code null} if the batch was too large to hold (then nothing is
         *         cached and lookups go to the store).
         */
        public AutoCloseable prefetch(final Collection<LookupKey> keys) {
            final Prefetch fetched = prefetch(keys, () -> "batched join: " + keys.size()
                    + " right-hand lookup" + (keys.size() == 1 ? "" : "s") + " fetched together");
            return fetched.isHeld() ? fetched : null;
        }

        /**
         * Fetches the statements for many lookups at once and serves them from memory until the
         * returned handle is closed.
         *
         * @param reason why the batch is read, for the trace.
         * @return the handle, never {@code null}: it holds nothing when there was nothing to
         *         fetch, or when the batch was too large to hold ({@link Prefetch#isOverflow()}).
         */
        public Prefetch prefetch(final Collection<LookupKey> keys, final Supplier<String> reason) {
            return hold(fetch(keys, reason));
        }

        /**
         * Fetches the statements for many lookups, without holding them: any thread may call it,
         * as an entity prefetch does for its next window (SCALE-STUDY.md O16). The query thread
         * then holds the result with {@link #hold}.
         *
         * @return the statements per lookup, or {@code null} if the batch was too large to hold
         */
        public Map<LookupKey, List<Statement>> fetch(final Collection<LookupKey> keys, final Supplier<String> reason) {
            try (StoreTrace.Reason why = StoreTrace.reason(reason)) {
                return BatchLookup.fetch(ryaDAO, conf, keys, conf.getJoinPrefetchLimit());
            }
        }

        /**
         * Serves a fetched batch from memory until the returned handle is closed; on the query
         * thread only, since the batches held are its own.
         *
         * @param fetched what {@link #fetch} returned: {@code null} for a batch too large to hold
         */
        public Prefetch hold(final Map<LookupKey, List<Statement>> fetched) {
            if (fetched == null) {
                overflows++;
                return new Prefetch(null, true);
            }
            if (fetched.isEmpty()) {
                return new Prefetch(null, false);
            }
            prefetches++;
            prefetched.push(fetched);
            return new Prefetch(fetched, false);
        }

        /** @return how many batches were prefetched. */
        public long getPrefetchCount() {
            return prefetches;
        }

        /** @return how many batches were too large to hold, so their lookups went to the store. */
        public long getPrefetchOverflowCount() {
            return overflows;
        }

        /** @return how many lookups were answered from a prefetched batch. */
        public long getCacheHitCount() {
            return cacheHits;
        }

        /** @return how many lookups went to the store. */
        public long getStoreLookupCount() {
            return storeLookups;
        }

        @Override
        public CloseableIteration<? extends Statement> getStatements(final Resource subject, final IRI predicate,
                final Value object, final Resource... contexts) throws QueryEvaluationException {
            if (!prefetched.isEmpty()) {
                final LookupKey key = LookupKey.of(subject, predicate, object, contexts);
                for (final Map<LookupKey, List<Statement>> batch : prefetched) {
                    final List<Statement> found = batch.get(key);
                    if (found != null) {
                        cacheHits++;
                        return new CloseableIteratorIteration<>(found.iterator());
                    }
                }
            }
            storeLookups++;
            return inGraphs(contexts, graph -> pattern(subject, predicate, object, graph));
        }

        /**
         * The statements whose object is a triple term starting with {@code prefix}
         * ({@link TripleTermResolver#prefix}), such as the terms about one subject: one range scan
         * of the predicate's objects, or of all objects without a predicate.
         */
        public CloseableIteration<? extends Statement> getStatementsAboutTripleTerms(final IRI predicate, final String prefix,
                final Resource... contexts) throws QueryEvaluationException {
            storeLookups++;
            final RyaIRI p = RdfToRyaConversions.convertIRI(predicate);
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "triple terms: only statements about terms with the pattern's constant parts are read")) {
                // U+10FFFF: no text sorts after its UTF-8.
                return inGraphs(contexts, graph -> new RyaStatement(null, p, new RyaTypeRange(new RyaType(RyaSchema.TRIPLE_TERM, prefix),
                        new RyaType(RyaSchema.TRIPLE_TERM, prefix + "\uDBFF\uDFFF")), RdfToRyaConversions.convertResource(graph)));
            }
        }

        /**
         * The statements with this predicate (any, if {@code null}) whose object's data starts with
         * {@code prefix}, whatever the object's type: the range of {@code po} (or {@code osp}) that
         * holds them. Only for a caller that checks each object itself, as a {@code STRSTARTS}
         * filter does.
         */
        public CloseableIteration<? extends Statement> getStatementsWithObjectPrefix(final IRI predicate, final String prefix,
                final Resource... contexts) throws QueryEvaluationException {
            storeLookups++;
            final RyaIRI p = predicate == null ? null : RdfToRyaConversions.convertIRI(predicate);
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "STRSTARTS: only objects starting with \"" + prefix + "\" are read")) {
                return inGraphs(contexts, graph -> new RyaStatement(null, p, RyaAnyTypeRange.startingWith(prefix),
                        RdfToRyaConversions.convertResource(graph)));
            }
        }

        /**
         * The statements with this predicate whose object is in one of the ranges of values
         * ({@code ValueRangePattern}): one range of {@code po} per range. Only for a caller that
         * checks each object itself, as the comparison filter the ranges were made for does.
         *
         * @param condition the comparisons, for the trace
         */
        public CloseableIteration<? extends Statement> getStatementsInValueRanges(final IRI predicate,
                final List<RyaValueRange> ranges, final String condition, final Resource... contexts)
                throws QueryEvaluationException {
            storeLookups++;
            final RyaIRI p = RdfToRyaConversions.convertIRI(predicate);
            final List<CloseableIteration<Statement>> parts = new ArrayList<>(ranges.size());
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "value ranges: only objects with " + condition + " are read, "
                    + ranges.size() + " range" + (ranges.size() == 1 ? "" : "s"))) {
                for (final RyaValueRange range : ranges) {
                    parts.add(inGraphs(contexts, graph -> new RyaStatement(null, p, range, RdfToRyaConversions.convertResource(graph))));
                }
            } catch (final RuntimeException e) {
                parts.forEach(CloseableIteration::close);
                throw e;
            }
            return parts.size() == 1 ? parts.get(0) : new UnionIteration<>(parts);
        }

        /**
         * The entity index (SCALE-STUDY.md O23), when queries may use it: it is READY and
         * {@code query.useindex} is on. Null otherwise.
         */
        public org.tripatha.store.index.EntityIndex entityIndex() {
            if (!conf.isUseIndex() || !(ryaDAO instanceof RyaStoreDAO dao)) {
                return null;
            }
            final IndexCatalog catalog = dao.getIndexCatalog();
            return catalog.isReady(org.tripatha.store.index.IndexType.ENTITY) ? catalog.entityIndex() : null;
        }

        /**
         * The subjects filed under {@code ?s predicate object} in one shard of the entity index,
         * in subject order, once per statement. Safe from any thread: it only opens a scan.
         */
        public CloseableIteration<byte[]> entitySubjects(final byte[] shardRow, final byte[] term) {
            if (!(ryaDAO.getQueryEngine() instanceof org.tripatha.store.query.RyaStoreQueryEngine engine)
                    || !(conf instanceof StoreRdfConfiguration storeConf)) {
                throw new QueryEvaluationException("The entity index needs Tripatha's store query engine");
            }
            try {
                return engine.entitySubjects(shardRow, term, storeConf);
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
        }

        /**
         * Whether stars read from the entity index are intersected in the servers: the index is
         * usable and the store can (Accumulo with Tripatha's iterators, {@code query.entityindex.servers}).
         */
        public boolean entityIntersectsInServers() {
            return entityIndex() != null && ryaDAO.getQueryEngine() instanceof org.tripatha.store.query.RyaStoreQueryEngine engine
                    && conf instanceof StoreRdfConfiguration storeConf && engine.intersectsEntitiesInServers(storeConf);
        }

        /** The subjects every term has in each shard, intersected in the servers; unordered across shards. */
        public CloseableIteration<org.tripatha.store.index.EntityIndex.Match> entityMatches(final List<byte[]> shardRows,
                final List<byte[]> terms) {
            if (!(ryaDAO.getQueryEngine() instanceof org.tripatha.store.query.RyaStoreQueryEngine engine)
                    || !(conf instanceof StoreRdfConfiguration storeConf)) {
                throw new QueryEvaluationException("The entity index needs Tripatha's store query engine");
            }
            storeLookups++;
            try {
                return engine.entityMatches(shardRows, terms, storeConf);
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
        }

        /** How many subjects every term has in these shards, and their rows, counted by the store. */
        public org.tripatha.store.spi.IntersectionCount entityCount(final List<byte[]> shardRows, final List<byte[]> terms) {
            if (!(ryaDAO.getQueryEngine() instanceof org.tripatha.store.query.RyaStoreQueryEngine engine)
                    || !(conf instanceof StoreRdfConfiguration storeConf)) {
                throw new QueryEvaluationException("The entity index needs Tripatha's store query engine");
            }
            storeLookups++;
            try {
                return engine.entityCount(shardRows, terms, storeConf);
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
        }

        /** Counts one more store lookup: for merges, whose scans other threads open. */
        public void countLookup() {
            storeLookups++;
        }

        /**
         * Subjects that divide the store's subjects into about {@code slices} parts, sorted by
         * their UTF-8 bytes, for a merge join read in slices; none when the store can't tell.
         */
        public List<byte[]> subjectBoundaries(final int slices) {
            return ryaDAO instanceof RyaStoreDAO dao ? dao.subjectBoundaries(slices) : List.of();
        }

        /**
         * The subjects of the statements {@code ?s predicate object} of every graph, in subject
         * order, as their UTF-8 bytes, once per statement: one range of {@code po}, or the slice of
         * it between two subjects. For a merge join ({@code SubjectMergeStep}), which reads only
         * subjects.
         *
         * @param from the first subject included, or null
         * @param to the first subject left out, or null
         */
        public CloseableIteration<byte[]> subjectsInOrder(final IRI predicate, final Value object, final byte[] from,
                final byte[] to) {
            storeLookups++;
            if (!(ryaDAO.getQueryEngine() instanceof org.tripatha.store.query.RyaStoreQueryEngine engine)
                    || !(conf instanceof StoreRdfConfiguration storeConf)) {
                throw new QueryEvaluationException("Merge joins need Tripatha's store query engine");
            }
            try {
                return engine.subjects(RdfToRyaConversions.convertIRI(predicate), RdfToRyaConversions.convertValue(object),
                        from, to, storeConf);
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
        }

        /** Runs a lookup with RDF4J's context semantics: no contexts means every graph; {@code null} the default graph only. */
        private CloseableIteration<Statement> inGraphs(final Resource[] contexts, final Function<Resource, RyaStatement> pattern) {
            if (contexts == null || contexts.length == 0) {
                return lookup(pattern.apply(null), false);
            }
            final Resource[] graphs = distinct(contexts);
            if (graphs.length == 1) {
                return lookup(pattern.apply(graphs[0]), graphs[0] == null);
            }
            return new Concat(pattern, graphs);
        }

        private CloseableIteration<Statement> lookup(final RyaStatement pattern, final boolean defaultGraphOnly) {
            final CloseableIteration<RyaStatement> found;
            try {
                found = ryaDAO.getQueryEngine().query(pattern, conf);
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
            final CloseableIteration<RyaStatement> filtered = !defaultGraphOnly ? found
                    : new FilterIteration<RyaStatement>(found) {
                        @Override
                        protected boolean accept(final RyaStatement st) {
                            return st.getContext() == null;
                        }

                        @Override
                        protected void handleClose() {
                        }
                    };
            return new ConvertingIteration<RyaStatement, Statement>(filtered) {
                @Override
                protected Statement convert(final RyaStatement st) {
                    return RyaToRdfConversions.convertStatement(st);
                }
            };
        }

        /** Every triple term in the store, at any depth, that has these parts; {@code null} is a wildcard. */
        @Override
        public CloseableIteration<? extends TripleTerm> getTripleTerms(final Resource subject, final IRI predicate,
                final Value object) throws QueryEvaluationException {
            return tripleTerms(subject, predicate, object, false);
        }

        /**
         * The distinct triple terms in the store with these parts ({@code null} is a wildcard):
         * one range scan of the object order, where triple terms are together.
         *
         * @param topLevelOnly only terms that are objects of statements, not terms nested in
         *        them. The scan is then narrowed to the terms about {@code subject}, if given;
         *        nested terms can be inside a term about anything, so they need the whole range.
         */
        public CloseableIteration<TripleTerm> tripleTerms(final Resource subject, final IRI predicate, final Value object,
                final boolean topLevelOnly) throws QueryEvaluationException {
            final RyaIRI s = topLevelOnly ? RdfToRyaConversions.convertResource(subject) : null;
            final RyaIRI p = s == null ? null : RdfToRyaConversions.convertIRI(predicate);
            final String prefix = TripleTermResolver.prefix(s, p);
            // U+10FFFF: no text sorts after its UTF-8.
            final RyaStatement range = new RyaStatement(null, null, new RyaTypeRange(
                    new RyaType(RyaSchema.TRIPLE_TERM, prefix), new RyaType(RyaSchema.TRIPLE_TERM, prefix + "\uDBFF\uDFFF")));
            final CloseableIteration<RyaStatement> found;
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "triple terms: the pattern's term is not bound by another pattern, so "
                    + (s == null ? "all stored triple terms are read" : "the stored triple terms about <" + s.getData() + "> are read"))) {
                storeLookups++;
                found = ryaDAO.getQueryEngine().query(range, conf);
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
            return new LookAheadIteration<>() {
                private final Deque<TripleTerm> next = new ArrayDeque<>();
                private final Set<TripleTerm> nestedSeen = new HashSet<>();
                private RyaType last;

                @Override
                protected TripleTerm getNextElement() {
                    while (next.isEmpty() && found.hasNext()) {
                        // The same term is the object of adjacent rows: once per statement, graph and label.
                        final RyaType o = found.next().getObject();
                        if (!(o instanceof RyaTripleTerm) || o.equals(last)) {
                            continue;
                        }
                        last = o;
                        TripleTerm term = (TripleTerm) RyaToRdfConversions.convertValue(o);
                        offer(term);
                        while (!topLevelOnly && term.getObject() instanceof TripleTerm nested) {
                            // A nested term that is also an object of a statement comes from the scan itself.
                            if (nestedSeen.add(nested) && !isStatementObject(nested)) {
                                offer(nested);
                            }
                            term = nested;
                        }
                    }
                    return next.poll();
                }

                private void offer(final TripleTerm term) {
                    if ((subject == null || subject.equals(term.getSubject()))
                            && (predicate == null || predicate.equals(term.getPredicate()))
                            && (object == null || object.equals(term.getObject()))) {
                        next.add(term);
                    }
                }

                @Override
                protected void handleClose() {
                    found.close();
                }
            };
        }

        private boolean isStatementObject(final TripleTerm term) {
            try (CloseableIteration<RyaStatement> it = ryaDAO.getQueryEngine()
                    .query(new RyaStatement(null, null, RdfToRyaConversions.convertValue(term)), conf)) {
                return it.hasNext();
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
        }

        @Override
        public ValueFactory getValueFactory() {
            return org.tripatha.api.RdfCloudTripleStoreConstants.VALUE_FACTORY;
        }

        /** Statements fetched ahead for a batch of lookups; closing it sends those lookups to the store again. */
        public final class Prefetch implements AutoCloseable {
            private final Map<LookupKey, List<Statement>> batch;
            private final boolean overflow;

            private Prefetch(final Map<LookupKey, List<Statement>> batch, final boolean overflow) {
                this.batch = batch;
                this.overflow = overflow;
            }

            /** @return whether statements are held (and lookups served from memory) until closed. */
            public boolean isHeld() {
                return batch != null;
            }

            /** @return whether the batch held more than {@code query.join.prefetchlimit} statements. */
            public boolean isOverflow() {
                return overflow;
            }

            @Override
            public void close() {
                if (batch != null) {
                    prefetched.removeIf(m -> m == batch);
                }
            }
        }

        /** Looks up each graph in turn. */
        private final class Concat implements CloseableIteration<Statement> {
            private final Function<Resource, RyaStatement> pattern;
            private final Iterator<Resource> graphs;
            private CloseableIteration<Statement> current;

            Concat(final Function<Resource, RyaStatement> pattern, final Resource[] graphs) {
                this.pattern = pattern;
                this.graphs = Arrays.asList(graphs).iterator();
            }

            @Override
            public boolean hasNext() {
                while (current == null || !current.hasNext()) {
                    if (current != null) {
                        current.close();
                        current = null;
                    }
                    if (!graphs.hasNext()) {
                        return false;
                    }
                    final Resource g = graphs.next();
                    current = lookup(pattern.apply(g), g == null);
                }
                return true;
            }

            @Override
            public Statement next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }

            @Override
            public void close() {
                if (current != null) {
                    current.close();
                    current = null;
                }
            }
        }
    }
}
