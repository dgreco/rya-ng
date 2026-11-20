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
package org.tripatha.hbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import org.tripatha.test.StoreITBase;

/**
 * HBase's import for the bulk build (SCALE-STUDY.md O10), as the normal test account: HFiles with
 * visibility tags of label ordinals from the canaries, counters added up in coprocessor mode and
 * refused in increment mode.
 */
class HBaseBulkImportIT extends StoreITBase {
    private BulkImport bulk;

    @BeforeEach
    void hbaseOnly() {
        assumeBackend("hbase");
        grantAuthorizations("A", "B");
        bulk = getStore().bulk().orElseThrow();
    }

    @Test
    void filesWithLabelsAndCountersAreImportedAsTheAccount() throws Exception {
        final String spo = getTablePrefix() + "spo";
        getStore().tables().create(spo, TableKind.STATEMENTS);
        write(spo, new RowMutation(b("b")).put(b(""), b(""), b(""), 10, b("")));
        final String dir = Files.createTempDirectory("tripatha-hbase-bulk").toString();
        final BulkImport.Written statements;
        try (BulkImport.BulkFile f = bulk.newFile(spo, dir + "/spo", "spo-0", 1)) {
            f.put(b("a"), b(""), b(""), b(""), 7, b(""));
            f.put(b("c"), b("g1"), b(""), b("A"), 7, b("v"));
            f.put(b("d"), b(""), b(""), b("A&B"), 7, b(""));
            statements = f.finish();
        }
        bulk.importFiles(spo, List.of(statements));
        assertEquals(List.of("a", "b"), rows(spo, Auths.NONE));
        assertEquals(List.of("a", "b", "c"), rows(spo, Auths.of("A")));
        assertEquals(List.of("a", "b", "c", "d"), rows(spo, Auths.of("A", "B")));
        try (CellScanner cells = getStore().scan(spo, KeyRange.row(b("c")), ScanSpec.of(Auths.of("A")))) {
            final Cell c = cells.next();
            assertEquals(7, c.timestamp());
            assertEquals("g1", new String(c.family(), StandardCharsets.UTF_8));
            assertEquals("A", new String(c.visibility(), StandardCharsets.UTF_8));
            assertEquals("v", new String(c.value(), StandardCharsets.UTF_8));
        }

        final String eval = getTablePrefix() + "eval";
        getStore().tables().create(eval, TableKind.COUNTERS);
        write(eval, new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 2));
        if (bulk.refusesCounters(eval).isPresent()) {
            return; // increment mode: countersAreRefusedInIncrementMode
        }
        final BulkImport.Written counters;
        try (BulkImport.BulkFile f = bulk.newFile(eval, dir + "/eval", "eval-0", System.currentTimeMillis() - 1000)) {
            f.counter(b("c"), b("s"), b(""), b("A"), 5);
            f.counter(b("e"), b("s"), b(""), b(""), 3);
            counters = f.finish();
        }
        bulk.importFiles(eval, List.of(counters));
        assertEquals(7, counter(eval, "c"), "2 online, 5 imported");
        assertEquals(3, counter(eval, "e"));
    }

    @Test
    void aLabelWithoutAnOrdinalStopsTheFile() throws Exception {
        final String spo = getTablePrefix() + "spo";
        getStore().tables().create(spo, TableKind.STATEMENTS);
        final String dir = Files.createTempDirectory("tripatha-hbase-bulk").toString();
        try (BulkImport.BulkFile f = bulk.newFile(spo, dir, "spo-0", 1)) {
            final Exception refused = assertThrows(RuntimeException.class, () -> f.put(b("a"), b(""), b(""), b("Z"), 7, b("")));
            assertTrue(String.valueOf(refused.getMessage()).contains("Z") || String.valueOf(refused.getCause()).contains("Z"),
                    "the label is named: " + refused);
        }
    }

    @Test
    void countersAreRefusedInIncrementMode() {
        final String eval = getTablePrefix() + "eval";
        getStore().tables().create(eval, TableKind.COUNTERS);
        final boolean increment = ((HBaseStore) getStore()).counterMode(eval) == HBaseStore.CounterMode.INCREMENT;
        assertEquals(increment, bulk.refusesCounters(eval).isPresent());
        if (increment) {
            assertTrue(bulk.refusesCounters(eval).get().contains("increment mode"), bulk.refusesCounters(eval).get());
        }
    }

    private void write(final String table, final RowMutation m) {
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            w.table(table).write(m);
        }
    }

    private long counter(final String table, final String row) {
        long sum = 0;
        try (CellScanner cells = getStore().scan(table, KeyRange.row(b(row)), ScanSpec.of(Auths.of("A", "B")))) {
            while (cells.hasNext()) {
                sum += getStore().counterValue(cells.next().value());
            }
        }
        return sum;
    }

    private List<String> rows(final String table, final Auths auths) {
        final List<String> rows = new ArrayList<>();
        try (CellScanner cells = getStore().scan(table, KeyRange.all(), ScanSpec.of(auths))) {
            while (cells.hasNext()) {
                rows.add(new String(cells.next().row(), StandardCharsets.UTF_8));
            }
        }
        return rows;
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
