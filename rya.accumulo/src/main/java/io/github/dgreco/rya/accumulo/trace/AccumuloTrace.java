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
package io.github.dgreco.rya.accumulo.trace;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.accumulo.core.client.AccumuloClient;

/**
 * Traces the Accumulo operations of one piece of work: every scan (table, ranges, columns,
 * iterators, authorizations, entries returned, time) and every flush of writes, each with the
 * reason rya-ng had for it.
 * <pre>
 * try (AccumuloTrace.Scope scope = AccumuloTrace.start(event -&gt; ...)) {
 *     // run a query or an update on this thread
 * }   // reports the totals
 * </pre>
 * Only clients returned by {@link #wrap} report anything; {@code RyaAccumuloSailFactory} (in
 * rya.sail) wraps the clients it is given. A
 * trace belongs to the thread that started it, and costs nothing when none is running.
 */
public final class AccumuloTrace {
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    /** Traces running now, for the progress thread. */
    private static final Set<Trace> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Reason NO_REASON = () -> {
    };

    private AccumuloTrace() {
    }

    /** Ends a trace: reports the totals and detaches it from the thread. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** Closes a reason opened with {@link #reason}. */
    public interface Reason extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Starts tracing the work this thread does until the scope is closed. Close it in a finally
     * block (try-with-resources): a thread left traced would report another request's work.
     */
    public static Scope start(final TraceListener listener) {
        final Trace previous = CURRENT.get();
        final Trace trace = new Trace(listener);
        CURRENT.set(trace);
        Progress.start();
        ACTIVE.add(trace);
        return new Scope() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                ACTIVE.remove(trace);
                try {
                    trace.finish();
                } finally {
                    if (previous == null) {
                        CURRENT.remove();
                    } else {
                        CURRENT.set(previous);
                    }
                }
            }
        };
    }

    /** Asks every running trace, about once a second, to report its long scans. Started on first use. */
    private static final class Progress {
        private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "rya-trace-progress");
            t.setDaemon(true);
            return t;
        });

        static {
            TICKER.scheduleWithFixedDelay(() -> {
                final long now = System.nanoTime();
                for (final Trace trace : ACTIVE) {
                    try {
                        trace.tick(now);
                    } catch (final RuntimeException e) {
                        // Progress is best effort; the trace itself carries on.
                    }
                }
            }, Trace.PROGRESS_INTERVAL_MS, Trace.PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        static void start() {
            // Loading the class starts the thread.
        }
    }

    /** The trace of this thread, or null. */
    public static Trace current() {
        return CURRENT.get();
    }

    /** Whether this thread is traced. */
    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * Records why the scans created until the returned reason is closed happen. Reasons nest:
     * a scan reports all the open ones. The text is computed only when the thread is traced.
     */
    public static Reason reason(final Supplier<String> why) {
        final Trace trace = CURRENT.get();
        if (trace == null) {
            return NO_REASON;
        }
        trace.pushReason(why.get());
        return trace::popReason;
    }

    /** Reports a query plan, if this thread is traced. */
    public static void plan(final String stage, final Supplier<String> tree) {
        final Trace trace = CURRENT.get();
        if (trace != null) {
            trace.plan(stage, tree);
        }
    }

    /** Reports a note about the traced work, if this thread is traced. */
    public static void note(final String kind, final Supplier<String> text) {
        final Trace trace = CURRENT.get();
        if (trace != null) {
            trace.note(kind, text);
        }
    }

    /**
     * A client whose scanners and writers report to the trace of the thread using them. Without
     * a trace they behave exactly like the client's own.
     */
    public static AccumuloClient wrap(final AccumuloClient client) {
        return TracingProxies.client(client);
    }
}
