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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.CellBuilderType;
import org.apache.hadoop.hbase.CellComparator;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.ExtendedCellBuilderFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileContextBuilder;
import org.apache.hadoop.hbase.security.visibility.VisibilityConstants;
import org.apache.hadoop.hbase.security.visibility.VisibilityLabelOrdinalProvider;
import org.apache.hadoop.hbase.security.visibility.VisibilityUtils;
import org.apache.hadoop.hbase.tool.BulkLoadHFiles;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;
import org.tripatha.test.hbase.MiniHBaseSingleton;

/**
 * The bulk spike for HBase (SCALE-STUDY.md M6, R10): HFiles written from this JVM in Tripatha's
 * cell layout ({@link HBaseCodec}), bulk-loaded into live tables.
 * <ul>
 * <li><b>Labels:</b> a cell's visibility is a tag of label ordinals, which a writer resolves from
 * {@code hbase:labels} as HBase's own import tools do. A normal account must then see exactly the
 * cells its labels allow.</li>
 * <li><b>Counters, coprocessor mode:</b> deltas are plain cells, which {@code CounterObserver}
 * adds up on reads. Two cells with the same key and timestamp are one cell to HBase, so an import
 * must stamp its counters with a timestamp of its own.</li>
 * <li><b>Counters, increment mode:</b> an imported cell replaces a counter or hides behind it,
 * so counters can only be imported into a table that doesn't hold them.</li>
 * </ul>
 */
class HBaseBulkSpikeIT extends StoreITBase {
    private static final byte[] FAMILY = HBaseCodec.FAMILY;

    @BeforeEach
    void hbaseOnly() {
        assumeBackend("hbase");
        grantAuthorizations("A", "B");
    }

    @Test
    void importedCountersAddUpOnlyWithATimestampPerImport() throws Exception {
        final String eval = getTablePrefix() + "eval";
        getStore().tables().create(eval, TableKind.COUNTERS);
        write(eval, new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 2));
        write(eval, new RowMutation(b("d")).addToCounter(b("s"), b(""), b(""), 1));
        if (((HBaseStore) getStore()).counterMode(eval) == HBaseStore.CounterMode.INCREMENT) {
            importedCountersReplaceInIncrementMode(eval);
            return;
        }

        final long first = System.currentTimeMillis() - 60_000;
        bulkLoad(eval, "counters-1", List.of(counter("c", "A", 5, first), counter("e", "", 3, first)));
        assertEquals(7, counter(eval, "c", "A"), "2 online, 5 imported");
        assertEquals(1, counter(eval, "d", ""));
        assertEquals(3, counter(eval, "e", ""));

        // The same cells again, with the same timestamp: HBase keeps one of the two, so they don't count again.
        bulkLoad(eval, "counters-2", List.of(counter("c", "A", 5, first)));
        assertEquals(7, counter(eval, "c", "A"), "same key, same timestamp: one cell");

        // With a timestamp of its own, an import adds up. A future one too: the coprocessor stamps later online
        // deltas after the newest timestamp in the region's files, so they don't collide with it.
        final long later = System.currentTimeMillis() + 86_400_000L;
        bulkLoad(eval, "counters-3", List.of(counter("c", "A", 5, later)));
        assertEquals(12, counter(eval, "c", "A"));
        write(eval, new RowMutation(b("c")).addToCounter(b("s"), b(""), b("A"), 1));
        assertEquals(13, counter(eval, "c", "A"));

        // A flush and a major compaction add the versions up, as for online deltas.
        try (Admin admin = MiniHBaseSingleton.adminConnection().getAdmin()) {
            admin.flush(TableName.valueOf(eval));
            admin.majorCompact(TableName.valueOf(eval));
            final long deadline = System.nanoTime() + 60_000_000_000L;
            Thread.sleep(500);
            while (admin.getCompactionState(TableName.valueOf(eval)) != org.apache.hadoop.hbase.client.CompactionState.NONE
                    && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
        }
        assertEquals(13, counter(eval, "c", "A"));
        assertEquals(3, counter(eval, "e", ""));
    }

    /**
     * Increment mode keeps one version per counter: an imported cell doesn't add to it, it hides
     * behind it (older) or replaces it (newer). Only a counter the table doesn't hold yet comes out
     * right, so a bulk build must refuse to import counters into a non-empty table in this mode.
     */
    private void importedCountersReplaceInIncrementMode(final String eval) throws Exception {
        bulkLoad(eval, "older", List.of(counter("c", "A", 5, System.currentTimeMillis() - 60_000), counter("e", "", 3, 1)));
        assertEquals(2, counter(eval, "c", "A"), "older than the online value: hidden behind it");
        assertEquals(3, counter(eval, "e", ""), "a counter the table didn't hold");
        bulkLoad(eval, "newer", List.of(counter("c", "A", 5, System.currentTimeMillis() + 86_400_000L)));
        assertEquals(5, counter(eval, "c", "A"), "newer than the online value: it replaces it, it doesn't add");
    }

    @Test
    void importedStatementsKeepTheirLabelsAsOrdinalTags() throws Exception {
        final String spo = getTablePrefix() + "spo";
        getStore().tables().create(spo, TableKind.STATEMENTS);
        write(spo, new RowMutation(b("b")).put(b(""), b(""), b(""), 10, b("")));
        bulkLoad(spo, "statements", List.of(statement("a", "", "", 7), statement("c", "g1", "A", 7),
                statement("d", "", "A&B", 7)));
        assertEquals(List.of("a", "b"), rows(spo, Auths.NONE));
        assertEquals(List.of("a", "b", "c"), rows(spo, Auths.of("A")));
        assertEquals(List.of("a", "b", "c", "d"), rows(spo, Auths.of("A", "B")));
        try (CellScanner cells = getStore().scan(spo, KeyRange.row(b("c")), ScanSpec.of(Auths.of("A")))) {
            final org.tripatha.store.spi.Cell c = cells.next();
            assertEquals(7, c.timestamp(), "the dataset's timestamp, as load writes it");
            assertEquals("g1", new String(c.family(), StandardCharsets.UTF_8));
            assertEquals("A", new String(c.visibility(), StandardCharsets.UTF_8));
        }
    }

    // ---- the cells, as the online path lays them out

    private ExtendedCell counter(final String row, final String label, final long delta, final long ts) throws Exception {
        return cell(row, "s", "", label, ts, Bytes.toBytes(delta));
    }

    private ExtendedCell statement(final String row, final String graph, final String label, final long ts) throws Exception {
        return cell(row, graph, "", label, ts, new byte[0]);
    }

    private ExtendedCell cell(final String row, final String family, final String qualifier, final String label, final long ts,
            final byte[] value) throws Exception {
        final List<Tag> tags = label.isEmpty() ? List.of()
                : VisibilityUtils.createVisibilityExpTags(HBaseCodec.visibility(b(label)).getExpression(), true, false, null,
                        ordinals());
        return ExtendedCellBuilderFactory.create(CellBuilderType.DEEP_COPY).setRow(HBaseCodec.row(b(row))).setFamily(FAMILY)
                .setQualifier(HBaseCodec.qualifier(b(family), b(qualifier), b(label))).setTimestamp(ts)
                .setType(Cell.Type.Put).setValue(value).setTags(tags).build();
    }

    private VisibilityLabelOrdinalProvider ordinals;

    /** Label ordinals as hbase:labels holds them (row: the ordinal; the label in the label qualifier). */
    private VisibilityLabelOrdinalProvider ordinals() throws Exception {
        if (ordinals == null) {
            final Map<String, Integer> byLabel = new HashMap<>();
            final Map<Integer, String> byOrdinal = new HashMap<>();
            try (Table labels = MiniHBaseSingleton.adminConnection().getTable(VisibilityConstants.LABELS_TABLE_NAME);
                    ResultScanner rows = labels.getScanner(new Scan().addColumn(VisibilityConstants.LABELS_TABLE_FAMILY,
                            VisibilityConstants.LABEL_QUALIFIER))) {
                for (final Result r : rows) {
                    final int ordinal = Bytes.toInt(r.getRow());
                    final String label = Bytes.toString(r.getValue(VisibilityConstants.LABELS_TABLE_FAMILY,
                            VisibilityConstants.LABEL_QUALIFIER));
                    byLabel.put(label, ordinal);
                    byOrdinal.put(ordinal, label);
                }
            }
            ordinals = new VisibilityLabelOrdinalProvider() {
                @Override
                public int getLabelOrdinal(final String label) {
                    return byLabel.getOrDefault(label, 0);
                }

                @Override
                public String getLabel(final int ordinal) {
                    return byOrdinal.get(ordinal);
                }
            };
        }
        return ordinals;
    }

    /** One HFile of the cells, in HBase's order, bulk-loaded by the superuser as an import tool would. */
    private void bulkLoad(final String table, final String name, final List<ExtendedCell> cells) throws Exception {
        final Configuration conf = MiniHBaseSingleton.configuration();
        final FileSystem fs = FileSystem.getLocal(conf);
        final Path dir = new Path(Files.createTempDirectory("tripatha-bulk-" + name).toUri());
        final Path file = new Path(new Path(dir, Bytes.toString(FAMILY)), name);
        final List<ExtendedCell> sorted = new ArrayList<>(cells);
        sorted.sort(CellComparator.getInstance());
        try (HFile.Writer writer = HFile.getWriterFactory(conf, new CacheConfig(conf)).withPath(fs, file)
                .withFileContext(new HFileContextBuilder().withIncludesTags(true).build()).create()) {
            for (final ExtendedCell c : sorted) {
                writer.append(c);
            }
        }
        BulkLoadHFiles.create(conf).bulkLoad(TableName.valueOf(table), dir);
    }

    // ---- reading back, as the normal test account

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
                final org.tripatha.store.spi.Cell c = cells.next();
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
