package io.github.dgreco.rya.rdftriplestore;

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
import java.util.Set;

import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.accumulo.index.IndexCatalog;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.StatementMetadata;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO;
import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.resolver.RdfToRyaConversions;
import io.github.dgreco.rya.api.resolver.RyaToRdfConversions;
import io.github.dgreco.rya.rdftriplestore.evaluation.BatchLookup;
import io.github.dgreco.rya.rdftriplestore.evaluation.LookupKey;
import io.github.dgreco.rya.rdftriplestore.evaluation.RdfCloudTripleStoreEvaluationStatistics;
import io.github.dgreco.rya.rdftriplestore.evaluation.RyaDefaultStatistics;
import io.github.dgreco.rya.rdftriplestore.evaluation.RyaEvaluationStrategy;
import io.github.dgreco.rya.rdftriplestore.evaluation.RyaQueryOptimizerPipeline;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceEngine;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceOptimizer;
import io.github.dgreco.rya.rdftriplestore.namespace.NamespaceManager;
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
            throw new SailException("The Rya store is not initialized");
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
                    // A null context in a Rya pattern means "any graph", so keep only default-graph matches.
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
        final IndexCatalog indexes = ryaDAO instanceof AccumuloRyaDAO accumulo ? accumulo.getIndexCatalog() : IndexCatalog.NONE;
        strategy.setOptimizerPipeline(new RyaQueryOptimizerPipeline(strategy, tripleSource, statistics,
                new InferenceOptimizer(queryConf, inferenceEngine), indexes, queryConf));
        try {
            final TupleExpr optimized = strategy.optimize(tupleExpr, statistics, bindings);
            if (queryConf.isDisplayQueryPlan()) {
                log.info("Rya query plan:\n{}", optimized);
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
        log.debug("Rollback requested, but Rya writes are not transactional; nothing undone");
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
    public static class StoreTripleSource<C extends RdfCloudTripleStoreConfiguration> implements TripleSource {
        private final C conf;
        private final RyaDAO<C> ryaDAO;
        /** Statements fetched ahead of time by batched joins; consulted before the store. */
        private final Deque<Map<LookupKey, List<Statement>>> prefetched = new ArrayDeque<>();
        private long prefetches;
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
            final Map<LookupKey, List<Statement>> fetched =
                    BatchLookup.fetch(ryaDAO, conf, keys, conf.getJoinPrefetchLimit());
            if (fetched == null || fetched.isEmpty()) {
                return null;
            }
            prefetches++;
            prefetched.push(fetched);
            return () -> prefetched.removeIf(m -> m == fetched);
        }

        /** @return how many batches were prefetched. */
        public long getPrefetchCount() {
            return prefetches;
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
            if (contexts == null || contexts.length == 0) {
                return lookup(subject, predicate, object, null, false);
            }
            final Resource[] graphs = distinct(contexts);
            if (graphs.length == 1) {
                return lookup(subject, predicate, object, graphs[0], graphs[0] == null);
            }
            return new Concat(subject, predicate, object, graphs);
        }

        private CloseableIteration<Statement> lookup(final Resource subject, final IRI predicate, final Value object,
                final Resource context, final boolean defaultGraphOnly) {
            final CloseableIteration<RyaStatement> found;
            try {
                found = ryaDAO.getQueryEngine().query(pattern(subject, predicate, object, context), conf);
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

        @Override
        public ValueFactory getValueFactory() {
            return io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.VALUE_FACTORY;
        }

        /** Looks up each graph in turn. */
        private final class Concat implements CloseableIteration<Statement> {
            private final Resource subject;
            private final IRI predicate;
            private final Value object;
            private final Iterator<Resource> graphs;
            private CloseableIteration<Statement> current;

            Concat(final Resource subject, final IRI predicate, final Value object, final Resource[] graphs) {
                this.subject = subject;
                this.predicate = predicate;
                this.object = object;
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
                    current = lookup(subject, predicate, object, g, g == null);
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
