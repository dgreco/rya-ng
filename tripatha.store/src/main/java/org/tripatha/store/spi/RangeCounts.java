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
package org.tripatha.store.spi;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Counting the cells of disjoint ranges ({@link Store#countRanges}). */
public final class RangeCounts {
    private RangeCounts() {
    }

    /** The default count: one batch scan of the ranges, each cell counted in the range holding its row. */
    public static long[] byScanning(final Store store, final String table, final List<KeyRange> ranges, final ScanSpec spec) {
        final long[] counts = new long[ranges.size()];
        if (ranges.isEmpty()) {
            return counts;
        }
        final Locator locator = new Locator(ranges);
        try (CellScanner cells = store.batchScan(table, ranges, spec)) {
            while (cells.hasNext()) {
                final int i = locator.indexOf(cells.next().row());
                if (i >= 0) {
                    counts[i]++;
                }
            }
        }
        return counts;
    }

    /** Finds which of some disjoint ranges holds a row. */
    public static final class Locator {
        private static final Comparator<byte[]> STARTS = (a, b) -> a == null ? (b == null ? 0 : -1)
                : b == null ? 1 : Arrays.compareUnsigned(a, b);

        private final List<KeyRange> ranges;
        /** The ranges' indexes, by their start. */
        private final Integer[] byStart;

        public Locator(final List<KeyRange> ranges) {
            this.ranges = ranges;
            this.byStart = new Integer[ranges.size()];
            for (int i = 0; i < byStart.length; i++) {
                byStart[i] = i;
            }
            Arrays.sort(byStart, (a, b) -> STARTS.compare(ranges.get(a).start(), ranges.get(b).start()));
        }

        /** The index of the range holding this row, or -1 for none. */
        public int indexOf(final byte[] row) {
            // The last range starting at or before the row, or the one before it when that one starts
            // at the row but leaves it out.
            int lo = 0;
            int hi = byStart.length - 1;
            int found = -1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (STARTS.compare(ranges.get(byStart[mid]).start(), row) <= 0) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            for (int k = found; k >= 0 && k >= found - 1; k--) {
                if (ranges.get(byStart[k]).containsRow(row)) {
                    return byStart[k];
                }
            }
            return -1;
        }
    }
}
