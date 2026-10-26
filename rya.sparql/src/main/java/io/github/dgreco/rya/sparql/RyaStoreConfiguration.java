package io.github.dgreco.rya.sparql;

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

import java.nio.file.Path;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import io.github.dgreco.rya.rdftriplestore.RyaAccumuloSailFactory;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Wires the Accumulo client and the Rya repository. */
@Configuration(proxyBeanMethods = false)
public class RyaStoreConfiguration {

    @Bean(destroyMethod = "close")
    AccumuloClient accumuloClient(final RyaProperties props) {
        final RyaProperties.Accumulo acc = props.accumulo();
        if (acc == null) {
            throw new IllegalStateException("Set rya.accumulo.client-properties, or rya.accumulo.instance/zookeepers/user/password");
        }
        if (StringUtils.hasText(acc.clientProperties())) {
            return Accumulo.newClient().from(Path.of(acc.clientProperties())).build();
        }
        return Accumulo.newClient()
                .to(required(acc.instance(), "rya.accumulo.instance"), required(acc.zookeepers(), "rya.accumulo.zookeepers"))
                .as(required(acc.user(), "rya.accumulo.user"), required(acc.password(), "rya.accumulo.password"))
                .build();
    }

    @Bean
    AccumuloRdfConfiguration ryaConfiguration(final RyaProperties props) {
        final AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
        conf.setTablePrefix(props.tablePrefix());
        conf.setInfer(props.inference());
        conf.setUseStats(props.stats());
        conf.setMaintainStats(props.stats());
        conf.setJoinBatchSize(props.joinBatchSize());
        conf.setAuths(props.sparql().authorizations().toArray(new String[0]));
        if (StringUtils.hasText(props.sparql().defaultVisibility())) {
            conf.setCv(props.sparql().defaultVisibility());
        }
        return conf;
    }

    @Bean(initMethod = "init", destroyMethod = "shutDown")
    SailRepository ryaRepository(final AccumuloClient client, final AccumuloRdfConfiguration conf,
            final RyaProperties props) {
        final RdfCloudTripleStore<AccumuloRdfConfiguration> sail = RyaAccumuloSailFactory.createSail(client, conf);
        if (sail.getInferenceEngine() != null) {
            sail.getInferenceEngine().setRefreshGraphSchedule(props.inferenceRefresh().toMillis());
        }
        return new SailRepository(sail);
    }

    private static String required(final String value, final String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
