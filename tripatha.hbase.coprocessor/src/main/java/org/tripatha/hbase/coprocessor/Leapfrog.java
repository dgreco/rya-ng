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
package org.tripatha.hbase.coprocessor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * One page of a shard row's intersection (SCALE-STUDY.md O23, D14): the subjects found in every
 * list, each with the product of its cells per list, as Accumulo's {@code EntityIntersectingIterator}
 * and RocksDB's {@code RocksIntersection} give them. Each list is sought to the largest subject the
 * others are at, until they agree, so a long list costs about as many seeks as the shortest has
 * cells.
 *
 * <p>A shard row holds about 200M triples' postings, more than one call may read: a page ends after
 * {@code maxMatches} matches or once its {@link Budget} is spent, always between two subjects, and
 * says where the next one starts. Every page makes progress: until it has, a spent budget is
 * extended instead.
 */
final class Leapfrog {
    /** Bounds are subjects, and no subject holds a {@code 00} or {@code 01} byte. */
    private static final byte AFTER = 2;

    private Leapfrog() {
    }

    /** Receives a page's matches, in subject order. */
    interface Sink {
        void match(byte[] subject, long rows);
    }

    /**
     * Runs one page over lists positioned at {@code from} or after.
     *
     * @param from the page's first subject bound (empty: the row's start)
     * @return where the next page starts, or null when the row is done
     */
    static byte[] page(final List<? extends Postings> lists, final byte[] from, final int maxMatches, final Budget budget,
            final Sink sink) throws IOException {
        if (lists.isEmpty()) {
            return null;
        }
        byte[] target = from;
        int matches = 0;
        while (true) {
            // Bring every list to the target, raising it to the first head beyond it, until they agree.
            boolean agreed;
            do {
                agreed = true;
                for (final Postings list : lists) {
                    byte[] head = at(list, target);
                    while (head == null && list.paused()) {
                        // No subject below the target matches, nor any this list skipped.
                        final byte[] bound = max(target, list.frontier());
                        if (Arrays.compareUnsigned(bound, from) > 0) {
                            return bound;
                        }
                        budget.extend();
                        list.resume();
                        head = at(list, target);
                    }
                    if (head == null) {
                        return null;
                    }
                    if (Arrays.compareUnsigned(head, target) > 0) {
                        target = head;
                        agreed = false;
                    }
                }
            } while (!agreed);
            // A match: count its cells in each list. A page never ends inside a subject.
            long rows = 1;
            for (final Postings list : lists) {
                long n = 0;
                while (true) {
                    final byte[] head = list.head();
                    if (head == null && list.paused()) {
                        budget.extend();
                        list.resume();
                        continue;
                    }
                    if (head == null || !Arrays.equals(head, target)) {
                        break;
                    }
                    n++;
                    list.next();
                }
                rows = Math.multiplyExact(rows, n);
            }
            sink.match(target, rows);
            target = after(target);
            if (++matches >= maxMatches || budget.spent()) {
                return target;
            }
        }
    }

    /** The list's head once at or after the bound. */
    private static byte[] at(final Postings list, final byte[] bound) throws IOException {
        final byte[] head = list.head();
        if (head == null || Arrays.compareUnsigned(head, bound) >= 0) {
            return head;
        }
        list.seek(bound);
        return list.head();
    }

    /** The lowest bound above a subject: {@code subject 02}, since subjects hold no {@code 00} or {@code 01}. */
    static byte[] after(final byte[] subject) {
        final byte[] out = Arrays.copyOf(subject, subject.length + 1);
        out[subject.length] = AFTER;
        return out;
    }

    private static byte[] max(final byte[] a, final byte[] b) {
        return Arrays.compareUnsigned(a, b) >= 0 ? a : b;
    }

    /**
     * A page's allowance: cells read (each one examined, shown or not, including those skipped by
     * labels or the time range) and time. Readers ask before each cell; once it is spent they stop
     * there, and {@link #extend} grants another allowance of the same size.
     */
    static final class Budget {
        /** How often the clock is read, in cells. */
        private static final int CLOCK = 256;

        private final long allowance;
        private final long nanos;
        private long cells;
        private long limit;
        private long deadline;
        /** The cells read when the allowance was last extended; -1 before. */
        private long extendedAt = -1;

        Budget(final long maxCells, final long maxMillis) {
            this.allowance = Math.max(1, maxCells);
            this.nanos = Math.max(0, maxMillis) * 1_000_000L;
            this.limit = allowance;
            this.deadline = System.nanoTime() + nanos;
        }

        /** Counts a cell about to be read; false when the allowance is spent: then it isn't read. */
        boolean spend() {
            if (cells >= limit || (cells % CLOCK == CLOCK - 1 && System.nanoTime() - deadline > 0)) {
                limit = cells;
                return false;
            }
            cells++;
            return true;
        }

        boolean spent() {
            return cells >= limit || System.nanoTime() - deadline > 0;
        }

        /**
         * Another allowance, for a page that hasn't progressed yet. Each one reads a cell at least,
         * so two in a row without one would loop forever: a bug, failed instead of spinning.
         */
        void extend() {
            if (cells == extendedAt) {
                throw new IllegalStateException("A paused list read nothing on its last allowance (" + cells + " cells)");
            }
            extendedAt = cells;
            limit = cells + allowance;
            deadline = System.nanoTime() + nanos;
        }

        long cells() {
            return cells;
        }
    }
}
