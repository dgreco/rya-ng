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
package io.github.dgreco.rya.accumulo;

import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.DELIM_BYTE;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PRED_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TYPE_DELIM_BYTE;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;

import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.api.persist.RyaDAOException;

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
 */
public final class GraphSizes {
    /** How long a list of predicates is reused before the po table is read again. */
    public static final long PREDICATES_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int THREADS = 4;

    private record Predicates(List<String> iris, long readAt) {
    }

    private final AccumuloClient client;
    private final LongSupplier clock;
    /** Predicate lists, by the authorizations they were read with. */
    private final Map<Set<String>, Predicates> predicates = new ConcurrentHashMap<>();

    public GraphSizes(final AccumuloClient client) {
        this(client, System::currentTimeMillis);
    }

    GraphSizes(final AccumuloClient client, final LongSupplier clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * @param conf the reader's configuration: its tables and authorizations
     * @return graph IRI to approximate number of statements, for every named graph with data the
     *         reader may see, sorted by IRI
     */
    public Map<String, Long> sizes(final AccumuloRdfConfiguration conf) {
        if (conf.isPrefixRowsWithHash()) {
            throw new UnsupportedOperationException("Graph sizes need the default table layout (tbl.hashprefix off)");
        }
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        final Authorizations auths = conf.getAuthorizations();
        final Map<String, Long> sizes = new TreeMap<>();
        try {
            final Set<String> graphs = graphs(tables.getGraphs(), auths);
            if (graphs.isEmpty()) {
                return sizes;
            }
            final List<String> preds = predicates(tables.getPo(), auths);
            if (preds.isEmpty()) {
                return sizes;
            }
            final List<Range> rows = new ArrayList<>(preds.size());
            for (final String p : preds) {
                rows.add(Range.exact(new Text(p.getBytes(StandardCharsets.UTF_8))));
            }
            try (BatchScanner scanner = client.createBatchScanner(tables.getEval(), auths, THREADS)) {
                scanner.setRanges(rows);
                scanner.fetchColumnFamily(PRED_CF_TXT);
                for (final Map.Entry<Key, Value> e : scanner) {
                    final String graph = e.getKey().getColumnQualifier().toString();
                    if (graphs.contains(graph)) {
                        sizes.merge(graph, Long.parseLong(new String(e.getValue().get(), StandardCharsets.UTF_8)), Long::sum);
                    }
                }
            }
        } catch (final TableNotFoundException e) {
            throw new RyaDAOException(e);
        }
        sizes.values().removeIf(n -> n <= 0);
        return sizes;
    }

    /** The graphs with a marker the reader may see. */
    private Set<String> graphs(final String table, final Authorizations auths) throws TableNotFoundException {
        final Set<String> graphs = new HashSet<>();
        try (Scanner scanner = client.createScanner(table, auths)) {
            scanner.fetchColumnFamily(EvalStatsMutations.GRAPH_CF_TXT);
            for (final Map.Entry<Key, Value> e : scanner) {
                graphs.add(e.getKey().getRow().toString());
            }
        }
        return graphs;
    }

    private List<String> predicates(final String poTable, final Authorizations auths) throws TableNotFoundException {
        final Set<String> key = new TreeSet<>();
        for (final byte[] a : auths.getAuthorizations()) {
            key.add(new String(a, StandardCharsets.UTF_8));
        }
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
    private List<String> readPredicates(final String poTable, final Authorizations auths) throws TableNotFoundException {
        final List<String> out = new ArrayList<>();
        try (Scanner scanner = client.createScanner(poTable, auths)) {
            scanner.setBatchSize(1);
            Text start = null;
            while (true) {
                scanner.setRange(new Range(start, true, null, false));
                final Iterator<Map.Entry<Key, Value>> it = scanner.iterator();
                if (!it.hasNext()) {
                    return out;
                }
                final byte[] row = it.next().getKey().getRowData().toArray();
                int end = 0;
                while (end < row.length && row[end] != DELIM_BYTE) {
                    end++;
                }
                out.add(new String(row, 0, end, StandardCharsets.UTF_8));
                // This predicate's rows all start with "predicate 00"; the next predicate's rows
                // start at or after "predicate 01".
                final byte[] next = Arrays.copyOf(row, end + 1);
                next[end] = TYPE_DELIM_BYTE;
                start = new Text(next);
            }
        }
    }
}
