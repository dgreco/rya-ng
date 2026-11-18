package org.tripatha.sparql;

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

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import org.tripatha.rdftriplestore.RdfCloudTripleStore;
import org.tripatha.rdftriplestore.RyaSailFactory;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.Stores;

/** Wires the store of the configured backend ({@code tripatha.backend}) and the Tripatha repository on it. */
@Configuration(proxyBeanMethods = false)
public class RyaStoreConfiguration {

    /**
     * The store, opened through the backend's {@link org.tripatha.store.spi.StoreProvider}: the
     * endpoint itself depends on no backend, so one can be left out of a build. Its operations
     * report to the request's trace, if any.
     */
    @Bean(destroyMethod = "close")
    Store ryaStore(final RyaProperties props) {
        final String backend = props.backend().trim().toLowerCase(java.util.Locale.ROOT);
        final Properties settings = switch (backend) {
            case "accumulo" -> accumuloSettings(props);
            case "hbase" -> hbaseSettings(props);
            case "rocksdb" -> rocksdbSettings(props);
            default -> throw new IllegalStateException("tripatha.backend must be accumulo, hbase or rocksdb, not " + props.backend());
        };
        final Store store;
        try {
            store = Stores.open(backend, settings);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("Can't open the " + backend + " store: " + e.getMessage(), e);
        }
        // A backend that drops unheld labels instead of refusing the read (HBase) would silently
        // return fewer rows: refuse to start instead.
        final List<String> unreadable = store.access().unreadableLabels(props.sparql().authorizations());
        if (!unreadable.isEmpty()) {
            store.close();
            throw new IllegalStateException("tripatha.sparql.authorizations names labels that " + store.access().whoami()
                    + " can't read on " + backend + ": " + unreadable + ". Register and grant them with the loader's labels "
                    + "command (it also writes the canaries this check reads).");
        }
        return store;
    }

    // The keys below are those of each backend's StoreProvider. They are spelled out here, not
    // taken from the providers' constants, so that this class compiles without the backends.

    private static Properties accumuloSettings(final RyaProperties props) {
        final RyaProperties.Accumulo acc = props.accumulo();
        if (acc == null) {
            throw new IllegalStateException("Set tripatha.accumulo.client-properties, or tripatha.accumulo.instance/zookeepers/user/password");
        }
        final Properties settings = new Properties();
        // The file may leave the password out; tripatha.accumulo.password (TRIPATHA_ACCUMULO_PASSWORD) fills it in.
        set(settings, "accumulo.password", acc.password());
        if (StringUtils.hasText(acc.clientProperties())) {
            settings.setProperty("accumulo.client-properties", acc.clientProperties());
            return settings;
        }
        settings.setProperty("accumulo.instance", required(acc.instance(), "tripatha.accumulo.instance"));
        settings.setProperty("accumulo.zookeepers", required(acc.zookeepers(), "tripatha.accumulo.zookeepers"));
        settings.setProperty("accumulo.user", required(acc.user(), "tripatha.accumulo.user"));
        required(acc.password(), "tripatha.accumulo.password");
        return settings;
    }

    private static Properties hbaseSettings(final RyaProperties props) {
        final RyaProperties.HBase hb = props.hbase();
        final Properties settings = new Properties();
        settings.setProperty("hbase.site", String.join(",", hb.site()));
        for (final Map.Entry<String, String> e : hb.conf().entrySet()) {
            settings.setProperty("hbase.conf." + e.getKey(), e.getValue());
        }
        settings.setProperty("hbase.labels-table", hb.labelsTable());
        settings.setProperty("hbase.counters", hb.counters());
        set(settings, "hbase.counters.jar", hb.countersJar());
        set(settings, "hbase.user", hb.user());
        return settings;
    }

    private static Properties rocksdbSettings(final RyaProperties props) {
        final Properties settings = new Properties();
        settings.setProperty("rocksdb.path", required(props.rocksdb().path(), "tripatha.rocksdb.path"));
        return settings;
    }

    private static void set(final Properties settings, final String key, final String value) {
        if (StringUtils.hasText(value)) {
            settings.setProperty(key, value);
        }
    }

    @Bean
    StoreRdfConfiguration ryaConfiguration(final RyaProperties props) {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(props.tablePrefix());
        conf.setInfer(props.inference());
        conf.setUseStats(props.stats());
        conf.setMaintainStats(props.stats());
        conf.setJoinBatchSize(props.joinBatchSize());
        conf.setJoinEntityPrefetch(props.entityPrefetch());
        if (props.joinMergeRatio() != null) {
            conf.setJoinMergeRatio(props.joinMergeRatio());
        }
        if (props.joinMergeSlices() != null) {
            conf.setJoinMergeSlices(props.joinMergeSlices());
        }
        conf.setTextIndex(props.textIndex());
        conf.setTextIndexPredicates(props.textIndexPredicates());
        conf.setGeoIndex(props.geoIndex());
        conf.setEntityIndex(props.entityIndex());
        if (props.entityIndexShards() != null) {
            conf.setEntityIndexShards(props.entityIndexShards());
        }
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
