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
package io.github.dgreco.rya.store.index;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;


import io.github.dgreco.rya.store.StoreRdfConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.Store;

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

    /** The index as it was defined when built (its stored predicate list), else as configured. */
    private static SecondaryIndex index(final Store store, final StoreRdfConfiguration conf,
            final IndexType type) throws Exception {
        if (type == IndexType.GEO) {
            return new GeoIndex();
        }
        final IndexState state = IndexManager.readState(store, conf.getTableLayoutStrategy(), type);
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
