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
package io.github.dgreco.rya.store.trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.ScanSpec;

/**
 * Reports a backend's reads to the trace of the thread that starts them, for backends that call
 * the trace themselves (the Accumulo backend traces through client proxies instead). Costs nothing
 * when the thread isn't traced.
 */
public final class TracedScans {
    private TracedScans() {
    }

    /**
     * Opens a read and reports it: its table, ranges, columns, filters and labels, then the cells
     * it returns and the time spent in it.
     *
     * @param op the backend's name for the operation (for example {@code Scan}, {@code Get})
     * @param filters the server-side filters, readable
     */
    public static CellScanner open(final String op, final String table, final Collection<KeyRange> ranges, final ScanSpec spec,
            final List<String> filters, final Supplier<CellScanner> open) {
        final Trace trace = StoreTrace.current();
        if (trace == null) {
            return open.get();
        }
        final int id = started(trace, op, table, ranges, spec, filters);
        final long t0 = System.nanoTime();
        final CellScanner real;
        try {
            real = open.get();
        } catch (final RuntimeException e) {
            trace.scanEnded(id, 0, System.nanoTime() - t0, "failed");
            throw e;
        }
        final Counting counting = new Counting(trace, id, real, System.nanoTime() - t0);
        trace.track(id, counting);
        return counting;
    }

    /** Reports an operation that reads nothing back (a visible-cell delete): its start, then its end. */
    public static Runnable started(final String op, final String table, final Collection<KeyRange> ranges, final ScanSpec spec,
            final List<String> filters) {
        final Trace trace = StoreTrace.current();
        if (trace == null) {
            return () -> {
            };
        }
        final int id = started(trace, op, table, ranges, spec, filters);
        final long t0 = System.nanoTime();
        return () -> trace.scanEnded(id, -1, System.nanoTime() - t0, "deleted");
    }

    private static int started(final Trace trace, final String op, final String table, final Collection<KeyRange> ranges,
            final ScanSpec spec, final List<String> filters) {
        final List<String> shown = new ArrayList<>();
        for (final KeyRange r : ranges) {
            if (shown.size() >= Trace.MAX_RANGES) {
                break;
            }
            shown.add(TraceText.range(r));
        }
        final List<String> columns = new ArrayList<>();
        for (final ScanSpec.Column c : spec.columns()) {
            final String cq = TraceText.bytes(c.qualifier());
            columns.add("column " + TraceText.bytes(c.family()) + " : " + (cq.isEmpty() ? "(empty qualifier)" : cq));
        }
        for (final byte[] f : spec.families()) {
            columns.add("family " + TraceText.bytes(f));
        }
        return trace.scanStarted(op, table, shown, ranges.size(), columns, List.copyOf(filters), spec.auths().toString(),
                trace.reason());
    }

    /** Counts what a read returns and the time spent in it, and reports its end once. */
    private static final class Counting implements CellScanner, Trace.Live {
        private final Trace trace;
        private final int id;
        private final CellScanner real;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile long entries;
        private volatile long nanos;
        private volatile long callStart;
        private volatile byte[] lastRow;

        Counting(final Trace trace, final int id, final CellScanner real, final long openNanos) {
            this.trace = trace;
            this.id = id;
            this.real = real;
            this.nanos = openNanos;
        }

        @Override
        public boolean hasNext() {
            final long t0 = System.nanoTime();
            callStart = t0;
            final boolean more;
            try {
                more = real.hasNext();
            } finally {
                nanos += System.nanoTime() - t0;
                callStart = 0;
            }
            if (!more) {
                end("exhausted");
            }
            return more;
        }

        @Override
        public Cell next() {
            final long t0 = System.nanoTime();
            callStart = t0;
            try {
                final Cell c = real.next();
                entries++;
                lastRow = c.row();
                return c;
            } catch (final NoSuchElementException e) {
                end("exhausted");
                throw e;
            } finally {
                nanos += System.nanoTime() - t0;
                callStart = 0;
            }
        }

        @Override
        public void close() {
            try {
                real.close();
            } finally {
                end("closed");
            }
        }

        @Override
        public long entries() {
            return entries;
        }

        @Override
        public long nanos(final long now) {
            final long started = callStart;
            return nanos + (started == 0 ? 0 : Math.max(0, now - started));
        }

        @Override
        public long waitingNanos(final long now) {
            final long started = callStart;
            return started == 0 ? 0 : Math.max(0, now - started);
        }

        @Override
        public String position() {
            final byte[] row = lastRow;
            return row == null ? null : TraceText.bytes(row);
        }

        private void end(final String how) {
            if (ended.compareAndSet(false, true)) {
                trace.scanEnded(id, entries, nanos, how);
            }
        }
    }
}
