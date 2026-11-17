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
package org.tripatha.rdftriplestore.evaluation;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;

/**
 * The store, for a {@link ValueRangePattern}: with neither end bound, its lookup reads only the
 * pattern's value ranges. Otherwise the lookup is already narrow, and goes to the store as it is.
 */
final class ValueRangeSource implements TripleSource {
    private final StoreTripleSource<?> store;
    private final ValueRangePattern pattern;

    ValueRangeSource(final StoreTripleSource<?> store, final ValueRangePattern pattern) {
        this.store = store;
        this.pattern = pattern;
    }

    @Override
    public CloseableIteration<? extends Statement> getStatements(final Resource subject, final IRI predicate,
            final Value object, final Resource... contexts) throws QueryEvaluationException {
        if (subject != null || object != null || predicate == null) {
            return store.getStatements(subject, predicate, object, contexts);
        }
        return store.getStatementsInValueRanges(predicate, pattern.getRanges(), pattern.getCondition(), contexts);
    }

    @Override
    public ValueFactory getValueFactory() {
        return store.getValueFactory();
    }
}
