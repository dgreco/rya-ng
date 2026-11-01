package io.github.dgreco.rya.store;

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
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.DELIM;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.OBJ_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PREDOBJECT_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PRED_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTOBJECT_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTPRED_CF;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECT_CF;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;

import io.github.dgreco.rya.api.persist.RdfDAOException;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.spi.TableKind;
import io.github.dgreco.rya.store.trace.StoreTrace;

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

    @Override
    public double getCardinality(StoreRdfConfiguration conf, final CARDINALITY_OF card, final List<Value> val,
            final Resource context) {
        if (conf == null) {
            conf = this.conf;
        }
        final String cf = switch (card) {
            case SUBJECT -> SUBJECT_CF;
            case PREDICATE -> PRED_CF;
            case OBJECT -> OBJ_CF;
            case SUBJECTPREDICATE -> SUBJECTPRED_CF;
            case PREDICATEOBJECT -> PREDOBJECT_CF;
            case SUBJECTOBJECT -> SUBJECTOBJECT_CF;
        };
        final byte[] cq = context == null ? EMPTY : context.stringValue().getBytes(StandardCharsets.UTF_8);
        final StringJoiner row = new StringJoiner(DELIM);
        for (final Value v : val) {
            row.add(v.stringValue());
        }
        final ScanSpec spec = ScanSpec.builder(conf.getStoreAuths()).column(cf.getBytes(StandardCharsets.UTF_8), cq).build();
        final StoreTrace.Reason why = StoreTrace.reason(() -> "statistics: " + card + " count of " + row.toString().replace(DELIM, " · ")
                + (context == null ? "" : " in <" + context.stringValue() + ">"));
        try (why; CellScanner scanner = store.scan(evalTable, KeyRange.row(row.toString().getBytes(StandardCharsets.UTF_8)), spec)) {
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
