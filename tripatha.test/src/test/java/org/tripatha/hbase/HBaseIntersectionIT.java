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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.IntersectionCount;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;
import org.tripatha.test.hbase.MiniHBaseSingleton;

/**
 * HBase's intersection endpoint (SCALE-STUDY.md D14), as the normal test account, against the
 * client's own reading of the same postings ({@code Store.scan} of each term):
 * <ul>
 * <li>labels the account doesn't hold stay hidden, whatever the request asks for, as on a client
 *     scan: the endpoint's scans go through the region's {@code preScannerOpen};</li>
 * <li>pages of one match and one cell, which stop and resume the leapfrog everywhere, give the same
 *     matches and counts as one page;</li>
 * <li>the time range, including cells it hides;</li>
 * <li>only tables whose regions answer intersect: index tables, not a table without the endpoint.</li>
 * </ul>
 */
class HBaseIntersectionIT extends StoreITBase {
    private static final byte[][] TERMS = {b("p\u0001a"), b("p\u0001b"), b("q\u0001c")};
    private static final List<byte[]> ROWS = List.of(b("0"), b("1"), b("2"));

    private String table;

    @BeforeEach
    void hbaseOnly() {
        assumeBackend("hbase");
        grantAuthorizations("A", "B");
        HBaseLabels.register(MiniHBaseSingleton.adminConnection(), List.of("notGranted"));
        table = getTablePrefix() + "entity";
        getStore().tables().create(table, TableKind.INDEX);
    }

    @Test
    void labelsTheAccountDoesntHoldStayHidden() {
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            final RowMutation m = new RowMutation(b("0"));
            // s1 is in both terms in the open, s2 only under a label nobody granted, s3 under A.
            m.put(TERMS[0], posting("s1", 0), b(""), 5, b(""));
            m.put(TERMS[1], posting("s1", 0), b(""), 5, b(""));
            m.put(TERMS[0], posting("s2", 0), b(""), 5, b(""));
            m.put(TERMS[1], posting("s2", 0), b("notGranted"), 5, b(""));
            m.put(TERMS[0], posting("s3", 0), b("A"), 5, b(""));
            m.put(TERMS[1], posting("s3", 0), b("A&B"), 5, b(""));
            w.table(table).write(m);
        }
        final List<byte[]> terms = List.of(TERMS[0], TERMS[1]);
        for (final Auths auths : List.of(Auths.NONE, Auths.of("A"), Auths.of("A", "B"), Auths.of("A", "B", "notGranted"),
                Auths.of("notGranted"), Auths.ALL_HELD)) {
            final Map<String, Long> expected = expected(List.of(b("0")), terms, ScanSpec.of(auths));
            assertFalse(expected.containsKey("0 s2"), "the client doesn't see it either: " + auths);
            assertEquals(expected, intersect(getStore(), List.of(b("0")), terms, ScanSpec.of(auths)), auths.toString());
        }
        assertEquals(Map.of("0 s1", 1L, "0 s3", 1L), intersect(getStore(), List.of(b("0")), terms, ScanSpec.of(Auths.ALL_HELD)));
    }

    @Test
    void pagesOfOneMatchAndOneCellGiveWhatOnePageGives() {
        final Random random = new Random(7);
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            for (final byte[] row : ROWS) {
                final RowMutation m = new RowMutation(row);
                for (int s = 0; s < 120; s++) {
                    for (final byte[] term : TERMS) {
                        if (random.nextInt(3) == 0) {
                            continue;
                        }
                        final int cells = 1 + random.nextInt(3);
                        for (int c = 0; c < cells; c++) {
                            final String label = switch (random.nextInt(5)) {
                                case 0 -> "A";
                                case 1 -> "notGranted";
                                default -> "";
                            };
                            m.put(term, posting("s" + s, c), b(label), 1 + random.nextInt(9), b(""));
                        }
                    }
                }
                w.table(table).write(m);
            }
        }
        try (Store paged = HBaseStore.owning(MiniHBaseSingleton.newTestConnection(Map.of(
                "tripatha.intersect.page.matches", "1", "tripatha.intersect.page.cells", "1", "tripatha.intersect.batch", "1")),
                MiniHBaseSingleton.testUser(), HBaseStore.Options.DEFAULT.withCounters(
                        org.tripatha.test.hbase.HBaseTestBackend.counterMode()))) {
            assertTrue(paged.canIntersect(table));
            for (int n = 1; n <= TERMS.length; n++) {
                final List<byte[]> terms = List.of(TERMS).subList(0, n);
                for (final ScanSpec spec : List.of(ScanSpec.of(Auths.NONE), ScanSpec.of(Auths.of("A")),
                        ScanSpec.builder(Auths.of("A")).timestamps(3L, 6L).build())) {
                    final Map<String, Long> expected = expected(ROWS, terms, spec);
                    assertFalse(expected.isEmpty());
                    final String which = n + " terms, " + spec.auths() + ", " + spec.minTimestamp() + ".." + spec.maxTimestamp();
                    assertEquals(expected, intersect(getStore(), ROWS, terms, spec), which);
                    assertEquals(expected, intersect(paged, ROWS, terms, spec), which + ", paged");
                    final IntersectionCount count = new IntersectionCount(expected.size(),
                            expected.values().stream().mapToLong(Long::longValue).sum());
                    assertEquals(count, getStore().countIntersection(table, ROWS, terms, spec), which);
                    assertEquals(count, paged.countIntersection(table, ROWS, terms, spec), which + ", paged");
                }
            }
        }
    }

    @Test
    void onlyTablesWithTheEndpointIntersect() throws Exception {
        assertTrue(getStore().canIntersect(table));
        final String statements = getTablePrefix() + "spo";
        getStore().tables().create(statements, TableKind.STATEMENTS);
        assertFalse(getStore().canIntersect(statements), "statement tables don't get the endpoint");
        // An index table of an older store: created without it.
        final String older = getTablePrefix() + "older";
        try (Admin admin = MiniHBaseSingleton.adminConnection().getAdmin()) {
            admin.createTable(TableDescriptorBuilder.newBuilder(TableName.valueOf(older))
                    .setColumnFamily(ColumnFamilyDescriptorBuilder.of(HBaseCodec.FAMILY)).build());
        }
        assertFalse(getStore().canIntersect(older));
        assertFalse(getStore().canIntersect(getTablePrefix() + "missing"));
    }

    /**
     * The count endpoint (O26) counts what a scan returns, under the account's labels (one it
     * doesn't hold stays hidden, whatever the request asks), across regions, and in pages of one row.
     */
    @Test
    void rangesAreCountedInTheRegionsAsScansReturnThem() {
        final String spo = getTablePrefix() + "spo";
        getStore().tables().create(spo, TableKind.STATEMENTS);
        final java.util.SortedSet<byte[]> splits = new java.util.TreeSet<>(java.util.Arrays::compareUnsigned);
        splits.add(b("s10"));
        splits.add(b("s20"));
        getStore().tables().addSplits(spo, splits);
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            for (int i = 0; i < 30; i++) {
                final RowMutation m = new RowMutation(b(String.format("s%02d", i) + "\u0000p\u0000o\u0001\u0003"));
                m.put(b(""), b(""), b(""), 5, b(""));
                m.put(b("urn:g1"), b(""), b(i % 2 == 0 ? "A" : "notGranted"), 5 + i % 3, b(""));
                w.table(spo).write(m);
            }
        }
        final List<KeyRange> ranges = List.of(KeyRange.closed(b("s00"), b("s04\u00ff")), KeyRange.closed(b("s08"), b("s23\u00ff")),
                KeyRange.prefix(b("s25")), KeyRange.closed(b("s27"), b("s99")), KeyRange.closed(b("t"), b("u")));
        try (Store paged = HBaseStore.owning(MiniHBaseSingleton.newTestConnection(Map.of("tripatha.count.page.rows", "1")),
                MiniHBaseSingleton.testUser(), HBaseStore.Options.DEFAULT.withCounters(
                        org.tripatha.test.hbase.HBaseTestBackend.counterMode()))) {
            assertTrue(getStore().countsWithoutReading(spo), "statement tables get the count endpoint");
            assertFalse(getStore().countsWithoutReading(table), "index tables don't");
            for (final ScanSpec spec : List.of(ScanSpec.of(Auths.NONE), ScanSpec.of(Auths.of("A")),
                    ScanSpec.of(Auths.of("A", "notGranted")), ScanSpec.of(Auths.ALL_HELD),
                    ScanSpec.builder(Auths.of("A")).family(b("urn:g1")).build(), ScanSpec.builder(Auths.of("A")).timestamps(6L, 6L).build())) {
                final long[] scanned = new long[ranges.size()];
                for (int i = 0; i < ranges.size(); i++) {
                    try (CellScanner cells = getStore().scan(spo, ranges.get(i), spec)) {
                        while (cells.hasNext()) {
                            cells.next();
                            scanned[i]++;
                        }
                    }
                }
                assertEquals(java.util.Arrays.toString(scanned), java.util.Arrays.toString(getStore().countRanges(spo, ranges, spec)),
                        spec.auths().toString());
                assertEquals(java.util.Arrays.toString(scanned), java.util.Arrays.toString(paged.countRanges(spo, ranges, spec)),
                        "pages of one row, " + spec.auths());
            }
        }
    }

    /** What the endpoint should find: each term read by the client, subjects counted, multiplied. */
    private Map<String, Long> expected(final List<byte[]> rows, final List<byte[]> terms, final ScanSpec spec) {
        final Map<String, Long> out = new TreeMap<>();
        for (final byte[] row : rows) {
            final Map<String, long[]> counts = new HashMap<>();
            for (int t = 0; t < terms.size(); t++) {
                try (CellScanner cells = getStore().scan(table, KeyRange.row(row), spec.toBuilder().family(terms.get(t)).build())) {
                    while (cells.hasNext()) {
                        final String subject = new String(EntityIndex.subject(cells.next().qualifier()), StandardCharsets.UTF_8);
                        counts.computeIfAbsent(subject, k -> new long[terms.size()])[t]++;
                    }
                }
            }
            counts.forEach((subject, perTerm) -> {
                long product = 1;
                for (final long n : perTerm) {
                    product *= n;
                }
                if (product > 0) {
                    out.put(new String(row, StandardCharsets.UTF_8) + " " + subject, product);
                }
            });
        }
        return out;
    }

    private Map<String, Long> intersect(final Store store, final List<byte[]> rows, final List<byte[]> terms, final ScanSpec spec) {
        final Map<String, Long> out = new TreeMap<>();
        final List<String> order = new ArrayList<>();
        try (CellScanner cells = store.intersect(table, rows, terms, spec)) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                final String key = new String(c.row(), StandardCharsets.UTF_8) + " " + new String(c.qualifier(), StandardCharsets.UTF_8);
                assertEquals(null, out.put(key, Long.parseLong(new String(c.value(), StandardCharsets.UTF_8))), "once: " + key);
                order.add(key);
            }
        }
        // A row's subjects come in order.
        for (final byte[] row : rows) {
            final String r = new String(row, StandardCharsets.UTF_8) + " ";
            final List<String> ofRow = order.stream().filter(k -> k.startsWith(r)).toList();
            assertEquals(ofRow.stream().sorted().toList(), ofRow, "in order within row " + r);
        }
        return out;
    }

    /** A posting's qualifier: subject 01 graph 01 qualifier. */
    private static byte[] posting(final String subject, final int graph) {
        return b(subject + "\u0001g" + graph + "\u0001");
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
