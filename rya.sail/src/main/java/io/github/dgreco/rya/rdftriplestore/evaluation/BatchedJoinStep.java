package io.github.dgreco.rya.rdftriplestore.evaluation;

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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF4J;
import org.eclipse.rdf4j.model.vocabulary.SESAME;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;

/**
 * A nested-loop join (same results and order as RDF4J's {@code JoinIterator}) whose right-hand
 * statement lookups are fetched in batches: it reads up to {@code batchSize} left rows, works
 * out the lookup each of them will make, fetches them all with one batch scan per table, and
 * then runs RDF4J's own right-hand step for each row against those prefetched statements.
 */
final class BatchedJoinStep implements QueryEvaluationStep {
    private final QueryEvaluationStep left;
    private final QueryEvaluationStep right;
    private final StatementPattern rightPattern;
    private final StoreTripleSource<?> source;
    private final int batchSize;

    BatchedJoinStep(final QueryEvaluationStep left, final QueryEvaluationStep right,
            final StatementPattern rightPattern, final StoreTripleSource<?> source, final int batchSize) {
        this.left = left;
        this.right = right;
        this.rightPattern = rightPattern;
        this.source = source;
        this.batchSize = batchSize;
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(final BindingSet bindings) {
        return new BatchedJoinIteration(left.evaluate(bindings));
    }

    /** @return the lookup RDF4J's statement-pattern step will make for these bindings, or null for none. */
    static LookupKey lookupFor(final StatementPattern sp, final BindingSet bindings) {
        final Value s = value(sp.getSubjectVar(), bindings);
        final Value p = value(sp.getPredicateVar(), bindings);
        final Value o = value(sp.getObjectVar(), bindings);
        if ((s != null && !(s instanceof Resource)) || (p != null && !(p instanceof IRI))) {
            return null;
        }
        final Value c = value(sp.getContextVar(), bindings);
        if (c == null) {
            return LookupKey.of((Resource) s, (IRI) p, o);
        }
        if (RDF4J.NIL.equals(c) || SESAME.NIL.equals(c)) {
            return LookupKey.of((Resource) s, (IRI) p, o, (Resource) null);
        }
        if (!(c instanceof Resource)) {
            return null;
        }
        return LookupKey.of((Resource) s, (IRI) p, o, (Resource) c);
    }

    private static Value value(final Var var, final BindingSet bindings) {
        return var == null ? null : DefaultEvaluationStrategy.getVarValue(var, bindings);
    }

    private final class BatchedJoinIteration extends LookAheadIteration<BindingSet> {
        private final CloseableIteration<BindingSet> leftRows;
        private Iterator<BindingSet> chunk;
        private AutoCloseable prefetch;
        private CloseableIteration<BindingSet> current;

        BatchedJoinIteration(final CloseableIteration<BindingSet> leftRows) {
            this.leftRows = leftRows;
        }

        @Override
        protected BindingSet getNextElement() {
            while (true) {
                if (current != null) {
                    if (current.hasNext()) {
                        return current.next();
                    }
                    current.close();
                    current = null;
                }
                if (chunk != null && chunk.hasNext()) {
                    current = right.evaluate(chunk.next());
                    continue;
                }
                releasePrefetch();
                if (!leftRows.hasNext()) {
                    return null;
                }
                final List<BindingSet> rows = new ArrayList<>(batchSize);
                final Set<LookupKey> keys = new LinkedHashSet<>();
                while (rows.size() < batchSize && leftRows.hasNext()) {
                    final BindingSet row = leftRows.next();
                    rows.add(row);
                    final LookupKey key = lookupFor(rightPattern, row);
                    if (key != null) {
                        keys.add(key);
                    }
                }
                if (!keys.isEmpty() && rows.size() > 1) {
                    prefetch = source.prefetch(keys);
                }
                chunk = rows.iterator();
            }
        }

        private void releasePrefetch() {
            if (prefetch != null) {
                try {
                    prefetch.close();
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
                prefetch = null;
            }
        }

        @Override
        protected void handleClose() {
            try {
                if (current != null) {
                    current.close();
                    current = null;
                }
                releasePrefetch();
            } finally {
                leftRows.close();
            }
        }
    }
}
