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
package org.tripatha.store.index;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.api.resolver.triple.TripleRow;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;

/**
 * Checks that an index table holds exactly the entries its statements call for: no entry
 * missing (which would lose query results) and none left over (which costs time). Reads every
 * table in full with every label the account holds, so it is meant for tests and
 * diagnostics, not for production paths.
 */
public final class IndexVerifier {

    /** Differences between the index and the statement tables, as readable key strings. */
    public record Report(Set<String> missing, Set<String> extra) {
        public boolean isClean() {
            return missing.isEmpty() && extra.isEmpty();
        }

        @Override
        public String toString() {
            return "missing=" + missing + ", extra=" + extra;
        }
    }

    private IndexVerifier() {
    }

    public static Report verify(final Store store, final StoreRdfConfiguration conf, final IndexType type)
            throws Exception {
        final SecondaryIndex index = index(store, conf, type);
        final ScanSpec everyLabel = ScanSpec.of(Auths.ALL_HELD);
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);
        final Set<String> expected = new HashSet<>();
        try (CellScanner spo = store.batchScan(conf.getTableLayoutStrategy().getSpo(), List.of(KeyRange.all()), everyLabel)) {
            while (spo.hasNext()) {
                final Cell k = spo.next();
                final RyaStatement st = context.deserializeTriple(TABLE_LAYOUT.SPO,
                        new TripleRow(k.row(), k.family(), k.qualifier(), k.timestamp(), k.visibility(), k.value()));
                for (final RowMutation m : index.puts(st)) {
                    for (final RowMutation.Update u : m.updates()) {
                        expected.add(describe(m.row(), u.family(), u.qualifier(), u.visibility(), u.timestamp()));
                    }
                }
            }
        }
        final Set<String> actual = new HashSet<>();
        try (CellScanner table = store.batchScan(type.table(conf.getTableLayoutStrategy()), List.of(KeyRange.all()), everyLabel)) {
            while (table.hasNext()) {
                final Cell k = table.next();
                actual.add(describe(k.row(), k.family(), k.qualifier(), k.visibility(), k.timestamp()));
            }
        }
        final Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        final Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        return new Report(missing, extra);
    }

    /**
     * The text index's posting counts against its postings (SCALE-STUDY.md O6), word by word,
     * predicate and label, under every label the account holds.
     *
     * @return the counters below their postings (a write path that doesn't count) and above them
     *         (the drift every counter has: a statement added twice, a graph dropped), or null when
     *         the counts aren't complete, so there is nothing to check
     */
    public static Report verifyTextCounts(final Store store, final StoreRdfConfiguration conf) throws Exception {
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        final IndexState state = IndexManager.readState(store, tables, IndexType.TEXT);
        if (state == null || !store.tables().exists(tables.getTextCount())) {
            return null;
        }
        final String flag = IndexManager.readFlag(store, tables, TextCounts.FLAG);
        if (flag == null || !flag.equals(state.definition())) {
            return null;
        }
        final ScanSpec everyLabel = ScanSpec.of(Auths.ALL_HELD);
        final Map<String, Long> postings = new HashMap<>();
        try (CellScanner cells = store.batchScan(tables.getText(), List.of(KeyRange.all()), everyLabel)) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                postings.merge(IndexKeys.term(c) + "|" + IndexKeys.docKey(c).predicate() + " [" + text(c.visibility()) + "]", 1L, Long::sum);
            }
        }
        final Map<String, Long> counts = new HashMap<>();
        try (CellScanner cells = store.batchScan(tables.getTextCount(), List.of(KeyRange.all()),
                ScanSpec.builder(Auths.ALL_HELD).column(TextCounts.FAMILY, new byte[0]).build())) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                counts.merge(text(c.row()).replace(IndexKeys.SEP, '|') + " [" + text(c.visibility()) + "]", store.counterValue(c.value()),
                        Long::sum);
            }
        }
        final Set<String> below = new TreeSet<>();
        final Set<String> above = new TreeSet<>();
        final Set<String> keys = new HashSet<>(postings.keySet());
        keys.addAll(counts.keySet());
        for (final String key : keys) {
            final long posted = postings.getOrDefault(key, 0L);
            final long counted = counts.getOrDefault(key, 0L);
            if (counted < posted) {
                below.add(key + ": " + counted + " counted, " + posted + " postings");
            } else if (counted > posted) {
                above.add(key + ": " + counted + " counted, " + posted + " postings");
            }
        }
        return new Report(below, above);
    }

    /** The index as it was defined when built (its stored predicate list), else as configured. */
    private static SecondaryIndex index(final Store store, final StoreRdfConfiguration conf,
            final IndexType type) throws Exception {
        if (type == IndexType.GEO) {
            return new GeoIndex();
        }
        final IndexState state = IndexManager.readState(store, conf.getTableLayoutStrategy(), type);
        if (type == IndexType.ENTITY) {
            final int shards = state == null ? -1 : EntityIndex.shardsOf(state.definition());
            return new EntityIndex(shards > 0 ? shards : conf.getEntityIndexShards());
        }
        final String marker = ";preds=";
        if (state == null || !state.definition().contains(marker)) {
            return new TextIndex(conf.getTextIndexPredicates());
        }
        final String stored = state.definition().substring(state.definition().indexOf(marker) + marker.length());
        final List<String> predicates = stored.isBlank() ? List.of() : List.of(stored.split(" "));
        return new TextIndex(predicates);
    }

    private static String describe(final byte[] row, final byte[] cf, final byte[] cq, final byte[] cv, final long ts) {
        return text(row).replace(IndexKeys.SEP, '|') + " [" + text(cf) + "][" + text(cq) + "][" + text(cv) + "]@" + ts;
    }

    private static String text(final byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
