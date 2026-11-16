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
package org.tripatha.rocksdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.KeyRange;

/**
 * The key encoding sorts like Accumulo's keys (row, family, qualifier, visibility, each as
 * unsigned bytes), decodes back to the cell, and a range's bounds hold exactly its cells.
 */
class RocksCodecTest {
    private static final Comparator<Cell> ACCUMULO_ORDER = Comparator.comparing(Cell::row, Arrays::compareUnsigned)
            .thenComparing(Cell::family, Arrays::compareUnsigned).thenComparing(Cell::qualifier, Arrays::compareUnsigned)
            .thenComparing(Cell::visibility, Arrays::compareUnsigned);

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Rows with the bytes that matter to the encoding: 00, 01, 02, FF, and prefixes of each other. */
    private static List<Cell> cells() {
        final byte[][] rows = {b(""), b("a"), b("a\u0000"), b("a\u0000\u0000"), b("a\u0000\u0001"), b("a\u0000b"), b("a\u0001"),
            b("a\u0002"), b("ab"), new byte[] {'a', (byte) 0xff}, new byte[] {0}, new byte[] {0, 1}, new byte[] {0, (byte) 0xff},
            new byte[] {(byte) 0xff}, new byte[] {(byte) 0xff, (byte) 0xff}, b("b")};
        final String[] families = {"", "g", "g1", "g\u0001", "h"};
        final String[] qualifiers = {"", "q", "q\u0001", "qq"};
        final String[] labels = {"", "A", "A&B", "B"};
        final List<Cell> cells = new ArrayList<>();
        long ts = 0;
        for (final byte[] row : rows) {
            for (final String f : families) {
                for (final String q : qualifiers) {
                    for (final String l : labels) {
                        cells.add(new Cell(row, b(f), b(q), b(l), ++ts, b("v" + ts)));
                    }
                }
            }
        }
        final Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            final byte[] row = new byte[random.nextInt(6)];
            for (int j = 0; j < row.length; j++) {
                row[j] = (byte) new int[] {0, 1, 2, 'a', 0xfe, 0xff}[random.nextInt(6)];
            }
            cells.add(new Cell(row, b(families[random.nextInt(families.length)]), b(qualifiers[random.nextInt(qualifiers.length)]),
                    b(labels[random.nextInt(labels.length)]), ++ts, b("")));
        }
        return cells;
    }

    private static byte[] key(final Cell c) {
        return RocksCodec.key(c.row(), c.family(), c.qualifier(), c.visibility());
    }

    @Test
    void keysSortLikeAccumuloKeys() {
        final List<Cell> cells = cells();
        final Random random = new Random(11);
        for (int i = 0; i < 200_000; i++) {
            final Cell x = cells.get(random.nextInt(cells.size()));
            final Cell y = cells.get(random.nextInt(cells.size()));
            assertEquals(Integer.signum(ACCUMULO_ORDER.compare(x, y)), Integer.signum(Arrays.compareUnsigned(key(x), key(y))),
                    () -> Arrays.toString(x.row()) + " and " + Arrays.toString(y.row()));
        }
    }

    @Test
    void keysAndValuesDecodeBack() {
        for (final Cell c : cells()) {
            final Cell back = RocksCodec.cell(key(c), RocksCodec.value(c.timestamp(), c.value()), false);
            assertArrayEquals(c.row(), back.row());
            assertArrayEquals(c.family(), back.family());
            assertArrayEquals(c.qualifier(), back.qualifier());
            assertArrayEquals(c.visibility(), back.visibility());
            assertEquals(c.timestamp(), back.timestamp());
            assertArrayEquals(c.value(), back.value());
        }
        final Cell counter = RocksCodec.cell(RocksCodec.key(b("r"), b("f"), b("q"), b("")), RocksCodec.counter(-3), true);
        assertEquals(0, counter.timestamp());
        assertEquals(-3, RocksCodec.counter(counter.value()));
        assertEquals(Long.MIN_VALUE, RocksCodec.timestamp(RocksCodec.value(Long.MIN_VALUE, b(""))));
    }

    @Test
    void boundsHoldExactlyTheCellsOfARange() {
        final List<Cell> cells = cells();
        final List<KeyRange> ranges = new ArrayList<>(List.of(KeyRange.all(), KeyRange.prefix(b("")), KeyRange.prefix(b("a")),
                KeyRange.prefix(b("a\u0000")), KeyRange.prefix(new byte[] {0}), KeyRange.prefix(new byte[] {(byte) 0xff}),
                KeyRange.prefix(new byte[] {'a', (byte) 0xff}), KeyRange.atLeast(b("a\u0001")),
                KeyRange.rows(null, true, b("a\u0000"), false), KeyRange.rows(b(""), false, null, false),
                KeyRange.cell(b("a\u0000"), b("g"), b("q")), KeyRange.cell(b(""), b(""), b("")), KeyRange.cell(b("a"), b("g"), b(""))));
        for (final Cell start : cells.subList(0, 40)) {
            ranges.add(KeyRange.row(start.row()));
            for (final Cell end : cells.subList(300, 320)) {
                for (final boolean[] inclusive : new boolean[][] {{true, true}, {true, false}, {false, true}, {false, false}}) {
                    ranges.add(KeyRange.rows(start.row(), inclusive[0], end.row(), inclusive[1]));
                }
            }
        }
        for (final KeyRange range : ranges) {
            final byte[] lower = RocksCodec.lowerBound(range);
            final byte[] upper = RocksCodec.upperBound(range);
            for (final Cell c : cells) {
                final boolean expected = range.containsRow(c.row()) && (range.kind() != KeyRange.Kind.CELL
                        || (Arrays.equals(range.family(), c.family()) && Arrays.equals(range.qualifier(), c.qualifier())));
                final byte[] key = key(c);
                final boolean actual = Arrays.compareUnsigned(key, lower) >= 0 && (upper == null || Arrays.compareUnsigned(key, upper) < 0);
                assertEquals(expected, actual, () -> range + " and the row " + Arrays.toString(c.row()));
            }
        }
    }

    @Test
    void familiesAndQualifiersCannotHoldAZeroByte() {
        assertThrows(IllegalArgumentException.class, () -> RocksCodec.key(b("r"), b("f\u0000"), b("q"), b("")));
        assertThrows(IllegalArgumentException.class, () -> RocksCodec.key(b("r"), b("f"), b("\u0000"), b("")));
        assertTrue(RocksCodec.key(b("r\u0000"), b("f"), b("q"), b("")).length > 0);
    }
}
