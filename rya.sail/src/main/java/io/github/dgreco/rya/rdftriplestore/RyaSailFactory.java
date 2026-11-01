package io.github.dgreco.rya.rdftriplestore;

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

import static java.util.Objects.requireNonNull;

import org.eclipse.rdf4j.repository.sail.SailRepository;

import io.github.dgreco.rya.rdftriplestore.inference.InferenceEngine;
import io.github.dgreco.rya.store.RyaStoreDAO;
import io.github.dgreco.rya.store.RyaStoreEvalStatsDAO;
import io.github.dgreco.rya.store.StoreRdfConfiguration;
import io.github.dgreco.rya.store.spi.Store;

/**
 * Builds a Rya sail on a {@link Store}: the DAO, the statistics reader (when
 * {@code query.usestats} is true, the default) and the inference engine (when
 * {@code query.infer} is true). The tables are named after the configuration's table prefix
 * and are created if missing.
 *
 * <pre>
 * StoreRdfConfiguration conf = new StoreRdfConfiguration();
 * conf.setTablePrefix("rya_");
 * SailRepository repo = RyaSailFactory.createRepository(AccumuloStore.wrap(client), conf);
 * repo.init();
 * </pre>
 *
 * <p>The caller keeps ownership of the {@link Store} and closes it after shutting down the sail
 * or repository.
 */
public final class RyaSailFactory {

    private RyaSailFactory() {
    }

    public static RdfCloudTripleStore<StoreRdfConfiguration> createSail(final Store store, final StoreRdfConfiguration conf) {
        requireNonNull(store, "store");
        requireNonNull(conf, "conf");
        final RdfCloudTripleStore<StoreRdfConfiguration> sail = new RdfCloudTripleStore<>();
        sail.setConf(conf);
        sail.setRyaDAO(new RyaStoreDAO(store, conf));
        if (conf.isUseStats()) {
            sail.setRdfEvalStatsDAO(new RyaStoreEvalStatsDAO(store, conf));
        }
        if (conf.isInfer()) {
            sail.setInferenceEngine(new InferenceEngine());
        }
        return sail;
    }

    public static SailRepository createRepository(final Store store, final StoreRdfConfiguration conf) {
        return new SailRepository(createSail(store, conf));
    }
}
