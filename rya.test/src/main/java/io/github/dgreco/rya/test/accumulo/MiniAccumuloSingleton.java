/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.dgreco.rya.test.accumulo;

import java.io.File;
import java.nio.file.Files;
import java.util.Properties;

import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.apache.accumulo.minicluster.MiniAccumuloConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@link MiniAccumuloCluster} per JVM, started on first use and stopped by a shutdown hook.
 * Starting a cluster takes several seconds, so tests share it and isolate themselves with
 * unique table prefixes (see {@link AccumuloITBase}).
 */
public final class MiniAccumuloSingleton {
    private static final Logger log = LoggerFactory.getLogger(MiniAccumuloSingleton.class);

    public static final String ROOT_USER = "root";
    public static final String ROOT_PASSWORD = "password";

    private static MiniAccumuloCluster cluster;
    private static File clusterDir;

    private MiniAccumuloSingleton() {
    }

    /**
     * @return the running cluster, starting it if needed.
     */
    public static synchronized MiniAccumuloCluster getCluster() {
        if (cluster == null) {
            try {
                final File dir = Files.createTempDirectory("rya-mac").toFile();
                clusterDir = dir;
                final MiniAccumuloConfig config = new MiniAccumuloConfig(dir, ROOT_PASSWORD);
                config.setNumTservers(1);
                final MiniAccumuloCluster started = new MiniAccumuloCluster(config);
                log.info("Starting MiniAccumuloCluster in {}", dir);
                started.start();
                cluster = started;
                Runtime.getRuntime().addShutdownHook(new Thread(MiniAccumuloSingleton::stop, "mac-shutdown"));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while starting MiniAccumuloCluster", e);
            } catch (final Exception e) {
                throw new IllegalStateException("Could not start MiniAccumuloCluster", e);
            }
        }
        return cluster;
    }

    /**
     * @return client properties (instance, ZooKeepers, root credentials) for the shared cluster.
     */
    public static Properties getClientProperties() {
        return getCluster().getClientProperties();
    }

    private static synchronized void stop() {
        final MiniAccumuloCluster running = cluster;
        cluster = null;
        if (running != null) {
            // stop() can block for a long time; the test runner then kills this JVM before the
            // cleanup below runs, leaving cluster processes behind. Give it a bounded wait.
            final Thread stopper = new Thread(() -> {
                try {
                    running.stop();
                } catch (final Exception e) {
                    // Logging may already be shut down during JVM exit.
                    e.printStackTrace(System.err);
                }
            }, "mac-stop");
            stopper.setDaemon(true);
            stopper.start();
            try {
                stopper.join(10_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // MiniAccumuloCluster.stop() does not always stop its ZooKeeper process; make sure no
        // cluster process outlives the test JVM.
        ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
        deleteRecursively(clusterDir);
        clusterDir = null;
    }

    /** Removes the cluster's own temporary directory (best effort). */
    private static void deleteRecursively(final File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(dir.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (final java.io.IOException e) {
            // The OS cleans its temp directory eventually.
        }
    }
}
