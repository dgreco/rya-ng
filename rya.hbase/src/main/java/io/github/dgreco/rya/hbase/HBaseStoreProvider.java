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
package io.github.dgreco.rya.hbase;

import java.io.IOException;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.security.UserGroupInformation;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.spi.StoreProvider;

/**
 * Opens HBase stores. Settings:
 * <ul>
 *   <li>{@code hbase.site}: comma-separated paths of {@code hbase-site.xml} files (and
 *       {@code core-site.xml} for Kerberos);</li>
 *   <li>{@code hbase.conf.<key>}: any HBase client setting, such as
 *       {@code hbase.conf.hbase.zookeeper.quorum}. Given a ZooKeeper quorum and no site file,
 *       the store finds the cluster through ZooKeeper (HBase 3's client otherwise asks
 *       {@code hbase.client.bootstrap.servers});</li>
 *   <li>{@code hbase.labels-table}: the label canary table (default {@value HBaseLabels#DEFAULT_TABLE});</li>
 *   <li>{@code hbase.counters}: {@code coprocessor} (the default) or {@code increment}, how new counter
 *       tables store deltas (see {@link HBaseStore.CounterMode});</li>
 *   <li>{@code hbase.counters.jar}: the hdfs path region servers load the coprocessor from, when it
 *       isn't on their classpath;</li>
 *   <li>{@code hbase.check-labels}: {@code false} skips the check that labels are enforced for the
 *       account, for label administration as a superuser (default {@code true});</li>
 *   <li>{@code hbase.user}: with simple authentication only, the account to connect as (by default
 *       the process user). With Kerberos the account is the logged-in principal.</li>
 * </ul>
 */
public final class HBaseStoreProvider implements StoreProvider {
    public static final String ID = "hbase";
    public static final String SITE = "hbase.site";
    public static final String CONF_PREFIX = "hbase.conf.";
    public static final String LABELS_TABLE = "hbase.labels-table";
    public static final String COUNTERS = "hbase.counters";
    public static final String COUNTERS_JAR = "hbase.counters.jar";
    public static final String USER = "hbase.user";
    public static final String CHECK_LABELS = "hbase.check-labels";

    private static final String REGISTRY = "hbase.client.registry.impl";
    private static final String ZK_REGISTRY = "org.apache.hadoop.hbase.client.ZKConnectionRegistry";

    /**
     * HBase 3's client finds the cluster through its masters ({@code hbase.client.bootstrap.servers}
     * or {@code hbase.masters}, by default {@code localhost:16000}), not ZooKeeper. Given only a
     * ZooKeeper quorum (no site files, no bootstrap servers or registry), it is found through ZooKeeper.
     */
    static boolean findThroughZooKeeper(final Properties settings, final String sites) {
        return sites.isBlank() && settings.getProperty(CONF_PREFIX + "hbase.zookeeper.quorum") != null
                && settings.getProperty(CONF_PREFIX + REGISTRY) == null
                && settings.getProperty(CONF_PREFIX + "hbase.client.bootstrap.servers") == null
                && settings.getProperty(CONF_PREFIX + "hbase.masters") == null;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Store open(final Properties settings) {
        final Configuration conf = HBaseConfiguration.create();
        final String sites = settings.getProperty(SITE, "");
        for (final String site : sites.split(",")) {
            if (!site.isBlank()) {
                conf.addResource(new Path(site.trim()));
            }
        }
        for (final String key : settings.stringPropertyNames()) {
            if (key.startsWith(CONF_PREFIX)) {
                conf.set(key.substring(CONF_PREFIX.length()), settings.getProperty(key));
            }
        }
        if (findThroughZooKeeper(settings, sites)) {
            conf.set(REGISTRY, ZK_REGISTRY);
        }
        try {
            final HBaseStore.Options options = new HBaseStore.Options(settings.getProperty(LABELS_TABLE, HBaseLabels.DEFAULT_TABLE),
                    HBaseStore.CounterMode.valueOf(settings.getProperty(COUNTERS, "coprocessor").trim().toUpperCase(java.util.Locale.ROOT)),
                    settings.getProperty(COUNTERS_JAR),
                    !"false".equalsIgnoreCase(settings.getProperty(CHECK_LABELS, "true").trim()));
            final String name = settings.getProperty(USER);
            final User user = name == null || name.isBlank() ? null
                    : User.create(UserGroupInformation.createRemoteUser(name.trim()));
            // HBase 3's createConnection(conf, user) needs a user; without one, the one-argument form logs
            // the client in (the process user, or Kerberos from kinit or hbase.client.keytab.*).
            final Connection connection = user == null ? ConnectionFactory.createConnection(conf)
                    : ConnectionFactory.createConnection(conf, user);
            return HBaseStore.owning(connection, user, options);
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
    }
}
