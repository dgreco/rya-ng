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

import org.apache.accumulo.core.client.AccumuloClient;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRdfEvalStatsDAO;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceEngine;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Builds a Rya sail on Accumulo: the DAO, the statistics reader (when
 * {@code query.usestats} is true, the default) and the inference engine (when
 * {@code query.infer} is true). The tables are named after the configuration's table prefix
 * and are created if missing.
 *
 * <pre>
 * AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
 * conf.setTablePrefix("rya_");
 * SailRepository repo = RyaAccumuloSailFactory.createRepository(client, conf);
 * repo.init();
 * </pre>
 *
 * <p>The caller keeps ownership of the {@link AccumuloClient} and closes it after shutting
 * down the sail or repository.
 */
public final class RyaAccumuloSailFactory {

    private RyaAccumuloSailFactory() {
    }

    public static RdfCloudTripleStore<AccumuloRdfConfiguration> createSail(final AccumuloClient client,
            final AccumuloRdfConfiguration conf) {
        requireNonNull(client, "client");
        requireNonNull(conf, "conf");

        final RdfCloudTripleStore<AccumuloRdfConfiguration> store = new RdfCloudTripleStore<>();
        store.setConf(conf);
        store.setRyaDAO(new AccumuloRyaDAO(client, conf));
        if (conf.isUseStats()) {
            store.setRdfEvalStatsDAO(new AccumuloRdfEvalStatsDAO(client, conf));
        }
        if (conf.isInfer()) {
            store.setInferenceEngine(new InferenceEngine());
        }
        return store;
    }

    public static SailRepository createRepository(final AccumuloClient client, final AccumuloRdfConfiguration conf) {
        return new SailRepository(createSail(client, conf));
    }
}
