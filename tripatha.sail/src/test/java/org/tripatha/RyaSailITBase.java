package org.tripatha;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;

import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.IndexVerifier;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.rdftriplestore.RdfCloudTripleStore;
import org.tripatha.rdftriplestore.RyaSailFactory;
import org.tripatha.rdftriplestore.inference.InferenceEngine;
import org.tripatha.test.StoreITBase;

/**
 * Helpers for sail tests on the shared test cluster of the backend named by
 * {@code -Dtripatha.test.backend}. Every object built here uses the test's unique table prefix.
 */
public abstract class RyaSailITBase extends StoreITBase {

    /**
     * @return a configuration that points at this test's tables. System properties change every
     *         test that uses it: {@code -Dtripatha.test.joinBatchSize=2} sets the join batch size, and
     *         {@code -Dtripatha.test.indexes=true} turns on the text, geo and entity indexes (each test then
     *         checks that the indexes match the stored statements), and
     *         {@code -Dtripatha.test.mergeRatio=1000000} merges every star of constant patterns
     *         (O22), whatever their lengths, {@code -Dtripatha.test.mergeSingle=1} reads every
     *         star pattern on its own as a merge of one list (O7a), and
     *         {@code -Dtripatha.test.lookupThreads=3} reads every batch of disjoint lookups on
     *         three threads, one lookup each at least, and {@code -Dtripatha.test.prefetchAhead=true}
     *         fetches an entity prefetch's next window while the current one is joined (O16).
     */
    protected StoreRdfConfiguration newConf() {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        final String batchSize = System.getProperty("tripatha.test.joinBatchSize");
        if (batchSize != null && !batchSize.isBlank()) {
            conf.setJoinBatchSize(Integer.parseInt(batchSize.trim()));
        }
        final String mergeRatio = System.getProperty("tripatha.test.mergeRatio");
        if (mergeRatio != null && !mergeRatio.isBlank()) {
            conf.setJoinMergeRatio(Double.parseDouble(mergeRatio.trim()));
        }
        // Every batch of disjoint lookups on that many threads, one lookup each at least.
        final String lookupThreads = System.getProperty("tripatha.test.lookupThreads");
        if (lookupThreads != null && !lookupThreads.isBlank()) {
            conf.setJoinLookupThreads(Integer.parseInt(lookupThreads.trim()));
            conf.setJoinLookupThreadKeys(1);
        }
        final String prefetchAhead = System.getProperty("tripatha.test.prefetchAhead");
        if (prefetchAhead != null && !prefetchAhead.isBlank()) {
            conf.setJoinPrefetchAhead(Boolean.parseBoolean(prefetchAhead.trim()));
        }
        // query.join.countunused (SCALE-STUDY.md O26), on by default: false runs every test without counts.
        final String countUnused = System.getProperty("tripatha.test.countUnused");
        if (countUnused != null && !countUnused.isBlank()) {
            conf.setJoinCountUnused(Boolean.parseBoolean(countUnused.trim()));
        }
        final String mergeSingle = System.getProperty("tripatha.test.mergeSingle");
        if (mergeSingle != null && !mergeSingle.isBlank()) {
            conf.setJoinMergeSingle(Long.parseLong(mergeSingle.trim()));
        }
        if (Boolean.getBoolean("tripatha.test.indexes")) {
            conf.setTextIndex(true);
            conf.setGeoIndex(true);
            conf.setEntityIndex(true);
            conf.setEntityIndexShards(4);
        }
        return conf;
    }

    /** Every index table of the test must hold exactly the entries its statements call for. */
    @AfterEach
    public void verifyIndexes() throws Exception {
        final StoreRdfConfiguration conf = newConf();
        if (getStore() == null || !getStore().tables().exists(conf.getTableLayoutStrategy().getSpo())) {
            return;
        }
        for (final IndexType type : IndexType.values()) {
            if (getStore().tables().exists(type.table(conf.getTableLayoutStrategy()))) {
                final IndexVerifier.Report report = IndexVerifier.verify(getStore(), conf, type);
                assertTrue(report.isClean(), type + " index out of step: " + report);
            }
        }
        // The text index's counts may be above its postings (a statement added twice), never below (O6).
        final IndexVerifier.Report counts = IndexVerifier.verifyTextCounts(getStore(), conf);
        if (counts != null) {
            assertTrue(counts.missing().isEmpty(), "text counts below their postings: " + counts.missing());
        }
    }

    /** @return an initialized DAO on this test's tables. */
    protected RyaStoreDAO newDao(final StoreRdfConfiguration conf) {
        final RyaStoreDAO dao = new RyaStoreDAO(getStore(), conf);
        dao.init();
        return dao;
    }

    /**
     * @return a store (not yet initialized) with statistics, and with an inference engine
     *         refreshing every few seconds when {@code inference} is true.
     */
    protected RdfCloudTripleStore<StoreRdfConfiguration> newStore(final StoreRdfConfiguration conf,
            final boolean inference) {
        conf.setInfer(inference);
        final RdfCloudTripleStore<StoreRdfConfiguration> store =
                RyaSailFactory.createSail(getStore(), conf);
        if (inference) {
            final InferenceEngine engine = new InferenceEngine();
            engine.setRefreshGraphSchedule(5000);
            store.setInferenceEngine(engine);
        }
        return store;
    }
}
