/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.dgreco.rya.store;

import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.DELIM;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.OBJ_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PREDOBJECT_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PRED_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTOBJECT_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTPRED_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECT_CF;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.store.spi.RowMutation;

/**
 * Builds the cardinality-counter mutations for the eval table, and the named-graph markers.
 *
 * <p>Each statement adds +1 (or -1 on delete) to six counters: its subject, predicate, object,
 * subject+predicate, predicate+object and subject+object. The row is the values joined with
 * {@code DELIM}, the column family says which combination it is, and the column qualifier is
 * empty (all graphs) plus, for a named-graph statement, a second cell with the graph IRI.
 * The cell carries the statement's visibility, so a reader only counts what it may see.
 * The backend adds the deltas up server side (Accumulo's {@code SummingCombiner}).
 *
 * <p>A {@link CounterBatch} sums the counters of many statements first and writes one delta per
 * counter with the total, which the backend adds up exactly like the individual +1 deltas.
 *
 * <p>The counts drive join ordering only, so they may drift (re-inserting an existing
 * statement counts it twice; dropping a graph does not adjust the all-graphs counters).
 */
public final class EvalStatsMutations {
    /** Column family of the named-graph marker rows. */
    public static final String GRAPH_CF = "g";
    public static final byte[] GRAPH_CF_BYTES = GRAPH_CF.getBytes(StandardCharsets.UTF_8);

    private static final byte[] EMPTY = new byte[0];

    private EvalStatsMutations() {
    }

    /**
     * One counter cell.
     *
     * @param row the counted values joined with {@code DELIM}
     * @param family which combination is counted (subject, predicate, ...)
     * @param graph the graph IRI, or empty for the all-graphs counter
     * @param visibility the counted statement's visibility, one char per byte (ISO-8859-1)
     */
    public record CounterKey(String row, String family, String graph, String visibility) {
    }

    /** The counters a statement adds to: six, plus six more for a named-graph statement. */
    public static List<CounterKey> counterKeys(final RyaStatement stmt) {
        final String s = stmt.getSubject().getData();
        final String p = stmt.getPredicate().getData();
        final String o = stmt.getObject().getData();
        final String graph = stmt.getContext() == null ? null : stmt.getContext().getData();
        final byte[] label = stmt.getColumnVisibility();
        final String cv = label == null ? "" : new String(label, StandardCharsets.ISO_8859_1);
        final List<CounterKey> keys = new ArrayList<>(graph == null ? 6 : 12);
        addKeys(keys, s, SUBJECT_CF, graph, cv);
        addKeys(keys, p, PRED_CF, graph, cv);
        addKeys(keys, o, OBJ_CF, graph, cv);
        addKeys(keys, s + DELIM + p, SUBJECTPRED_CF, graph, cv);
        addKeys(keys, p + DELIM + o, PREDOBJECT_CF, graph, cv);
        addKeys(keys, s + DELIM + o, SUBJECTOBJECT_CF, graph, cv);
        return keys;
    }

    private static void addKeys(final List<CounterKey> keys, final String row, final String family, final String graph,
            final String cv) {
        keys.add(new CounterKey(row, family, "", cv));
        if (graph != null) {
            keys.add(new CounterKey(row, family, graph, cv));
        }
    }

    /**
     * Sums counter deltas in memory and writes one delta per counter. Not thread-safe: use one
     * per writer.
     */
    public static final class CounterBatch {
        private final Map<CounterKey, long[]> sums = new HashMap<>();

        public void add(final RyaStatement stmt, final long delta) {
            for (final CounterKey key : counterKeys(stmt)) {
                sums.computeIfAbsent(key, k -> new long[1])[0] += delta;
            }
        }

        /** The number of distinct counters pending. */
        public int size() {
            return sums.size();
        }

        /** The pending sums as mutations (one per row; zero sums are dropped), and clears them. */
        public List<RowMutation> drain() {
            final Map<String, RowMutation> byRow = new LinkedHashMap<>();
            for (final Map.Entry<CounterKey, long[]> e : sums.entrySet()) {
                final long sum = e.getValue()[0];
                if (sum == 0) {
                    continue;
                }
                final CounterKey key = e.getKey();
                final RowMutation m = byRow.computeIfAbsent(key.row(),
                        row -> new RowMutation(row.getBytes(StandardCharsets.UTF_8)));
                m.addToCounter(key.family().getBytes(StandardCharsets.UTF_8),
                        key.graph().isEmpty() ? EMPTY : key.graph().getBytes(StandardCharsets.UTF_8),
                        key.visibility().getBytes(StandardCharsets.ISO_8859_1), sum);
            }
            sums.clear();
            return new ArrayList<>(byRow.values());
        }
    }

    /**
     * @param stmt the statement added or removed.
     * @param add true for an insert (+1), false for a delete (-1).
     */
    public static List<RowMutation> counters(final RyaStatement stmt, final boolean add) {
        final CounterBatch batch = new CounterBatch();
        batch.add(stmt, add ? 1 : -1);
        return batch.drain();
    }

    /**
     * @return a marker saying the statement's graph has data, or null for the default graph.
     *         Writing it again is harmless.
     */
    public static RowMutation graphMarker(final RyaStatement stmt) {
        if (stmt.getContext() == null) {
            return null;
        }
        return new RowMutation(stmt.getContext().getData().getBytes(StandardCharsets.UTF_8))
                .put(GRAPH_CF_BYTES, EMPTY, stmt.getColumnVisibility(), EMPTY);
    }
}
