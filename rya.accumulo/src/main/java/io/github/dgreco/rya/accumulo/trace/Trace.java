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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Mutation;

/**
 * One traced request: its listener, its open reasons, and running totals. Thread-safe: scanners
 * capture their trace when they are created, and may be closed from another thread.
 */
public final class Trace {
    /** Scan and flush events reported one by one; after that only the totals grow. */
    public static final int MAX_DETAILED_EVENTS = 1000;
    /** Ranges listed in one scan event; the event still says how many there are. */
    public static final int MAX_RANGES = 10;
    /** How often running scans report progress, and how long one runs before it does. */
    public static final long PROGRESS_INTERVAL_MS = 1000;

    /** What a running scan has done so far. Read by the progress thread, so thread-safe. */
    interface Live {
        long entries();

        /** Time inside Accumulo calls, including a call in progress. */
        long nanos(long now);

        /** How long the call in progress has been waiting; 0 when no call is in progress. */
        long waitingNanos(long now);

        /** The last row read, readable; null before the first. */
        String position();
    }

    private final TraceListener listener;
    private final long startNanos = System.nanoTime();
    private final Deque<String> reasons = new ArrayDeque<>();
    private final Map<String, Totals> totals = new TreeMap<>();
    private final Map<String, Pending> pending = new TreeMap<>();
    private final Set<Integer> detailed = new HashSet<>();
    private final Map<Integer, OpenScan> open = new TreeMap<>();
    private int nextScanId = 1;
    private int detailedEvents;
    private long omitted;
    private boolean finished;

    private static final class Totals {
        long scans;
        long ranges;
        long entries;
        long nanos;
        long mutations;
        long cells;
        long deletes;
        long bytes;
    }

    private static final class Pending {
        long mutations;
        long cells;
        long deletes;
        long bytes;
    }

    private static final class OpenScan {
        final String table;
        final long openedNanos = System.nanoTime();
        Live live;

        OpenScan(final String table) {
            this.table = table;
        }
    }

    Trace(final TraceListener listener) {
        this.listener = listener;
    }

    /** Milliseconds since the trace started. */
    synchronized double now() {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    synchronized void pushReason(final String reason) {
        reasons.addLast(reason);
    }

    synchronized void popReason() {
        reasons.pollLast();
    }

    /** The open reasons, outermost first, joined with " › "; empty when there are none. */
    synchronized String reason() {
        return String.join(" › ", reasons);
    }

    synchronized void emit(final TraceEvent event) {
        try {
            listener.event(event);
        } catch (final RuntimeException e) {
            // A broken listener must not break the traced work.
        }
    }

    void note(final String kind, final Supplier<String> text) {
        emit(new TraceEvent.Note(now(), kind, text.get()));
    }

    void plan(final String stage, final Supplier<String> tree) {
        emit(new TraceEvent.Plan(now(), stage, tree.get()));
    }

    synchronized int scanStarted(final String op, final String table, final List<String> ranges, final int rangeCount,
            final List<String> columns, final List<String> iterators, final String auths, final String reason) {
        final int id = nextScanId++;
        final Totals t = totals.computeIfAbsent(table, k -> new Totals());
        t.scans++;
        t.ranges += rangeCount;
        open.put(id, new OpenScan(table));
        if (detailedEvents < MAX_DETAILED_EVENTS) {
            detailedEvents++;
            detailed.add(id);
            emit(new TraceEvent.ScanStart(now(), id, op, table, ranges, rangeCount, columns, iterators, auths, reason));
        } else {
            omitted++;
        }
        return id;
    }

    synchronized void scanEnded(final int id, final long entries, final long nanos, final String how) {
        final OpenScan scan = open.remove(id);
        if (scan == null) {
            return;
        }
        final Totals t = totals.computeIfAbsent(scan.table, k -> new Totals());
        t.entries += Math.max(0, entries);
        t.nanos += nanos;
        if (detailed.remove(id)) {
            emit(new TraceEvent.ScanEnd(now(), id, entries, nanos / 1e6, how));
        }
    }

    /** Lets the progress reports see what scan {@code id} has read so far. */
    synchronized void track(final int id, final Live live) {
        final OpenScan scan = open.get(id);
        if (scan != null) {
            scan.live = live;
        }
    }

    /**
     * Reports the scans that have run for at least {@link #PROGRESS_INTERVAL_MS}, if any. Called
     * about once a second by {@link AccumuloTrace}'s progress thread.
     */
    synchronized void tick(final long now) {
        if (finished) {
            return;
        }
        final List<TraceEvent.ScanProgress> running = new ArrayList<>();
        long inFlight = 0;
        for (final Map.Entry<Integer, OpenScan> e : open.entrySet()) {
            final OpenScan scan = e.getValue();
            if (scan.live == null) {
                continue;
            }
            final long entries = scan.live.entries();
            inFlight += entries;
            if (detailed.contains(e.getKey()) && now - scan.openedNanos >= PROGRESS_INTERVAL_MS * 1_000_000) {
                running.add(new TraceEvent.ScanProgress(e.getKey(), entries, scan.live.nanos(now) / 1e6,
                        scan.live.waitingNanos(now) / 1e6, scan.live.position()));
            }
        }
        if (running.isEmpty()) {
            return;
        }
        long done = 0;
        for (final Totals t : totals.values()) {
            done += t.entries;
        }
        emit(new TraceEvent.Progress((now - startNanos) / 1e6, running, open.size(), nextScanId - 1L, done + inFlight));
    }

    synchronized void wrote(final String table, final Mutation m) {
        final Pending p = pending.computeIfAbsent(table, k -> new Pending());
        p.mutations++;
        for (final ColumnUpdate u : m.getUpdates()) {
            p.cells++;
            if (u.isDeleted()) {
                p.deletes++;
            }
        }
        p.bytes += m.numBytes();
    }

    synchronized void flushed(final long nanos) {
        if (pending.isEmpty()) {
            return;
        }
        final List<TraceEvent.TableWrites> tables = new ArrayList<>();
        for (final Map.Entry<String, Pending> e : pending.entrySet()) {
            final Pending p = e.getValue();
            tables.add(new TraceEvent.TableWrites(e.getKey(), p.mutations, p.cells, p.deletes, p.bytes));
            final Totals t = totals.computeIfAbsent(e.getKey(), k -> new Totals());
            t.mutations += p.mutations;
            t.cells += p.cells;
            t.deletes += p.deletes;
            t.bytes += p.bytes;
        }
        pending.clear();
        if (detailedEvents < MAX_DETAILED_EVENTS) {
            detailedEvents++;
            emit(new TraceEvent.Flush(now(), nanos / 1e6, tables));
        } else {
            omitted++;
        }
    }

    /** Ends the scans still open, counts unflushed writes, and reports the totals. */
    synchronized void finish() {
        finished = true;
        for (final Integer id : new ArrayList<>(open.keySet())) {
            scanEnded(id, -1, 0, "open");
        }
        for (final Map.Entry<String, Pending> e : pending.entrySet()) {
            final Totals t = totals.computeIfAbsent(e.getKey(), k -> new Totals());
            t.mutations += e.getValue().mutations;
            t.cells += e.getValue().cells;
            t.deletes += e.getValue().deletes;
            t.bytes += e.getValue().bytes;
        }
        pending.clear();
        long scans = 0;
        long entries = 0;
        long nanos = 0;
        long mutations = 0;
        final List<TraceEvent.TableTotals> tables = new ArrayList<>();
        for (final Map.Entry<String, Totals> e : totals.entrySet()) {
            final Totals t = e.getValue();
            tables.add(new TraceEvent.TableTotals(e.getKey(), t.scans, t.ranges, t.entries, t.nanos / 1e6, t.mutations,
                    t.cells, t.deletes, t.bytes));
            scans += t.scans;
            entries += t.entries;
            nanos += t.nanos;
            mutations += t.mutations;
        }
        emit(new TraceEvent.Summary(now(), scans, entries, nanos / 1e6, mutations, tables, omitted));
    }
}
