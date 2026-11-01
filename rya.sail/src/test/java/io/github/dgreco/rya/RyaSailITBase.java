package io.github.dgreco.rya;

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

import io.github.dgreco.rya.store.StoreRdfConfiguration;
import io.github.dgreco.rya.store.index.IndexType;
import io.github.dgreco.rya.store.index.IndexVerifier;
import io.github.dgreco.rya.store.RyaStoreDAO;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import io.github.dgreco.rya.rdftriplestore.RyaSailFactory;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceEngine;
import io.github.dgreco.rya.test.StoreITBase;

/**
 * Helpers for sail tests on the shared test cluster of the backend named by
 * {@code -Drya.test.backend}. Every object built here uses the test's unique table prefix.
 */
public abstract class RyaSailITBase extends StoreITBase {

    /**
     * @return a configuration that points at this test's tables. System properties change every
     *         test that uses it: {@code -Drya.test.joinBatchSize=2} sets the join batch size, and
     *         {@code -Drya.test.indexes=true} turns on the text and geo indexes (each test then
     *         checks that the indexes match the stored statements).
     */
    protected StoreRdfConfiguration newConf() {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        final String batchSize = System.getProperty("rya.test.joinBatchSize");
        if (batchSize != null && !batchSize.isBlank()) {
            conf.setJoinBatchSize(Integer.parseInt(batchSize.trim()));
        }
        if (Boolean.getBoolean("rya.test.indexes")) {
            conf.setTextIndex(true);
            conf.setGeoIndex(true);
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
