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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.TableAdmin;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;

/**
 * The table operations a load at 1T relies on (SCALE-STUDY.md O2): many split points added at
 * once, and the size at which the backend splits a table on its own.
 */
class TableAdminIT extends StoreITBase {
    private static final int ROWS = 400;
    private static final int POINTS = 40;

    /** As prepare splits a table before a load: many points at once, on an empty table. */
    @Test
    void manySplitPointsAreAddedAtOnceBeforeALoad() {
        final String table = getTablePrefix() + "splits";
        final TableAdmin tables = getStore().tables();
        tables.create(table, TableKind.STATEMENTS);
        final SortedSet<byte[]> points = points(POINTS);
        final long t0 = System.nanoTime();
        tables.addSplits(table, points);
        final long seconds = (System.nanoTime() - t0) / 1_000_000_000L;
        write(table, 0, ROWS);
        if (onBackend("rocksdb")) {
            assertEquals(0, tables.splitCount(table), "RocksDB doesn't split: the points are hints");
            assertEquals(POINTS, tables.splitRows(table, 1_000).size());
        } else {
            assertEquals(POINTS, tables.splitCount(table));
            assertTrue(seconds < 120, POINTS + " points took " + seconds + " s: they should take a few rounds");
            // Asking again for points that are already boundaries changes nothing (a second prepare).
            tables.addSplits(table, points);
            assertEquals(POINTS, tables.splitCount(table));
        }
        assertEquals(ROWS, count(table));
    }

    /** A table that already holds data can be split too (on HBase its new regions need a compaction first). */
    @Test
    void aTableWithDataIsSplitAndKeepsItsData() {
        final String table = getTablePrefix() + "full";
        final TableAdmin tables = getStore().tables();
        tables.create(table, TableKind.STATEMENTS);
        write(table, 0, ROWS / 2);
        final SortedSet<byte[]> points = points(3);
        tables.addSplits(table, points);
        write(table, ROWS / 2, ROWS);
        if (!onBackend("rocksdb")) {
            assertEquals(3, tables.splitCount(table));
        }
        assertEquals(ROWS, count(table), "every row written before and after the splits");
    }

    private static SortedSet<byte[]> points(final int n) {
        final SortedSet<byte[]> points = new TreeSet<>(Arrays::compareUnsigned);
        for (int i = 1; i <= n; i++) {
            points.add(row(i * ROWS / (n + 1)));
        }
        return points;
    }

    private int count(final String table) {
        int seen = 0;
        try (CellScanner cells = getStore().scan(table, KeyRange.all(), ScanSpec.of(Auths.NONE))) {
            while (cells.hasNext()) {
                cells.next();
                seen++;
            }
        }
        return seen;
    }

    @Test
    void theTargetSizeIsWhereTheBackendSplitsOnItsOwn() {
        final String table = getTablePrefix() + "target";
        final TableAdmin tables = getStore().tables();
        tables.create(table, TableKind.STATEMENTS);
        final long size = 5L << 30;
        tables.setTargetSize(table, size);
        if (onBackend("rocksdb")) {
            assertEquals(-1, tables.targetSize(table), "RocksDB doesn't split");
        } else {
            assertEquals(size, tables.targetSize(table));
            tables.setTargetSize(table, size);
            assertEquals(size, tables.targetSize(table));
        }
        write(table, 0, 10);
        assertTrue(tables.exists(table));
    }

    private void write(final String table, final int from, final int to) {
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            for (int i = from; i < to; i++) {
                w.table(table).write(new RowMutation(row(i)).put(b("f"), b("q"), new byte[0], b("v" + i)));
            }
        }
    }

    private static byte[] row(final int i) {
        return b(String.format("r%05d", i));
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
