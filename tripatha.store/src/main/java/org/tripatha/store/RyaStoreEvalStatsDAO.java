package org.tripatha.store;

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

import static com.google.common.base.Preconditions.checkNotNull;
import static org.tripatha.api.RdfCloudTripleStoreConstants.DELIM;
import static org.tripatha.api.RdfCloudTripleStoreConstants.OBJ_CF;
import static org.tripatha.api.RdfCloudTripleStoreConstants.PREDOBJECT_CF;
import static org.tripatha.api.RdfCloudTripleStoreConstants.PRED_CF;
import static org.tripatha.api.RdfCloudTripleStoreConstants.SUBJECTOBJECT_CF;
import static org.tripatha.api.RdfCloudTripleStoreConstants.SUBJECTPRED_CF;
import static org.tripatha.api.RdfCloudTripleStoreConstants.SUBJECT_CF;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;

import org.tripatha.api.resolver.RdfToRyaConversions;
import org.tripatha.api.persist.RdfDAOException;
import org.tripatha.api.persist.RdfEvalStatsDAO;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.trace.StoreTrace;

/**
 * Reads the cardinality counters that {@link RyaStoreDAO} keeps in the eval table
 * (see {@link EvalStatsMutations}). Returns -1 for a value combination that was never written.
 */
public class RyaStoreEvalStatsDAO implements RdfEvalStatsDAO<StoreRdfConfiguration> {
    private static final byte[] EMPTY = new byte[0];

    private final AtomicBoolean isInitialized = new AtomicBoolean();

    private StoreRdfConfiguration conf = new StoreRdfConfiguration();
    private Store store;
    private String evalTable;
    private GraphSizes graphSizes;

    public RyaStoreEvalStatsDAO() {
    }

    public RyaStoreEvalStatsDAO(final Store store, final StoreRdfConfiguration conf) {
        this.store = store;
        this.conf = conf;
    }

    @Override
    public void init() {
        if (isInitialized.get()) {
            return;
        }
        checkNotNull(store, "store");
        try {
            evalTable = conf.getTableLayoutStrategy().getEval();
            store.tables().create(evalTable, TableKind.COUNTERS);
            graphSizes = new GraphSizes(store);
            isInitialized.set(true);
        } catch (final Exception e) {
            throw new RdfDAOException(e);
        }
    }

    @Override
    public void destroy() {
        isInitialized.set(false);
    }

    @Override
    public boolean isInitialized() {
        return isInitialized.get();
    }

    /**
     * Approximate number of statements in each named graph that has data the configuration's
     * authorizations can see, from the counters; see {@link GraphSizes}.
     *
     * @param conf the reader's configuration, or null for this DAO's
     * @return graph IRI to approximate number of statements, sorted by IRI
     */
    public Map<String, Long> graphSizes(final StoreRdfConfiguration conf) {
        if (graphSizes == null) {
            throw new IllegalStateException("The statistics DAO is not initialized");
        }
        return graphSizes.sizes(conf != null ? conf : this.conf);
    }

    /**
     * Approximate number of {@code rdf:type} statements of each class, from the counters; see
     * {@link GraphSizes#classSizes}.
     *
     * @param classes the classes to count, or null for every class
     */
    public Map<String, Long> classSizes(final StoreRdfConfiguration conf, final Set<String> classes) {
        if (graphSizes == null) {
            throw new IllegalStateException("The statistics DAO is not initialized");
        }
        return graphSizes.classSizes(conf != null ? conf : this.conf, classes);
    }

    /**
     * Approximate number of statements of each predicate, from the counters; see
     * {@link GraphSizes#predicateSizes}.
     *
     * @param predicates the predicates to count, or null for every one
     */
    public Map<String, Long> predicateSizes(final StoreRdfConfiguration conf, final Set<String> predicates) {
        if (graphSizes == null) {
            throw new IllegalStateException("The statistics DAO is not initialized");
        }
        return graphSizes.predicateSizes(conf != null ? conf : this.conf, predicates);
    }

    public Store getStore() {
        return store;
    }

    public void setStore(final Store store) {
        this.store = store;
    }

    @Override
    public StoreRdfConfiguration getConf() {
        return conf;
    }

    @Override
    public void setConf(final StoreRdfConfiguration conf) {
        this.conf = conf;
    }

    @Override
    public double getCardinality(final StoreRdfConfiguration conf, final CARDINALITY_OF card, final List<Value> val) {
        return getCardinality(conf, card, val, null);
    }

    private static String family(final CARDINALITY_OF card) {
        return switch (card) {
            case SUBJECT -> SUBJECT_CF;
            case PREDICATE -> PRED_CF;
            case OBJECT -> OBJ_CF;
            case SUBJECTPREDICATE -> SUBJECTPRED_CF;
            case PREDICATEOBJECT -> PREDOBJECT_CF;
            case SUBJECTOBJECT -> SUBJECTOBJECT_CF;
        };
    }

    private static String row(final List<Value> values) {
        final StringJoiner row = new StringJoiner(DELIM);
        for (final Value v : values) {
            // A triple term is counted under its encoded form, which is what the writer has.
            row.add(v.isTripleTerm() ? RdfToRyaConversions.convertValue(v).getData() : v.stringValue());
        }
        return row.toString();
    }

    /**
     * Every counter in one batch scan, where reading them one by one would be a round trip each
     * (SCALE-STUDY.md M14: 0.4 to 0.65 ms a read on the Accumulo test cluster): one row per read,
     * the reads' columns, and each cell added to the reads it is the counter of.
     */
    @Override
    public Map<CounterRead, Double> getCardinalities(StoreRdfConfiguration conf, final Collection<CounterRead> reads) {
        if (conf == null) {
            conf = this.conf;
        }
        final Map<CounterRead, Double> out = new LinkedHashMap<>();
        if (reads.isEmpty()) {
            return out;
        }
        final Map<String, List<CounterRead>> byCell = new HashMap<>();
        final Set<String> rows = new java.util.TreeSet<>();
        final ScanSpec.Builder spec = ScanSpec.builder(conf.getStoreAuths()).threads(4);
        final Set<String> columns = new HashSet<>();
        for (final CounterRead r : reads) {
            final String row = row(r.values());
            final String cf = family(r.card());
            final String cq = r.context() == null ? "" : r.context().stringValue();
            rows.add(row);
            byCell.computeIfAbsent(row + DELIM + cf + DELIM + cq, k -> new ArrayList<>()).add(r);
            if (columns.add(cf + DELIM + cq)) {
                spec.column(cf.getBytes(StandardCharsets.UTF_8), cq.getBytes(StandardCharsets.UTF_8));
            }
        }
        final List<KeyRange> ranges = new ArrayList<>(rows.size());
        for (final String row : rows) {
            ranges.add(KeyRange.row(row.getBytes(StandardCharsets.UTF_8)));
        }
        final Map<String, long[]> totals = new HashMap<>();
        final StoreTrace.Reason why = StoreTrace.reason(() -> "statistics: " + reads.size() + " counters read together for planning");
        try (why; CellScanner scanner = store.batchScan(evalTable, ranges, spec.build())) {
            while (scanner.hasNext()) {
                final Cell c = scanner.next();
                final String key = new String(c.row(), StandardCharsets.UTF_8) + DELIM + new String(c.family(), StandardCharsets.UTF_8)
                        + DELIM + new String(c.qualifier(), StandardCharsets.UTF_8);
                // One cell per visibility label; add up the ones this reader can see.
                totals.computeIfAbsent(key, k -> new long[1])[0] += store.counterValue(c.value());
            }
        } catch (final Exception e) {
            throw new RdfDAOException(e);
        }
        for (final Map.Entry<String, List<CounterRead>> e : byCell.entrySet()) {
            final long[] total = totals.get(e.getKey());
            for (final CounterRead r : e.getValue()) {
                out.put(r, total == null ? -1 : (double) Math.max(total[0], 0));
            }
        }
        return out;
    }

    @Override
    public double getCardinality(StoreRdfConfiguration conf, final CARDINALITY_OF card, final List<Value> val,
            final Resource context) {
        if (conf == null) {
            conf = this.conf;
        }
        final String cf = family(card);
        final byte[] cq = context == null ? EMPTY : context.stringValue().getBytes(StandardCharsets.UTF_8);
        final String row = row(val);
        final ScanSpec spec = ScanSpec.builder(conf.getStoreAuths()).column(cf.getBytes(StandardCharsets.UTF_8), cq).build();
        final StoreTrace.Reason why = StoreTrace.reason(() -> "statistics: " + card + " count of " + row.replace(DELIM, " · ")
                + (context == null ? "" : " in <" + context.stringValue() + ">"));
        try (why; CellScanner scanner = store.scan(evalTable, KeyRange.row(row.getBytes(StandardCharsets.UTF_8)), spec)) {
            // One cell per visibility label; add up the ones this reader can see.
            long total = 0;
            boolean found = false;
            while (scanner.hasNext()) {
                total += store.counterValue(scanner.next().value());
                found = true;
            }
            return found ? Math.max(total, 0) : -1;
        } catch (final Exception e) {
            throw new RdfDAOException(e);
        }
    }
}
