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
package io.github.dgreco.rya.test.hbase;

import java.io.File;
import java.nio.file.Files;
import java.security.PrivilegedExceptionAction;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.StartTestingClusterOption;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.visibility.VisibilityConstants;
import org.apache.hadoop.hbase.security.visibility.VisibilityController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dgreco.rya.hbase.HBaseLabels;
import io.github.dgreco.rya.hbase.HBaseStore;

/**
 * One in-process HBase cluster per JVM, started on first use and stopped by a shutdown hook. It
 * runs as production should: {@code VisibilityController} on and {@code hbase.security.authorization}
 * true. The test process is an HBase superuser (it sees every cell), so tests run as
 * {@value #TEST_USER}, a normal account (BACKENDS-STUDY.md, phase 0 results):
 * <ul>
 *   <li>ZooKeeper and HBase without HDFS: the root and the WAL on the local file system (HDFS's
 *       in-process server clashes with Accumulo's Jersey 3 on the test classpath);</li>
 *   <li>{@code hbase.superuser} is the process user, because the mini cluster's region server runs
 *       as another user;</li>
 *   <li>everything binds to, and is advertised as, the loopback address.</li>
 * </ul>
 */
public final class MiniHBaseSingleton {
    private static final Logger log = LoggerFactory.getLogger(MiniHBaseSingleton.class);

    /** The normal account tests run as. */
    public static final String TEST_USER = "ryatest";
    /** A label no test reads with: its canary lets every store check that labels are enforced. */
    public static final String CANARY_LABEL = "rya.test.canary";

    private static HBaseTestingUtil util;
    private static Connection admin;
    private static User testUser;
    private static File rootDir;

    private MiniHBaseSingleton() {
    }

    /** The running cluster, started if needed. */
    public static synchronized HBaseTestingUtil cluster() {
        if (util == null) {
            try {
                start();
            } catch (final Exception e) {
                throw new IllegalStateException("Could not start the HBase mini cluster", e);
            }
        }
        return util;
    }

    private static void start() throws Exception {
        final long t0 = System.nanoTime();
        final HBaseTestingUtil started = new HBaseTestingUtil();
        final Configuration conf = started.getConfiguration();
        rootDir = Files.createTempDirectory("rya-hbase").toFile();
        conf.set("hbase.rootdir", rootDir.toURI() + "root");
        conf.set("hbase.wal.dir", rootDir.toURI() + "wal");
        conf.set("hbase.wal.provider", "filesystem");
        conf.setBoolean("hbase.unsafe.stream.capability.enforce", false);
        // Loopback only: a host name that resolves to another of the machine's addresses (two
        // interfaces on one network) would make the cluster unreachable halfway through a run.
        conf.set("hbase.master.ipc.address", "127.0.0.1");
        conf.set("hbase.regionserver.ipc.address", "127.0.0.1");
        conf.set("hbase.master.hostname", "localhost");
        conf.set("hbase.unsafe.regionserver.hostname", "localhost");
        conf.setInt("hbase.master.info.port", -1);
        conf.setInt("hbase.regionserver.info.port", -1);
        conf.setInt("hfile.format.version", 3);
        conf.setBoolean("hbase.security.authorization", true);
        conf.set("hbase.superuser", System.getProperty("user.name"));
        conf.set(CoprocessorHost.MASTER_COPROCESSOR_CONF_KEY, VisibilityController.class.getName());
        conf.set(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY, VisibilityController.class.getName());
        log.info("Starting the HBase mini cluster in {}", rootDir);
        started.startMiniZKCluster();
        started.startMiniHBaseCluster(StartTestingClusterOption.builder().numRegionServers(1).createRootDir(true)
                .createWALDir(true).build());
        started.waitTableAvailable(VisibilityConstants.LABELS_TABLE_NAME.getName(), 60_000);
        util = started;
        admin = started.getConnection();
        testUser = User.createUserForTesting(conf, TEST_USER, new String[] {TEST_USER});
        // Every reader holds the reserved label; the canary lets each store check that labels are enforced.
        HBaseLabels.register(admin, List.of(HBaseStore.NONE_LABEL, CANARY_LABEL));
        HBaseLabels.grant(admin, TEST_USER, List.of(HBaseStore.NONE_LABEL));
        HBaseLabels.writeCanaries(admin, HBaseLabels.DEFAULT_TABLE, List.of(CANARY_LABEL));
        Runtime.getRuntime().addShutdownHook(new Thread(MiniHBaseSingleton::stop, "hbase-mini-shutdown"));
        log.info("HBase mini cluster up in {} ms", (System.nanoTime() - t0) / 1_000_000);
    }

    /** A superuser connection, for setting up labels and tables. Owned by this class. */
    public static Connection adminConnection() {
        cluster();
        return admin;
    }

    /** The cluster's client configuration. */
    public static Configuration configuration() {
        return cluster().getConfiguration();
    }

    /** The normal account tests run as. */
    public static User testUser() {
        cluster();
        return testUser;
    }

    /** A new connection as {@value #TEST_USER}; the caller closes it. */
    public static Connection newTestConnection() {
        final Configuration conf = configuration();
        try {
            return testUser().runAs((PrivilegedExceptionAction<Connection>) () -> ConnectionFactory.createConnection(conf));
        } catch (final Exception e) {
            throw new IllegalStateException("Could not connect to the HBase mini cluster as " + TEST_USER, e);
        }
    }

    private static synchronized void stop() {
        final HBaseTestingUtil running = util;
        util = null;
        if (running == null) {
            return;
        }
        final Thread stopper = new Thread(() -> {
            try {
                running.shutdownMiniHBaseCluster();
                running.shutdownMiniZKCluster();
            } catch (final Exception e) {
                e.printStackTrace(System.err);
            }
        }, "hbase-mini-stop");
        stopper.setDaemon(true);
        stopper.start();
        try {
            stopper.join(15_000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteRecursively(rootDir);
    }

    private static void deleteRecursively(final File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(dir.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (final java.io.IOException e) {
            // best effort
        }
    }
}
