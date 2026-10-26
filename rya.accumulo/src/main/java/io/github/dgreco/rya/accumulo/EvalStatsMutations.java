package io.github.dgreco.rya.accumulo;

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

import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.DELIM;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.EMPTY_TEXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.OBJ_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PREDOBJECT_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PRED_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTOBJECT_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTPRED_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECT_CF_TXT;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.Combiner;
import org.apache.accumulo.core.iterators.LongCombiner;
import org.apache.accumulo.core.iterators.user.SummingCombiner;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import io.github.dgreco.rya.api.domain.RyaStatement;

/**
 * Builds the cardinality-counter mutations for the eval table, and the named-graph markers.
 *
 * <p>Each statement adds +1 (or -1 on delete) to six counters: its subject, predicate, object,
 * subject+predicate, predicate+object and subject+object. The row is the values joined with
 * {@code DELIM}, the column family says which combination it is, and the column qualifier is
 * empty (all graphs) plus, for a named-graph statement, a second cell with the graph IRI.
 * The cell carries the statement's visibility, so a reader only counts what it may see.
 * Accumulo's {@link SummingCombiner} adds the cells up server side.
 *
 * <p>A {@link CounterBatch} sums the counters of many statements first and writes one cell per
 * counter with the total, which the combiner adds up exactly like the individual +1 cells.
 *
 * <p>The counts drive join ordering only, so they may drift (re-inserting an existing
 * statement counts it twice; dropping a graph does not adjust the all-graphs counters).
 */
public final class EvalStatsMutations {
    /** Name of the combiner attached to the eval table. */
    public static final String COMBINER_NAME = "ryaStatsSum";
    /** Combiners must run before the default VersioningIterator (priority 20). */
    public static final int COMBINER_PRIORITY = 10;

    /** Column family of the named-graph marker rows. */
    public static final Text GRAPH_CF_TXT = new Text("g");

    private EvalStatsMutations() {
    }

    /**
     * One counter cell.
     *
     * @param row the counted values joined with {@code DELIM}
     * @param family which combination is counted (subject, predicate, ...)
     * @param graph the graph IRI, or empty for the all-graphs counter
     * @param visibility the counted statement's visibility
     */
    public record CounterKey(String row, Text family, String graph, ColumnVisibility visibility) {
    }

    /** The counters a statement adds to: six, plus six more for a named-graph statement. */
    public static List<CounterKey> counterKeys(final RyaStatement stmt) {
        final String s = stmt.getSubject().getData();
        final String p = stmt.getPredicate().getData();
        final String o = stmt.getObject().getData();
        final String graph = stmt.getContext() == null ? null : stmt.getContext().getData();
        final ColumnVisibility cv = visibility(stmt);
        final List<CounterKey> keys = new ArrayList<>(graph == null ? 6 : 12);
        addKeys(keys, s, SUBJECT_CF_TXT, graph, cv);
        addKeys(keys, p, PRED_CF_TXT, graph, cv);
        addKeys(keys, o, OBJ_CF_TXT, graph, cv);
        addKeys(keys, s + DELIM + p, SUBJECTPRED_CF_TXT, graph, cv);
        addKeys(keys, p + DELIM + o, PREDOBJECT_CF_TXT, graph, cv);
        addKeys(keys, s + DELIM + o, SUBJECTOBJECT_CF_TXT, graph, cv);
        return keys;
    }

    private static void addKeys(final List<CounterKey> keys, final String row, final Text family, final String graph,
            final ColumnVisibility cv) {
        keys.add(new CounterKey(row, family, "", cv));
        if (graph != null) {
            keys.add(new CounterKey(row, family, graph, cv));
        }
    }

    /**
     * Sums counter deltas in memory and writes one cell per counter. Not thread-safe: use one
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
        public List<Mutation> drain() {
            final Map<String, Mutation> byRow = new LinkedHashMap<>();
            for (final Map.Entry<CounterKey, long[]> e : sums.entrySet()) {
                final long sum = e.getValue()[0];
                if (sum == 0) {
                    continue;
                }
                final CounterKey key = e.getKey();
                final Mutation m = byRow.computeIfAbsent(key.row(),
                        row -> new Mutation(new Text(row.getBytes(StandardCharsets.UTF_8))));
                m.put(key.family(), key.graph().isEmpty() ? EMPTY_TEXT : new Text(key.graph()), key.visibility(),
                        new Value(Long.toString(sum).getBytes(StandardCharsets.UTF_8)));
            }
            sums.clear();
            return new ArrayList<>(byRow.values());
        }
    }

    /** @return the combiner setting that sums the string-encoded counters. */
    public static IteratorSetting combinerSetting() {
        final IteratorSetting setting = new IteratorSetting(COMBINER_PRIORITY, COMBINER_NAME, SummingCombiner.class);
        LongCombiner.setEncodingType(setting, LongCombiner.Type.STRING);
        Combiner.setCombineAllColumns(setting, true);
        return setting;
    }

    /**
     * @param stmt the statement added or removed.
     * @param add true for an insert (+1), false for a delete (-1).
     */
    public static List<Mutation> counters(final RyaStatement stmt, final boolean add) {
        final CounterBatch batch = new CounterBatch();
        batch.add(stmt, add ? 1 : -1);
        return batch.drain();
    }

    /**
     * @return a marker saying the statement's graph has data, or null for the default graph.
     *         Writing it again is harmless.
     */
    public static Mutation graphMarker(final RyaStatement stmt) {
        if (stmt.getContext() == null) {
            return null;
        }
        final Mutation m = new Mutation(new Text(stmt.getContext().getData()));
        m.put(GRAPH_CF_TXT, EMPTY_TEXT, visibility(stmt), new Value(new byte[0]));
        return m;
    }

    private static ColumnVisibility visibility(final RyaStatement stmt) {
        final byte[] cv = stmt.getColumnVisibility();
        return cv == null ? new ColumnVisibility() : new ColumnVisibility(cv);
    }
}
