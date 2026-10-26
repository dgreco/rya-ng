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
package io.github.dgreco.rya.accumulo.index;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;

/**
 * Checks that an index table holds exactly the entries its statements call for: no entry
 * missing (which would lose query results) and none left over (which costs time). Reads every
 * table in full with all the Accumulo user's authorizations, so it is meant for tests and
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

    public static Report verify(final AccumuloClient client, final AccumuloRdfConfiguration conf, final IndexType type)
            throws Exception {
        final SecondaryIndex index = index(client, conf, type);
        final Authorizations auths = client.securityOperations().getUserAuthorizations(client.whoami());
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);

        final Set<String> expected = new HashSet<>();
        try (BatchScanner spo = client.createBatchScanner(conf.getTableLayoutStrategy().getSpo(), auths, 4)) {
            spo.setRanges(Collections.singleton(new Range()));
            for (final Map.Entry<Key, Value> e : spo) {
                final Key k = e.getKey();
                final RyaStatement st = context.deserializeTriple(TABLE_LAYOUT.SPO,
                        new TripleRow(k.getRowData().toArray(), k.getColumnFamilyData().toArray(),
                                k.getColumnQualifierData().toArray(), k.getTimestamp(),
                                k.getColumnVisibilityData().toArray(), e.getValue().get()));
                for (final Mutation m : index.puts(st)) {
                    for (final ColumnUpdate u : m.getUpdates()) {
                        expected.add(describe(m.getRow(), u.getColumnFamily(), u.getColumnQualifier(),
                                u.getColumnVisibility(), u.getTimestamp()));
                    }
                }
            }
        }
        final Set<String> actual = new HashSet<>();
        try (BatchScanner table = client.createBatchScanner(type.table(conf.getTableLayoutStrategy()), auths, 4)) {
            table.setRanges(Collections.singleton(new Range()));
            for (final Map.Entry<Key, Value> e : table) {
                final Key k = e.getKey();
                actual.add(describe(k.getRowData().toArray(), k.getColumnFamilyData().toArray(),
                        k.getColumnQualifierData().toArray(), k.getColumnVisibilityData().toArray(), k.getTimestamp()));
            }
        }
        final Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        final Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        return new Report(missing, extra);
    }

    /** The index as it was defined when built (its stored predicate list), else as configured. */
    private static SecondaryIndex index(final AccumuloClient client, final AccumuloRdfConfiguration conf,
            final IndexType type) throws Exception {
        if (type == IndexType.GEO) {
            return new GeoIndex();
        }
        final IndexState state = IndexManager.readState(client, conf.getTableLayoutStrategy(), type);
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
