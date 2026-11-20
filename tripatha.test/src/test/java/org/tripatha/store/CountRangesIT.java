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
package org.tripatha.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RangeCounts;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;

/**
 * {@code Store.countRanges} (SCALE-STUDY.md O26) counts what a scan of each range would return,
 * on every backend: under labels (held, not held, none), graphs and a time range, for ranges with
 * many cells, none, or crossing the table's splits, and with a scan batch of two cells, which makes
 * a server resume a range more than once.
 */
class CountRangesIT extends StoreITBase {
    private static final byte[] DEFAULT_GRAPH = new byte[0];
    private static final byte[] G1 = b("urn:g1");
    private static final int ROWS = 60;

    private String table;

    @BeforeEach
    void write() {
        grantAuthorizations("A", "B");
        table = getTablePrefix() + "spo";
        getStore().tables().create(table, TableKind.STATEMENTS);
        final SortedSet<byte[]> splits = new TreeSet<>(Arrays::compareUnsigned);
        splits.add(b(row(20)));
        splits.add(b(row(40) + "\u0000p"));
        getStore().tables().addSplits(table, splits);
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            for (int i = 0; i < ROWS; i++) {
                // Row i holds i % 7 statements, in two graphs, under three labels, at times 1 to 9.
                for (int j = 0; j < i % 7; j++) {
                    final RowMutation m = new RowMutation(b(row(i) + "\u0000p\u0000o" + j + "\u0001\u0003"));
                    m.put(j % 3 == 0 ? G1 : DEFAULT_GRAPH, b(""), b(new String[] {"", "A", "A&B"}[j % 3]), 1 + (i + j) % 9, b(""));
                    if (j == 1) {
                        m.put(G1, b(""), b(""), 5, b("")); // the same statement in a second graph
                        m.put(DEFAULT_GRAPH, b(""), b("B"), 5, b("")); // and under a second label
                    }
                    w.table(table).write(m);
                }
            }
        }
    }

    @Test
    void countsAreWhatScansReturn() {
        // Disjoint, as countRanges requires.
        final List<KeyRange> ranges = new ArrayList<>();
        for (int i = 0; i <= 12; i += 3) {
            ranges.add(KeyRange.closed(b(row(i) + "\u0000"), b(row(i) + "\u0000\u00ff")));
        }
        ranges.add(KeyRange.closed(b(row(13)), b(row(47) + "\u0000\u00ff"))); // across both splits
        ranges.add(KeyRange.prefix(b(row(50))));
        ranges.add(KeyRange.closed(b("s99"), b("s99\u00ff"))); // nothing there
        ranges.add(KeyRange.row(b(row(58) + "\u0000p\u0000o1\u0001\u0003")));
        for (final ScanSpec spec : List.of(ScanSpec.of(Auths.NONE), ScanSpec.of(Auths.of("A")), ScanSpec.of(Auths.of("A", "B")),
                ScanSpec.of(Auths.ALL_HELD), ScanSpec.builder(Auths.of("A", "B")).family(G1).build(),
                ScanSpec.builder(Auths.of("A", "B")).family(DEFAULT_GRAPH).build(),
                ScanSpec.builder(Auths.of("A")).timestamps(3L, 6L).build(),
                ScanSpec.builder(Auths.of("A", "B")).batchSize(2).build())) {
            final long[] expected = scanned(ranges, spec);
            assertArrayEquals(expected, getStore().countRanges(table, ranges, spec), spec.toString());
            assertArrayEquals(expected, RangeCounts.byScanning(getStore(), table, ranges, spec), "by scanning, " + spec);
        }
    }

    @Test
    void noRangesNoCounts() {
        assertEquals(0, getStore().countRanges(table, List.of(), ScanSpec.of(Auths.NONE)).length);
    }

    @Test
    void theLocatorFindsTheRangeOfARow() {
        final List<KeyRange> ranges = List.of(KeyRange.rows(b("b"), false, b("c"), true), KeyRange.closed(b("a"), b("b")),
                KeyRange.prefix(b("d")), KeyRange.atLeast(b("x")));
        final RangeCounts.Locator locator = new RangeCounts.Locator(ranges);
        assertEquals(1, locator.indexOf(b("a")));
        assertEquals(1, locator.indexOf(b("b")), "b ends the second range and is left out of the first");
        assertEquals(0, locator.indexOf(b("b0")));
        assertEquals(0, locator.indexOf(b("c")));
        assertEquals(-1, locator.indexOf(b("c0")));
        assertEquals(2, locator.indexOf(b("d")));
        assertEquals(2, locator.indexOf(b("dzz")));
        assertEquals(-1, locator.indexOf(b("e")));
        assertEquals(3, locator.indexOf(b("zz")));
        assertEquals(-1, locator.indexOf(b("")));
    }

    /** Each range scanned on its own, its cells counted. */
    private long[] scanned(final List<KeyRange> ranges, final ScanSpec spec) {
        final long[] counts = new long[ranges.size()];
        for (int i = 0; i < counts.length; i++) {
            try (CellScanner cells = getStore().scan(table, ranges.get(i), spec)) {
                while (cells.hasNext()) {
                    cells.next();
                    counts[i]++;
                }
            }
        }
        return counts;
    }

    private static String row(final int i) {
        return String.format("s%02d", i);
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
