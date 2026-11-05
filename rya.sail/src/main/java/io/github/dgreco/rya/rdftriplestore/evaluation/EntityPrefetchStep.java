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
package io.github.dgreco.rya.rdftriplestore.evaluation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;

/**
 * Feeds the driver rows of an {@link EntityChain}: before handing on a window of rows, it
 * fetches every lookup the chain's patterns will make for them in one batch, so the joins and
 * OPTIONALs above find their statements in memory.
 *
 * <p>A window's statements are released when the next window is read. The links above are
 * nested loops ({@code JoinIterator}, {@code LeftJoinIterator}) that ask for the next driver row
 * only once they are done with the current one, so by then every lookup for the window has been
 * made. A lookup that is not in memory goes to the store: prefetching can cost time, never
 * change results.
 *
 * <p>Windows start small, so a query that stops early fetches little, and double up to the join
 * batch size. A window whose statements would pass {@code query.join.prefetchlimit} is halved
 * and fetched again; a single row that is still too large is left to the store.
 */
final class EntityPrefetchStep implements QueryEvaluationStep {
    static final int FIRST_WINDOW = 64;

    private final QueryEvaluationStep driver;
    private final List<StatementPattern> patterns;
    private final StoreTripleSource<?> source;
    private final int batchSize;

    EntityPrefetchStep(final QueryEvaluationStep driver, final List<StatementPattern> patterns,
            final StoreTripleSource<?> source, final int batchSize) {
        this.driver = driver;
        this.patterns = patterns;
        this.source = source;
        this.batchSize = batchSize;
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(final BindingSet bindings) {
        return new PrefetchingIteration(driver.evaluate(bindings));
    }

    private final class PrefetchingIteration extends LookAheadIteration<BindingSet> {
        private final CloseableIteration<BindingSet> rows;
        /** Rows read from the driver and not fetched for yet. */
        private final Deque<BindingSet> pending = new ArrayDeque<>();
        /** Rows whose lookups are in memory, to hand on. */
        private final Deque<BindingSet> ready = new ArrayDeque<>();
        private int window = Math.min(FIRST_WINDOW, batchSize);
        private StoreTripleSource<?>.Prefetch prefetch;

        PrefetchingIteration(final CloseableIteration<BindingSet> rows) {
            this.rows = rows;
        }

        @Override
        protected BindingSet getNextElement() {
            if (ready.isEmpty()) {
                release();
                while (pending.size() < window && rows.hasNext()) {
                    pending.add(rows.next());
                }
                if (pending.isEmpty()) {
                    return null;
                }
                fetchAhead();
            }
            return ready.poll();
        }

        /** Fetches for all pending rows, or for as large a first part of them as fits, and readies those rows. */
        private void fetchAhead() {
            int n = pending.size();
            while (true) {
                final List<BindingSet> head = head(n);
                final Set<LookupKey> keys = keysFor(head);
                // A single lookup is cheapest done on its own, when it is made.
                final StoreTripleSource<?>.Prefetch fetched = keys.size() < 2 ? null
                        : source.prefetch(keys, () -> "entity prefetch: " + keys.size() + " lookups for "
                                + head.size() + " row" + (head.size() == 1 ? "" : "s") + " fetched together");
                if (fetched == null || !fetched.isOverflow() || n == 1) {
                    prefetch = fetched;
                    for (int i = 0; i < n; i++) {
                        ready.add(pending.poll());
                    }
                    window = fetched != null && fetched.isOverflow() ? 1 : Math.min(n * 2, batchSize);
                    return;
                }
                n /= 2;
            }
        }

        private List<BindingSet> head(final int n) {
            final List<BindingSet> head = new ArrayList<>(n);
            final Iterator<BindingSet> it = pending.iterator();
            while (head.size() < n) {
                head.add(it.next());
            }
            return head;
        }

        private Set<LookupKey> keysFor(final List<BindingSet> head) {
            final Set<LookupKey> keys = new LinkedHashSet<>();
            for (final BindingSet row : head) {
                for (final StatementPattern sp : patterns) {
                    final LookupKey key = BatchedJoinStep.lookupFor(sp, row);
                    if (key != null && (key.subject() != null || key.object() != null)) {
                        keys.add(key);
                    }
                }
            }
            return keys;
        }

        private void release() {
            if (prefetch != null) {
                prefetch.close();
                prefetch = null;
            }
        }

        @Override
        protected void handleClose() {
            try {
                release();
            } finally {
                rows.close();
            }
        }
    }
}
