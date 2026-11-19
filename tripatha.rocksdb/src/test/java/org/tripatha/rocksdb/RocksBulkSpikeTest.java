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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.EnvOptions;
import org.rocksdb.IngestExternalFileOptions;
import org.rocksdb.Options;
import org.rocksdb.SstFileWriter;
import org.rocksdb.UInt64AddOperator;

import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;

/**
 * The bulk spike for RocksDB (SCALE-STUDY.md M6): SST files written with the store's own key and
 * value encoding, then ingested into tables that already hold data. Counter deltas go in as
 * {@code merge} entries, so they must add up with what the table holds, on reads and after a
 * compaction, as online deltas do.
 */
class RocksBulkSpikeTest {
    @TempDir
    Path dir;

    @Test
    void mergeEntriesInAnIngestedFileAddToTheCountersAlreadyThere() throws Exception {
        final Path db = dir.resolve("db");
        try (RocksStore store = RocksStore.open(db)) {
            store.tables().create("t_eval", TableKind.COUNTERS);
            write(store, "t_eval", new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 2));
            write(store, "t_eval", new RowMutation(b("d")).addToCounter(b("s"), b(""), b(""), 1));

            // Keys in order: c (A), then e (a counter the table doesn't have yet).
            ingest(db, "t_eval", sst("counters-1.sst", w -> {
                w.merge(RocksCodec.key(b("c"), b("s"), b(""), b("A")), RocksCodec.counter(5));
                w.merge(RocksCodec.key(b("e"), b("s"), b(""), b("")), RocksCodec.counter(3));
            }));
            assertEquals(7, counter(store, "c", "A"), "2 online, 5 imported");
            assertEquals(1, counter(store, "d", ""));
            assertEquals(3, counter(store, "e", ""));

            // The same deltas imported again count again: the build must record its imports.
            ingest(db, "t_eval", sst("counters-2.sst", w -> w.merge(RocksCodec.key(b("c"), b("s"), b(""), b("A")),
                    RocksCodec.counter(5))));
            assertEquals(12, counter(store, "c", "A"));

            // Online deltas after the import, and a compaction, keep the sums.
            write(store, "t_eval", new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 1));
            store.compact("t_eval");
            assertEquals(13, counter(store, "c", "A"));
            assertEquals(3, counter(store, "e", ""));
        }
    }

    @Test
    void statementCellsInAnIngestedFileKeepTheirLabelsAndTimestamps() throws Exception {
        final Path db = dir.resolve("db");
        try (RocksStore store = RocksStore.open(db)) {
            store.tables().create("t_spo", TableKind.STATEMENTS);
            write(store, "t_spo", new RowMutation(b("b")).put(b(""), b(""), b(""), 10, b("")));
            ingest(db, "t_spo", sst("statements.sst", w -> {
                w.put(RocksCodec.key(b("a"), b(""), b(""), b("")), RocksCodec.value(7, b("")));
                w.put(RocksCodec.key(b("c"), b("g1"), b(""), b("A")), RocksCodec.value(7, b("")));
                w.put(RocksCodec.key(b("d"), b(""), b(""), b("A&B")), RocksCodec.value(7, b("")));
            }));
            assertEquals(List.of("a", "b"), rows(store, Auths.NONE));
            assertEquals(List.of("a", "b", "c"), rows(store, Auths.of("A")));
            assertEquals(List.of("a", "b", "c", "d"), rows(store, Auths.of("A", "B")));
            try (CellScanner cells = store.scan("t_spo", KeyRange.row(b("c")), ScanSpec.of(Auths.of("A")))) {
                final Cell c = cells.next();
                assertEquals(7, c.timestamp(), "the dataset's timestamp, as load writes it");
                assertEquals("g1", new String(c.family(), StandardCharsets.UTF_8));
            }
        }
    }

    private interface Entries {
        void write(SstFileWriter writer) throws Exception;
    }

    private Path sst(final String name, final Entries entries) throws Exception {
        final Path file = dir.resolve(name);
        try (EnvOptions env = new EnvOptions(); UInt64AddOperator add = new UInt64AddOperator();
                Options options = new Options().setMergeOperator(add); SstFileWriter writer = new SstFileWriter(env, options)) {
            writer.open(file.toString());
            entries.write(writer);
            writer.finish();
        }
        return file;
    }

    /** Ingests a file into a table of the open database, as the bulk build would. */
    private static void ingest(final Path db, final String table, final Path file) throws Exception {
        final RocksDatabase database = RocksDatabase.acquire(db);
        try (IngestExternalFileOptions options = new IngestExternalFileOptions()) {
            database.db().ingestExternalFile(database.family(table), List.of(file.toString()), options);
        } finally {
            database.release();
        }
    }

    private static void write(final RocksStore store, final String table, final RowMutation m) {
        try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
            w.table(table).write(m);
        }
    }

    private static long counter(final RocksStore store, final String row, final String label) {
        long sum = 0;
        try (CellScanner cells = store.scan("t_eval", KeyRange.row(b(row)), ScanSpec.of(Auths.ALL_HELD))) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                if (new String(c.visibility(), StandardCharsets.UTF_8).equals(label)) {
                    sum += store.counterValue(c.value());
                }
            }
        }
        return sum;
    }

    private static List<String> rows(final RocksStore store, final Auths auths) {
        final List<String> rows = new ArrayList<>();
        try (CellScanner cells = store.scan("t_spo", KeyRange.all(), ScanSpec.of(auths))) {
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
