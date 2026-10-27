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

import java.util.List;

/**
 * What a trace reports. Times ({@code at}) are milliseconds since the trace started; durations
 * are milliseconds spent inside Accumulo client calls.
 */
public sealed interface TraceEvent {

    /** The event's name in a stream (for example the SSE event type). */
    String type();

    /** Free text about the traced request, such as the query itself. */
    record Note(double at, String kind, String text) implements TraceEvent {
        @Override
        public String type() {
            return "note";
        }
    }

    /** A query plan: {@code stage} is "parsed" or "optimized". */
    record Plan(double at, String stage, String tree) implements TraceEvent {
        @Override
        public String type() {
            return "plan";
        }
    }

    /**
     * A scan starts: a {@code Scanner} or {@code BatchScanner} iterator is opened, or a
     * {@code BatchDeleter} deletes.
     *
     * @param ranges the first ranges, readable; {@code rangeCount} says how many there are
     * @param reason why rya-ng scans, innermost last
     */
    record ScanStart(double at, int id, String op, String table, List<String> ranges, int rangeCount,
            List<String> columns, List<String> iterators, String auths, String reason) implements TraceEvent {
        @Override
        public String type() {
            return "scan";
        }
    }

    /**
     * A scan ends.
     *
     * @param entries key/values returned, or -1 when Accumulo doesn't say (deletes)
     * @param how "exhausted", "closed", "deleted" or "open" (still open when the trace ended)
     */
    record ScanEnd(double at, int id, long entries, double ms, String how) implements TraceEvent {
        @Override
        public String type() {
            return "scan-end";
        }
    }

    /** Buffered writes are sent to the tablet servers: what each table received since the last flush. */
    record Flush(double at, double ms, List<TableWrites> tables) implements TraceEvent {
        @Override
        public String type() {
            return "flush";
        }
    }

    /** Writes to one table. {@code cells} counts puts and deletes; {@code deletes} the deletes alone. */
    record TableWrites(String table, long mutations, long cells, long deletes, long bytes) {
    }

    /** Totals for one table over the whole trace. */
    record TableTotals(String table, long scans, long ranges, long entries, double ms, long mutations, long cells,
            long deletes, long bytes) {
    }

    /**
     * The end of a trace: totals over every scan and write, including those whose own events were
     * left out because the trace was too long ({@code omitted} events).
     */
    record Summary(double at, long scans, long entries, double scanMs, long mutations, List<TableTotals> tables,
            long omitted) implements TraceEvent {
        @Override
        public String type() {
            return "summary";
        }
    }
}
