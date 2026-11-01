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
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import io.github.dgreco.rya.accumulo.AccumuloClients;
import io.github.dgreco.rya.accumulo.AccumuloStore;
import io.github.dgreco.rya.hbase.HBaseLabels;
import io.github.dgreco.rya.hbase.HBaseStore;
import io.github.dgreco.rya.hbase.HBaseStoreProvider;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import io.github.dgreco.rya.rdftriplestore.RyaSailFactory;
import io.github.dgreco.rya.store.StoreRdfConfiguration;
import io.github.dgreco.rya.store.spi.Store;

/** Wires the store of the configured backend ({@code rya.backend}) and the Rya repository on it. */
@Configuration(proxyBeanMethods = false)
public class RyaStoreConfiguration {

    /** The store; its operations report to the request's trace, if any. */
    @Bean(destroyMethod = "close")
    Store ryaStore(final RyaProperties props) {
        return switch (props.backend().trim().toLowerCase(java.util.Locale.ROOT)) {
            case "accumulo" -> AccumuloStore.owning(accumuloClient(props));
            case "hbase" -> hbaseStore(props);
            default -> throw new IllegalStateException("rya.backend must be accumulo or hbase, not " + props.backend());
        };
    }

    private static AccumuloClient accumuloClient(final RyaProperties props) {
        final RyaProperties.Accumulo acc = props.accumulo();
        if (acc == null) {
            throw new IllegalStateException("Set rya.accumulo.client-properties, or rya.accumulo.instance/zookeepers/user/password");
        }
        if (StringUtils.hasText(acc.clientProperties())) {
            // The file may leave the password out; rya.accumulo.password (RYA_ACCUMULO_PASSWORD) fills it in.
            return AccumuloClients.fromFile(Path.of(acc.clientProperties()), acc.password());
        }
        return Accumulo.newClient()
                .to(required(acc.instance(), "rya.accumulo.instance"), required(acc.zookeepers(), "rya.accumulo.zookeepers"))
                .as(required(acc.user(), "rya.accumulo.user"), required(acc.password(), "rya.accumulo.password"))
                .build();
    }

    /**
     * An HBase store, refused at startup when its account can't read every configured label: HBase
     * would silently return fewer rows, where Accumulo rejects the request.
     */
    private static Store hbaseStore(final RyaProperties props) {
        final RyaProperties.HBase hb = props.hbase();
        final Properties settings = new Properties();
        settings.setProperty(HBaseStoreProvider.SITE, String.join(",", hb.site()));
        for (final Map.Entry<String, String> e : hb.conf().entrySet()) {
            settings.setProperty(HBaseStoreProvider.CONF_PREFIX + e.getKey(), e.getValue());
        }
        settings.setProperty(HBaseStoreProvider.LABELS_TABLE, hb.labelsTable());
        settings.setProperty(HBaseStoreProvider.COUNTERS, hb.counters());
        if (StringUtils.hasText(hb.countersJar())) {
            settings.setProperty(HBaseStoreProvider.COUNTERS_JAR, hb.countersJar());
        }
        if (StringUtils.hasText(hb.user())) {
            settings.setProperty(HBaseStoreProvider.USER, hb.user());
        }
        final HBaseStore store = (HBaseStore) new HBaseStoreProvider().open(settings);
        final List<String> configured = props.sparql().authorizations();
        if (!configured.isEmpty()) {
            final List<String> missing = HBaseLabels.missingCanaries(store, hb.labelsTable(), configured);
            if (!missing.isEmpty()) {
                store.close();
                throw new IllegalStateException("rya.sparql.authorizations names labels that " + store.access().whoami()
                        + " can't read on HBase: " + missing + ". Register and grant them with the loader's labels command "
                        + "(it also writes the canaries this check reads).");
            }
        }
        return store;
    }

    @Bean
    StoreRdfConfiguration ryaConfiguration(final RyaProperties props) {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(props.tablePrefix());
        conf.setInfer(props.inference());
        conf.setUseStats(props.stats());
        conf.setMaintainStats(props.stats());
        conf.setJoinBatchSize(props.joinBatchSize());
        conf.setTextIndex(props.textIndex());
        conf.setTextIndexPredicates(props.textIndexPredicates());
        conf.setGeoIndex(props.geoIndex());
        conf.setRebuildIndexes(props.rebuildIndexes());
        conf.setAuths(props.sparql().authorizations().toArray(new String[0]));
        if (StringUtils.hasText(props.sparql().defaultVisibility())) {
            conf.setCv(props.sparql().defaultVisibility());
        }
        return conf;
    }

    @Bean(initMethod = "init", destroyMethod = "shutDown")
    SailRepository ryaRepository(final Store store, final StoreRdfConfiguration conf, final RyaProperties props) {
        final RdfCloudTripleStore<StoreRdfConfiguration> sail = RyaSailFactory.createSail(store, conf);
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
