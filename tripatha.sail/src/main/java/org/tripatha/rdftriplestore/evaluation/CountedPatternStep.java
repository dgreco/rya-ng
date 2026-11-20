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

import java.util.NoSuchElementException;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;

/**
 * A {@link CountedPattern}'s rows: each incoming row, repeated as many times as the pattern has
 * statements for it, which is what RDF4J's own step gives once the unused object is dropped. The
 * count comes from the batch a join prefetched, or from the store. A row the count can't serve
 * (the object or a graph to be bound, the default graph alone, a store that can't count) goes to
 * RDF4J's own step.
 */
final class CountedPatternStep implements QueryEvaluationStep {
    private final CountedPattern pattern;
    private final QueryEvaluationStep plain;
    private final StoreTripleSource<?> source;

    CountedPatternStep(final CountedPattern pattern, final QueryEvaluationStep plain, final StoreTripleSource<?> source) {
        this.pattern = pattern;
        this.plain = plain;
        this.source = source;
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(final BindingSet bindings) {
        // query.limit caps every lookup's statements, which a count doesn't.
        final LookupKey key = source.getConf().getLimit() != null ? null : CountedPattern.countKey(pattern, bindings);
        final long n = key == null ? -1 : source.count(key);
        return n < 0 ? plain.evaluate(bindings) : new Repeat(bindings, n);
    }

    /** One row, {@code n} times: RDF4J's steps copy a row before adding to it, so it can be shared. */
    private static final class Repeat implements CloseableIteration<BindingSet> {
        private final BindingSet row;
        private long left;

        Repeat(final BindingSet row, final long n) {
            this.row = row;
            this.left = n;
        }

        @Override
        public boolean hasNext() {
            return left > 0;
        }

        @Override
        public BindingSet next() {
            if (left <= 0) {
                throw new NoSuchElementException();
            }
            left--;
            return row;
        }

        @Override
        public void close() {
            left = 0;
        }
    }
}
