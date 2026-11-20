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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.Trace;

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
 *
 * <p>With {@code query.join.prefetchahead} (SCALE-STUDY.md O16), from the second window on, the
 * next window's rows are read from the driver as soon as the current window is handed on, and
 * their keys are built and their lookups fetched on another thread while the current window is
 * joined. Only the query thread holds a batch: it takes the next window's statements when the
 * current one is used up, and a fetch too large to hold falls back to the halving above. A query
 * that stops in its first window fetches no more than before; at most two windows are in memory.
 * Closing the step waits for a fetch still running, so none outlives the query.
 */
final class EntityPrefetchStep implements QueryEvaluationStep {
    static final int FIRST_WINDOW = 64;
    /** Threads fetching windows ahead: daemons, kept a minute when idle. */
    private static final ExecutorService AHEAD_THREADS = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "tripatha-prefetch-ahead");
        t.setDaemon(true);
        return t;
    });

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
        private final boolean fetchAheadOn = source.getConf().isJoinPrefetchAhead();
        /** Windows handed on so far: fetching ahead starts once the first one is used up. */
        private int windows;
        /** The next window, being fetched on another thread; null when none is. */
        private Ahead ahead;

        PrefetchingIteration(final CloseableIteration<BindingSet> rows) {
            this.rows = rows;
        }

        @Override
        protected BindingSet getNextElement() {
            if (ready.isEmpty()) {
                release();
                if (ahead != null) {
                    takeAhead();
                } else {
                    while (pending.size() < window && rows.hasNext()) {
                        pending.add(rows.next());
                    }
                    if (pending.isEmpty()) {
                        return null;
                    }
                    fetchAhead();
                }
                windows++;
                if (fetchAheadOn && windows >= 2 && pending.isEmpty()) {
                    startAhead();
                }
            }
            return ready.poll();
        }

        /**
         * Reads the next window's rows from the driver, here, and fetches their lookups on another
         * thread, under this query's trace. Its result is held only when this window is used up.
         */
        private void startAhead() {
            final List<BindingSet> next = new ArrayList<>(window);
            while (next.size() < window && rows.hasNext()) {
                next.add(rows.next());
            }
            if (next.isEmpty()) {
                return;
            }
            final Trace trace = StoreTrace.current();
            final String why = trace == null ? "" : trace.reason();
            final String reason = (why.isEmpty() ? "" : why + " › ") + "the next window, fetched on a thread of its own";
            final FutureTask<Fetched> task = new FutureTask<>(() -> {
                try (StoreTrace.Scope joined = StoreTrace.join(trace, reason)) {
                    final Set<LookupKey> keys = new LinkedHashSet<>();
                    final Set<LookupKey> counted = new LinkedHashSet<>();
                    keysFor(next, keys, counted);
                    if (keys.size() + counted.size() < 2) {
                        return new Fetched(null, false);
                    }
                    final StoreTripleSource.Batch fetched = source.fetch(keys, counted, () -> "entity prefetch: "
                            + (keys.size() + counted.size()) + " lookups for " + next.size() + " row" + (next.size() == 1 ? "" : "s")
                            + " fetched together");
                    return new Fetched(fetched, fetched == null);
                }
            });
            ahead = new Ahead(next, task);
            AHEAD_THREADS.execute(task);
        }

        /** Hands on the next window fetched ahead: its statements held, or its rows fetched here if they were too many to hold. */
        private void takeAhead() {
            final Ahead a = ahead;
            ahead = null;
            final Fetched result = a.await();
            if (result.overflow()) {
                pending.addAll(a.rows());
                fetchAhead();
                return;
            }
            prefetch = result.batch() == null ? null : source.hold(result.batch());
            ready.addAll(a.rows());
            window = Math.min(a.rows().size() * 2, batchSize);
        }

        /** Fetches for all pending rows, or for as large a first part of them as fits, and readies those rows. */
        private void fetchAhead() {
            int n = pending.size();
            while (true) {
                final List<BindingSet> head = head(n);
                final Set<LookupKey> keys = new LinkedHashSet<>();
                final Set<LookupKey> counted = new LinkedHashSet<>();
                keysFor(head, keys, counted);
                // A single lookup is cheapest done on its own, when it is made.
                final StoreTripleSource<?>.Prefetch fetched = keys.size() + counted.size() < 2 ? null
                        : source.prefetch(keys, counted, () -> "entity prefetch: " + (keys.size() + counted.size())
                                + " lookups for " + head.size() + " row" + (head.size() == 1 ? "" : "s") + " fetched together");
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

        /**
         * The lookups the rows will make: those that read statements into {@code keys}, those
         * counted instead into {@code counted} ({@link CountedPattern}, SCALE-STUDY.md O26).
         */
        private void keysFor(final List<BindingSet> head, final Set<LookupKey> keys, final Set<LookupKey> counted) {
            final Set<LookupKey> all = new LinkedHashSet<>();
            for (final BindingSet row : head) {
                for (final StatementPattern sp : patterns) {
                    BatchedJoinStep.addKey(sp, row, all, counted);
                }
            }
            for (final LookupKey key : all) {
                if (key.subject() != null || key.object() != null) {
                    keys.add(key);
                }
            }
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
                if (ahead != null) {
                    // Its scans must end before the query does, and with it the connection's store.
                    ahead.awaitQuietly();
                    ahead = null;
                }
                release();
            } finally {
                rows.close();
            }
        }
    }

    /** What a fetch ahead found: the statements and counts per lookup (null when the window had one lookup or none), or that they were too many to hold. */
    private record Fetched(StoreTripleSource.Batch batch, boolean overflow) {
    }

    /** A window's rows and their fetch, running on another thread. */
    private record Ahead(List<BindingSet> rows, FutureTask<Fetched> task) {
        /** The fetch's result, waited for even if this thread is interrupted (the interrupt is kept); its failure, rethrown. */
        Fetched await() {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        return task.get();
                    } catch (final InterruptedException e) {
                        interrupted = true;
                    } catch (final ExecutionException e) {
                        final Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        if (cause instanceof Error err) {
                            throw err;
                        }
                        throw new QueryEvaluationException(cause);
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** Waits for the fetch to end, whatever its result. */
        void awaitQuietly() {
            try {
                await();
            } catch (final RuntimeException | Error e) {
                // Closing: the query no longer wants its statements.
            }
        }
    }
}
