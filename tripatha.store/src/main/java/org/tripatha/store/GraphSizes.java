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
package org.tripatha.store;

import static org.tripatha.api.RdfCloudTripleStoreConstants.DELIM_BYTE;
import static org.tripatha.api.RdfCloudTripleStoreConstants.PREDOBJECT_CF;
import static org.tripatha.api.RdfCloudTripleStoreConstants.PRED_CF;
import static org.tripatha.api.RdfCloudTripleStoreConstants.TYPE_DELIM_BYTE;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.trace.StoreTrace;

/**
 * Approximate number of statements in each named graph, read from the statistics counters
 * instead of from the statements.
 * <p>
 * Every statement in a named graph adds 1 to the counter of its predicate for that graph (eval
 * table, family {@code p}, qualifier = the graph IRI; see {@link EvalStatsMutations}). A graph's
 * size is the sum of those counters over all predicates. So this reads one counter row per
 * predicate, where counting the statements would read all of them.
 * <p>
 * The predicates are found by jumping from one predicate to the next in the po table, one seek
 * each. The list is reused for {@link #PREDICATES_TTL_MS} for each set of authorizations, so a
 * predicate written for the first time since then is not counted yet.
 * <p>
 * The sizes are as approximate as the counters: re-inserting a statement counts it twice.
 * Only graphs with a visible marker in the graphs table are listed, so a dropped graph is left
 * out even though its counters remain, and only counters the reader may see are added up.
 * <p>
 * The same counters give the size of a class ({@link #classSizes}: its {@code rdf:type}
 * statements, the all-graphs {@code po} counter) and of a predicate ({@link #predicateSizes}:
 * the all-graphs {@code p} counter), SCALE-STUDY.md O8. Neither family leaves out counters of 1.
 */
public final class GraphSizes {
    /** How long a list of predicates is reused before the po table is read again. */
    public static final long PREDICATES_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int THREADS = 4;

    private record Predicates(List<String> iris, long readAt) {
    }

    private final Store store;
    private final LongSupplier clock;
    /** Predicate lists, by the authorizations they were read with. */
    private final Map<Set<String>, Predicates> predicates = new ConcurrentHashMap<>();

    /** {@code rdf:type}, whose {@code po} counters are the classes' sizes. */
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final byte[] EMPTY = new byte[0];

    public GraphSizes(final Store store) {
        this(store, System::currentTimeMillis);
    }

    /**
     * Approximate number of {@code rdf:type} statements of each class, from the all-graphs
     * {@code po} counters of {@code rdf:type}, as stored: no inference.
     *
     * @param conf the reader's configuration: its tables and authorizations
     * @param classes the classes to count (one counter row each), or null for every class (one
     *        scan of {@code rdf:type}'s counter rows, one per class)
     * @return class IRI to approximate size, sorted; classes the reader sees no statement of are left out
     */
    public Map<String, Long> classSizes(final StoreRdfConfiguration conf, final Set<String> classes) {
        final byte[] prefix = (RDF_TYPE + (char) DELIM_BYTE).getBytes(StandardCharsets.UTF_8);
        final List<KeyRange> rows = new ArrayList<>();
        if (classes == null) {
            rows.add(KeyRange.prefix(prefix));
        } else {
            for (final String c : classes) {
                final byte[] cls = c.getBytes(StandardCharsets.UTF_8);
                final byte[] row = Arrays.copyOf(prefix, prefix.length + cls.length);
                System.arraycopy(cls, 0, row, prefix.length, cls.length);
                rows.add(KeyRange.row(row));
            }
        }
        return counters(conf, rows, PREDOBJECT_CF, prefix.length, "approximate counts: class sizes from the rdf:type counters");
    }

    /**
     * Approximate number of statements of each predicate, from its all-graphs {@code p} counter.
     *
     * @param conf the reader's configuration: its tables and authorizations
     * @param predicates the predicates to count (one counter row each), or null for every one (the
     *        predicates of {@code po}, listed one seek each and reused as for graph sizes)
     * @return predicate IRI to approximate size, sorted; predicates the reader sees nothing of are left out
     */
    public Map<String, Long> predicateSizes(final StoreRdfConfiguration conf, final Set<String> predicates) {
        final List<String> preds;
        if (predicates != null) {
            preds = new ArrayList<>(predicates);
        } else {
            if (conf.isPrefixRowsWithHash()) {
                throw new UnsupportedOperationException("Listing the predicates needs the default table layout (tbl.hashprefix off)");
            }
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "approximate counts: list the predicates (one seek each, unless cached)")) {
                preds = predicates(conf.getTableLayoutStrategy().getPo(), conf.getStoreAuths());
            }
        }
        final List<KeyRange> rows = new ArrayList<>(preds.size());
        for (final String p : preds) {
            rows.add(KeyRange.row(p.getBytes(StandardCharsets.UTF_8)));
        }
        return counters(conf, rows, PRED_CF, 0, "approximate counts: predicate sizes from the p counters");
    }

    /** The all-graphs counters of a family in these rows, added up per row (minus a prefix of {@code skip} bytes). */
    private Map<String, Long> counters(final StoreRdfConfiguration conf, final List<KeyRange> rows, final String family,
            final int skip, final String why) {
        final Map<String, Long> sizes = new TreeMap<>();
        if (rows.isEmpty()) {
            return sizes;
        }
        final ScanSpec spec = ScanSpec.builder(conf.getStoreAuths()).column(family.getBytes(StandardCharsets.UTF_8), EMPTY)
                .threads(THREADS).build();
        try (StoreTrace.Reason reason = StoreTrace.reason(() -> why);
                CellScanner scanner = store.batchScan(conf.getTableLayoutStrategy().getEval(), rows, spec)) {
            while (scanner.hasNext()) {
                final Cell c = scanner.next();
                // HBase's coprocessor mode keeps a counter's deltas as cells until a compaction: add them all.
                sizes.merge(new String(c.row(), skip, c.row().length - skip, StandardCharsets.UTF_8), store.counterValue(c.value()),
                        Long::sum);
            }
        }
        sizes.values().removeIf(n -> n <= 0);
        return sizes;
    }

    GraphSizes(final Store store, final LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * @param conf the reader's configuration: its tables and authorizations
     * @return graph IRI to approximate number of statements, for every named graph with data the
     *         reader may see, sorted by IRI
     */
    public Map<String, Long> sizes(final StoreRdfConfiguration conf) {
        if (conf.isPrefixRowsWithHash()) {
            throw new UnsupportedOperationException("Graph sizes need the default table layout (tbl.hashprefix off)");
        }
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        final Auths auths = conf.getStoreAuths();
        final Map<String, Long> sizes = new TreeMap<>();
        final Set<String> graphs;
        try (StoreTrace.Reason why = StoreTrace.reason(() -> "graph sizes: graphs with a marker")) {
            graphs = graphs(tables.getGraphs(), auths);
        }
        if (graphs.isEmpty()) {
            return sizes;
        }
        final List<String> preds;
        try (StoreTrace.Reason why = StoreTrace.reason(() -> "graph sizes: list the predicates (one seek each, unless cached)")) {
            preds = predicates(tables.getPo(), auths);
        }
        if (preds.isEmpty()) {
            return sizes;
        }
        final List<KeyRange> rows = new ArrayList<>(preds.size());
        for (final String p : preds) {
            rows.add(KeyRange.row(p.getBytes(StandardCharsets.UTF_8)));
        }
        final ScanSpec spec = ScanSpec.builder(auths).family(PRED_CF.getBytes(StandardCharsets.UTF_8)).threads(THREADS).build();
        try (StoreTrace.Reason why = StoreTrace.reason(() -> "graph sizes: predicate counters per graph");
                CellScanner scanner = store.batchScan(tables.getEval(), rows, spec)) {
            while (scanner.hasNext()) {
                final Cell c = scanner.next();
                final String graph = new String(c.qualifier(), StandardCharsets.UTF_8);
                if (graphs.contains(graph)) {
                    sizes.merge(graph, store.counterValue(c.value()), Long::sum);
                }
            }
        }
        sizes.values().removeIf(n -> n <= 0);
        return sizes;
    }

    /** The graphs with a marker the reader may see. */
    private Set<String> graphs(final String table, final Auths auths) {
        final Set<String> graphs = new HashSet<>();
        final ScanSpec spec = ScanSpec.builder(auths).family(EvalStatsMutations.GRAPH_CF_BYTES).build();
        try (CellScanner scanner = store.scan(table, KeyRange.all(), spec)) {
            while (scanner.hasNext()) {
                graphs.add(new String(scanner.next().row(), StandardCharsets.UTF_8));
            }
        }
        return graphs;
    }

    private List<String> predicates(final String poTable, final Auths auths) {
        final Set<String> key = new TreeSet<>(auths.labels());
        final Predicates cached = predicates.get(key);
        final long now = clock.getAsLong();
        if (cached != null && now - cached.readAt() < PREDICATES_TTL_MS) {
            return cached.iris();
        }
        final List<String> read = readPredicates(poTable, auths);
        predicates.put(key, new Predicates(read, now));
        return read;
    }

    /** The distinct predicates of the po table the reader may see: one seek per predicate. */
    private List<String> readPredicates(final String poTable, final Auths auths) {
        final List<String> out = new ArrayList<>();
        final ScanSpec spec = ScanSpec.builder(auths).batchSize(1).build();
        byte[] start = null;
        while (true) {
            final byte[] row;
            try (CellScanner scanner = store.scan(poTable, KeyRange.rows(start, true, null, false), spec)) {
                if (!scanner.hasNext()) {
                    return out;
                }
                row = scanner.next().row();
            }
            int end = 0;
            while (end < row.length && row[end] != DELIM_BYTE) {
                end++;
            }
            out.add(new String(row, 0, end, StandardCharsets.UTF_8));
            // This predicate's rows all start with "predicate 00"; the next predicate's rows
            // start at or after "predicate 01".
            final byte[] next = Arrays.copyOf(row, end + 1);
            next[end] = TYPE_DELIM_BYTE;
            start = next;
        }
    }
}
