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
package org.tripatha.spark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The cells a sample of the input makes, per table: their bytes (row, columns, label, timestamp
 * and value, uncompressed, as {@code SplitPlanner} counts them) and a random sample of their
 * rows, for the split points and the files' boundaries.
 */
final class Sample implements CellMaker.Sink {
    /** The rows kept per table. */
    static final int ROWS = 200_000;

    private final Map<String, long[]> bytes = new LinkedHashMap<>();
    private final Map<String, long[]> seen = new LinkedHashMap<>();
    private final Map<String, List<byte[]>> rows = new LinkedHashMap<>();
    private final SplittableRandom random = new SplittableRandom(0x5A3F_1E5L);
    /** The input over the sample: the sampled bytes times this are the whole input's. */
    double scale = 1;

    Sample(final List<String> tables) {
        for (final String t : tables) {
            bytes.put(t, new long[1]);
            seen.put(t, new long[1]);
            rows.put(t, new ArrayList<>());
        }
    }

    @Override
    public void put(final String table, final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility,
            final long timestamp, final byte[] value) {
        add(table, row, (long) row.length + family.length + qualifier.length + visibility.length + Long.BYTES + value.length);
    }

    @Override
    public void counter(final String table, final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility,
            final long delta) {
        add(table, row, (long) row.length + family.length + qualifier.length + visibility.length + 2L * Long.BYTES);
    }

    private void add(final String table, final byte[] row, final long cell) {
        final long[] b = bytes.get(table);
        if (b == null) {
            return;
        }
        b[0] += cell;
        // Reservoir sampling: each cell's row is kept with the same chance.
        final long n = ++seen.get(table)[0];
        final List<byte[]> kept = rows.get(table);
        if (kept.size() < ROWS) {
            kept.add(row);
        } else {
            final long j = random.nextLong(n);
            if (j < ROWS) {
                kept.set((int) j, row);
            }
        }
    }

    /** A table's estimated bytes for the whole input. */
    double bytes(final String table) {
        return bytes.getOrDefault(table, new long[1])[0] * scale;
    }

    /** The sampled rows of a table, sorted as unsigned bytes. */
    List<byte[]> sortedRows(final String table) {
        final List<byte[]> r = new ArrayList<>(rows.getOrDefault(table, List.of()));
        r.sort(Arrays::compareUnsigned);
        return r;
    }
}
