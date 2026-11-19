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
package org.tripatha.accumulo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.rfile.RFile;
import org.apache.accumulo.core.client.rfile.RFileWriter;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.LoadPlan;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;

/**
 * The bulk spike for Accumulo (SCALE-STUDY.md M6): RFiles written from this JVM with the public
 * {@link RFile} writer, from the cells the online path makes ({@link AccumuloStore#toMutation}),
 * imported into live tables. Their labels must hold, and counter cells must add up with what the
 * table holds through its combiner, as online deltas do.
 */
class AccumuloBulkSpikeIT extends StoreITBase {
    private AccumuloClient client;

    @BeforeEach
    void accumuloOnly() {
        assumeBackend("accumulo");
        client = ((AccumuloStore) getStore()).client();
        grantAuthorizations("A", "B");
    }

    @Test
    void importedCountersAddToTheCountersAlreadyThere() throws Exception {
        final String eval = getTablePrefix() + "eval";
        getStore().tables().create(eval, TableKind.COUNTERS);
        write(eval, new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 2));
        write(eval, new RowMutation(b("d")).addToCounter(b("s"), b(""), b(""), 1));

        importRFile(eval, "counters-1", List.of(new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 5),
                new RowMutation(b("e")).addToCounter(b("s"), b(""), b(""), 3)), 1, false);
        assertEquals(7, counter(eval, "c", "A"), "2 online, 5 imported");
        assertEquals(1, counter(eval, "d", ""));
        assertEquals(3, counter(eval, "e", ""));

        // The same file imported again counts again, even with the same timestamps: the build must record its imports.
        // This time with a load plan, as a build that knows each file's tablet does: Accumulo then doesn't read the file.
        importRFile(eval, "counters-2", List.of(new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 5)), 1, true);
        assertEquals(12, counter(eval, "c", "A"));

        // Online deltas after the import, and a full compaction, keep the sums.
        write(eval, new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 1));
        client.tableOperations().compact(eval, null, null, true, true);
        assertEquals(13, counter(eval, "c", "A"));
        assertEquals(3, counter(eval, "e", ""));
    }

    @Test
    void importedStatementsKeepTheirLabelsAndTimestamps() throws Exception {
        final String spo = getTablePrefix() + "spo";
        getStore().tables().create(spo, TableKind.STATEMENTS);
        write(spo, new RowMutation(b("b")).put(b(""), b(""), b(""), 10, b("")));
        importRFile(spo, "statements", List.of(new RowMutation(b("a")).put(b(""), b(""), b(""), 7, b("")),
                new RowMutation(b("c")).put(b("g1"), b(""), b("A"), 7, b("")),
                new RowMutation(b("d")).put(b(""), b(""), b("A&B"), 7, b(""))), 7, false);
        assertEquals(List.of("a", "b"), rows(spo, Auths.NONE));
        assertEquals(List.of("a", "b", "c"), rows(spo, Auths.of("A")));
        assertEquals(List.of("a", "b", "c", "d"), rows(spo, Auths.of("A", "B")));
        try (CellScanner cells = getStore().scan(spo, KeyRange.row(b("c")), ScanSpec.of(Auths.of("A")))) {
            final Cell c = cells.next();
            assertEquals(7, c.timestamp(), "the dataset's timestamp, as load writes it");
            assertEquals("g1", new String(c.family(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Writes the cells of {@code mutations}, encoded as the online path encodes them, to one RFile
     * in key order, and imports it. Cells without a timestamp of their own (counters) get {@code ts}.
     *
     * @param plan import with a {@link LoadPlan} naming the file's rows, instead of letting Accumulo read the file
     */
    private void importRFile(final String table, final String name, final List<RowMutation> mutations, final long ts,
            final boolean plan) throws Exception {
        final TreeMap<Key, Value> cells = new TreeMap<>();
        for (final RowMutation rm : mutations) {
            final Mutation m = AccumuloStore.toMutation(rm);
            for (final ColumnUpdate u : m.getUpdates()) {
                cells.put(new Key(m.getRow(), u.getColumnFamily(), u.getColumnQualifier(), u.getColumnVisibility(),
                        u.hasTimestamp() ? u.getTimestamp() : ts), new Value(u.getValue()));
            }
        }
        final Path dir = Files.createTempDirectory("tripatha-bulk-" + name);
        final FileSystem fs = FileSystem.getLocal(new Configuration());
        try (RFileWriter writer = RFile.newWriter().to(dir.resolve(name + ".rf").toString()).withFileSystem(fs).build()) {
            for (final Map.Entry<Key, Value> e : cells.entrySet()) {
                writer.append(e.getKey(), e.getValue());
            }
        }
        if (plan) {
            final LoadPlan loadPlan = LoadPlan.builder().loadFileTo(name + ".rf", LoadPlan.RangeType.FILE,
                    cells.firstKey().getRow(), cells.lastKey().getRow()).build();
            client.tableOperations().importDirectory(dir.toString()).to(table).plan(loadPlan).load();
        } else {
            client.tableOperations().importDirectory(dir.toString()).to(table).load();
        }
    }

    private void write(final String table, final RowMutation m) {
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            w.table(table).write(m);
        }
    }

    private long counter(final String table, final String row, final String label) {
        final Store store = getStore();
        long sum = 0;
        try (CellScanner cells = store.scan(table, KeyRange.row(b(row)), ScanSpec.of(Auths.of("A", "B")))) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                if (new String(c.visibility(), StandardCharsets.UTF_8).equals(label)) {
                    sum += store.counterValue(c.value());
                }
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
