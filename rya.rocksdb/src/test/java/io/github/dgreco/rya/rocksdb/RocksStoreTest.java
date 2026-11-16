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
package io.github.dgreco.rya.rocksdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.spi.Stores;
import io.github.dgreco.rya.store.spi.TableKind;
import io.github.dgreco.rya.store.spi.WriterOptions;

/** What is particular to this backend; the behaviour every backend shares is checked by the parity ITs in rya.test. */
class RocksStoreTest {
    @TempDir
    Path dir;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> read(final Store store, final String table, final KeyRange range, final ScanSpec spec) {
        final List<String> out = new ArrayList<>();
        try (CellScanner cells = store.scan(table, range, spec)) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                out.add(new String(c.row(), StandardCharsets.UTF_8) + "/" + new String(c.visibility(), StandardCharsets.UTF_8) + "@"
                        + c.timestamp() + "=" + new String(c.value(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static List<String> all(final Store store, final String table) {
        return read(store, table, KeyRange.all(), ScanSpec.of(Auths.ALL_HELD));
    }

    @Test
    void theProviderIsFoundAndNeedsADirectory() {
        final Properties settings = new Properties();
        assertThrows(IllegalArgumentException.class, () -> Stores.open("rocksdb", settings));
        settings.setProperty(RocksStoreProvider.PATH, dir.resolve("db").toString());
        try (Store store = Stores.open("rocksdb", settings)) {
            assertEquals("rocksdb", store.backend());
            assertTrue(store.capabilities().labels());
            assertTrue(store.capabilities().serverCombine());
            assertFalse(store.capabilities().scanIterators());
        }
    }

    @Test
    void dataAndTablesSurviveAReopen() {
        try (Store store = RocksStore.open(dir)) {
            assertTrue(store.tables().create("t_spo", TableKind.STATEMENTS));
            assertFalse(store.tables().create("t_spo", TableKind.STATEMENTS));
            store.tables().create("t_eval", TableKind.COUNTERS);
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table("t_spo").write(new RowMutation(b("r")).put(b("g"), b("q"), b("A"), 5, b("v")));
                w.table("t_eval").write(new RowMutation(b("c")).addToCounter(b("f"), b("q"), b(""), 3));
                w.table("t_eval").write(new RowMutation(b("c")).addToCounter(b("f"), b("q"), b(""), -1));
            }
        }
        try (Store store = RocksStore.open(dir)) {
            assertEquals(List.of("t_eval", "t_spo"), store.tables().list());
            assertEquals(List.of("r/A@5=v"), all(store, "t_spo"));
            try (CellScanner cells = store.scan("t_eval", KeyRange.row(b("c")), ScanSpec.of(Auths.NONE))) {
                assertEquals(2, store.counterValue(cells.next().value()));
                assertFalse(cells.hasNext());
            }
            // Still a counter table after the reopen: it refuses a plain value.
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                assertThrows(RyaDAOException.class,
                        () -> w.table("t_eval").write(new RowMutation(b("c")).put(b("f"), b("q"), b(""), 1, b("x"))));
                assertThrows(RyaDAOException.class,
                        () -> w.table("t_spo").write(new RowMutation(b("c")).addToCounter(b("f"), b("q"), b(""), 1)));
            }
        }
    }

    @Test
    void storesOfOneDirectoryShareTheDatabase() {
        try (Store first = RocksStore.open(dir)) {
            first.tables().create("t", TableKind.STATEMENTS);
            try (Store second = RocksStore.open(dir.resolve(".")); CellWriter w = second.newWriter(WriterOptions.DEFAULT)) {
                assertTrue(second.tables().exists("t"));
                w.table("t").write(new RowMutation(b("r")).put(b(""), b(""), b(""), 1, b("v")));
            }
            // The second store is closed; the first still reads what it wrote.
            assertEquals(List.of("r/@1=v"), all(first, "t"));
        }
    }

    @Test
    void aFlushStoresEveryTableOrNone() {
        try (Store store = RocksStore.open(dir)) {
            store.tables().create("a", TableKind.STATEMENTS);
            store.tables().create("b", TableKind.STATEMENTS);
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table("a").write(new RowMutation(b("r")).put(b(""), b(""), b(""), 1, b("v")));
                w.table("b").write(new RowMutation(b("r")).put(b(""), b(""), b(""), 1, b("v")));
                assertEquals(List.of(), all(store, "a"));
                assertEquals(List.of(), all(store, "b"));
                w.flush();
                assertEquals(1, all(store, "a").size());
                assertEquals(1, all(store, "b").size());
            }
        }
    }

    @Test
    void timestampedDeletesSpareNewerCells() {
        try (Store store = RocksStore.open(dir)) {
            store.tables().create("t", TableKind.STATEMENTS);
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table("t").write(new RowMutation(b("old")).put(b(""), b(""), b(""), 10, b("v")));
                w.table("t").write(new RowMutation(b("new")).put(b(""), b(""), b(""), 30, b("v")));
                w.table("t").write(new RowMutation(b("old")).delete(b(""), b(""), b(""), 20));
                w.table("t").write(new RowMutation(b("new")).delete(b(""), b(""), b(""), 20));
                w.table("t").write(new RowMutation(b("missing")).delete(b(""), b(""), b(""), 20));
            }
            assertEquals(List.of("new/@30=v"), all(store, "t"));
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table("t").write(new RowMutation(b("new")).delete(b(""), b(""), b("")));
            }
            assertEquals(List.of(), all(store, "t"));
        }
    }

    @Test
    void labelsAreEnforcedOnEveryRead() {
        try (Store store = RocksStore.open(dir)) {
            store.tables().create("t", TableKind.STATEMENTS);
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table("t").write(new RowMutation(b("r1")).put(b(""), b(""), b(""), 1, b("v")));
                w.table("t").write(new RowMutation(b("r2")).put(b(""), b(""), b("A"), 1, b("v")));
                w.table("t").write(new RowMutation(b("r3")).put(b(""), b(""), b("A&B"), 1, b("v")));
                assertThrows(RuntimeException.class, () -> w.table("t").write(new RowMutation(b("r4")).put(b(""), b(""), b("A&"), 1, b("v"))));
            }
            assertEquals(1, read(store, "t", KeyRange.all(), ScanSpec.of(Auths.NONE)).size());
            assertEquals(2, read(store, "t", KeyRange.all(), ScanSpec.of(Auths.of("A"))).size());
            assertEquals(3, read(store, "t", KeyRange.all(), ScanSpec.of(Auths.of("A", "B"))).size());
            assertEquals(3, all(store, "t").size());
            // A delete only removes what its labels see.
            store.deleteVisible("t", List.of(KeyRange.all()), ScanSpec.of(Auths.of("A")), WriterOptions.DEFAULT);
            assertEquals(List.of("r3/A&B@1=v"), all(store, "t"));
        }
    }

    @Test
    void countersAddUpUnderConcurrentWriters() throws Exception {
        try (Store store = RocksStore.open(dir)) {
            store.tables().create("eval", TableKind.COUNTERS);
            final ExecutorService pool = Executors.newFixedThreadPool(8);
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                        for (int i = 0; i < 500; i++) {
                            w.table("eval").write(new RowMutation(b("c")).addToCounter(b("f"), b(""), b(""), i % 2 == 0 ? 3 : -1));
                            if (i % 50 == 0) {
                                w.flush();
                            }
                        }
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES));
            for (int round = 0; round < 2; round++) {
                try (CellScanner cells = store.scan("eval", KeyRange.all(), ScanSpec.of(Auths.NONE))) {
                    assertEquals(8 * 500, store.counterValue(cells.next().value()));
                    assertFalse(cells.hasNext());
                }
                ((RocksStore) store).compact("eval");
            }
            // A deleted counter starts again from zero.
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table("eval").write(new RowMutation(b("c")).delete(b("f"), b(""), b("")));
                w.table("eval").write(new RowMutation(b("c")).addToCounter(b("f"), b(""), b(""), 4));
            }
            try (CellScanner cells = store.scan("eval", KeyRange.all(), ScanSpec.of(Auths.NONE))) {
                assertEquals(4, store.counterValue(cells.next().value()));
            }
        }
    }

    @Test
    void deletingRowsEmptiesATableUnderAnOpenScanner() {
        try (Store store = RocksStore.open(dir)) {
            store.tables().create("t", TableKind.STATEMENTS);
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                for (int i = 0; i < 100; i++) {
                    w.table("t").write(new RowMutation(b("r" + i)).put(b(""), b(""), b(""), 1, b("v")));
                }
            }
            try (CellScanner open = store.scan("t", KeyRange.all(), ScanSpec.of(Auths.NONE))) {
                assertTrue(open.hasNext());
                store.tables().deleteRows("t");
                assertEquals(List.of(), all(store, "t"));
                int seen = 0;
                while (open.hasNext()) {
                    open.next();
                    seen++;
                }
                assertEquals(100, seen, "the scanner keeps the snapshot it started with");
            }
            store.tables().delete("t");
            assertFalse(store.tables().exists("t"));
            assertThrows(RyaDAOException.class, () -> all(store, "t"));
        }
    }
}
