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
package org.tripatha.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

import org.junit.jupiter.api.Test;

/** The split points a rebuild plans for its index tables from the sorted rows' samples. */
class IndexRebuildTest {
    private static byte[] row(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> text(final SortedSet<byte[]> points) {
        final List<String> out = new ArrayList<>();
        points.forEach(p -> out.add(new String(p, StandardCharsets.UTF_8)));
        return out;
    }

    @Test
    void evenWeightsCutEvenParts() {
        final List<IndexRebuild.Weighted> rows = new ArrayList<>();
        for (char c = 'a'; c <= 'p'; c++) {
            rows.add(new IndexRebuild.Weighted(row("row-" + c), 1));
        }
        // 16 rows, 3 points: after the 4th, 8th and 12th row.
        assertEquals(List.of("row-e", "row-i", "row-m"), text(IndexRebuild.quantiles(rows, 3)));
    }

    @Test
    void aHeavyRowTakesItsShare() {
        final List<IndexRebuild.Weighted> rows = new ArrayList<>();
        for (char c = 'a'; c <= 'j'; c++) {
            // Sampled from a range that wrote 100 times as many cells: each of its rows stands for 100.
            rows.add(new IndexRebuild.Weighted(row("row-" + c), c == 'b' ? 1000 : 1));
        }
        final List<String> points = text(IndexRebuild.quantiles(rows, 3));
        assertEquals(List.of("row-c"), points, "every quantile falls on the heavy row: one point after it");
    }

    @Test
    void separatorsAreShort() {
        final List<IndexRebuild.Weighted> rows = List.of(new IndexRebuild.Weighted(row("apple"), 1),
                new IndexRebuild.Weighted(row("apricot"), 1), new IndexRebuild.Weighted(row("banana"), 1),
                new IndexRebuild.Weighted(row("blueberry"), 1));
        assertEquals(List.of("b"), text(IndexRebuild.quantiles(rows, 1)), "the shortest prefix after the row before");
    }

    @Test
    void tooFewRowsGiveNoPoints() {
        assertTrue(IndexRebuild.quantiles(List.of(new IndexRebuild.Weighted(row("only"), 5)), 4).isEmpty());
        assertTrue(IndexRebuild.quantiles(List.of(), 4).isEmpty());
    }
}
