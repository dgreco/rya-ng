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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.BulkImport;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;

/** {@link RocksBulkImport} through the SPI: the bulk build's files on RocksDB (SCALE-STUDY.md O3). */
class RocksBulkImportTest {
    @TempDir
    Path dir;

    @Test
    void filesOfStatementsAndCountersAreImported() {
        try (RocksStore store = RocksStore.open(dir.resolve("db"))) {
            store.tables().create("t_spo", TableKind.STATEMENTS);
            store.tables().create("t_eval", TableKind.COUNTERS);
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table("t_eval").write(new RowMutation(b("c")).addToCounter(b("s"), b(""), b(""), 2));
            }
            final BulkImport bulk = store.bulk().orElseThrow();
            final String files = dir.resolve("files").toString();
            final BulkImport.Written spo;
            try (BulkImport.BulkFile f = bulk.newFile("t_spo", files, "spo-0", 0)) {
                f.put(b("a"), b(""), b(""), b(""), 7, b(""));
                f.put(b("b"), b("g"), b(""), b("A"), 7, b("v"));
                spo = f.finish();
            }
            assertEquals(2, spo.cells());
            assertArrayEquals(b("a"), spo.firstRow());
            assertArrayEquals(b("b"), spo.lastRow());
            final BulkImport.Written eval;
            try (BulkImport.BulkFile f = bulk.newFile("t_eval", files, "eval-0", 0)) {
                f.counter(b("c"), b("s"), b(""), b(""), 5);
                f.counter(b("d"), b("s"), b(""), b(""), 3);
                eval = f.finish();
            }
            bulk.importFiles("t_spo", List.of(spo));
            bulk.importFiles("t_eval", List.of(eval));
            assertFalse(Files.exists(Path.of(spo.path())), "ingested files are moved into the database");

            assertEquals(List.of("a"), rows(store, "t_spo", Auths.NONE));
            assertEquals(List.of("a", "b"), rows(store, "t_spo", Auths.of("A")));
            try (CellScanner cells = store.scan("t_spo", KeyRange.row(b("b")), ScanSpec.of(Auths.of("A")))) {
                final Cell c = cells.next();
                assertEquals(7, c.timestamp());
                assertEquals("v", new String(c.value(), StandardCharsets.UTF_8));
            }
            assertEquals(7, counter(store, "c"), "2 online, 5 imported");
            assertEquals(3, counter(store, "d"));
        }
    }

    @Test
    void anEmptyFileHasNothingToImport() {
        try (RocksStore store = RocksStore.open(dir.resolve("db"))) {
            store.tables().create("t_spo", TableKind.STATEMENTS);
            try (BulkImport.BulkFile f = store.bulk().orElseThrow().newFile("t_spo", dir.toString(), "empty", 0)) {
                assertNull(f.finish());
            }
            assertFalse(Files.exists(dir.resolve("empty.sst")));
        }
    }

    @Test
    void cellsOutOfOrderOrOfTheWrongKindAreRefused() {
        try (RocksStore store = RocksStore.open(dir.resolve("db"))) {
            store.tables().create("t_spo", TableKind.STATEMENTS);
            store.tables().create("t_eval", TableKind.COUNTERS);
            final BulkImport bulk = store.bulk().orElseThrow();
            try (BulkImport.BulkFile f = bulk.newFile("t_spo", dir.toString(), "order", 0)) {
                f.put(b("b"), b(""), b(""), b(""), 7, b(""));
                assertThrows(RyaDAOException.class, () -> f.put(b("a"), b(""), b(""), b(""), 7, b("")));
                assertThrows(RyaDAOException.class, () -> f.put(b("b"), b(""), b(""), b(""), 7, b("")), "one cell per key");
            }
            assertFalse(Files.exists(dir.resolve("order.sst")), "an abandoned file is deleted");
            try (BulkImport.BulkFile f = bulk.newFile("t_spo", dir.toString(), "kind", 0)) {
                assertThrows(IllegalArgumentException.class, () -> f.counter(b("a"), b("s"), b(""), b(""), 1));
            }
            try (BulkImport.BulkFile f = bulk.newFile("t_eval", dir.toString(), "kind", 0)) {
                assertThrows(IllegalArgumentException.class, () -> f.put(b("a"), b(""), b(""), b(""), 7, b("")));
            }
        }
    }

    private static List<String> rows(final RocksStore store, final String table, final Auths auths) {
        final List<String> rows = new ArrayList<>();
        try (CellScanner cells = store.scan(table, KeyRange.all(), ScanSpec.of(auths))) {
            while (cells.hasNext()) {
                rows.add(new String(cells.next().row(), StandardCharsets.UTF_8));
            }
        }
        return rows;
    }

    private static long counter(final RocksStore store, final String row) {
        try (CellScanner cells = store.scan("t_eval", KeyRange.row(b(row)), ScanSpec.of(Auths.NONE))) {
            return store.counterValue(cells.next().value());
        }
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
