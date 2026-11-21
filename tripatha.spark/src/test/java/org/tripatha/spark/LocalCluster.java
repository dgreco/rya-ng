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
package org.tripatha.spark;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.accumulo.minicluster.MiniAccumuloCluster;

import org.tripatha.test.accumulo.MiniAccumuloSingleton;

/**
 * A throwaway Accumulo for trying the job with spark-submit on one machine: starts the test
 * cluster, writes {@code accumulo-client.properties} into the directory given, and runs until
 * stopped. Its volume is the local file system, so any local {@code --work-dir} can be imported.
 * See tripatha.spark/README.md.
 */
public final class LocalCluster {
    private LocalCluster() {
    }

    public static void main(final String[] args) throws Exception {
        final Path dir = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath();
        final MiniAccumuloCluster cluster = MiniAccumuloSingleton.getCluster();
        Files.createDirectories(dir);
        final Path props = dir.resolve("accumulo-client.properties");
        Files.writeString(props, "instance.name=" + cluster.getInstanceName() + "\ninstance.zookeepers=" + cluster.getZooKeepers()
                + "\nauth.type=password\nauth.principal=" + MiniAccumuloSingleton.ROOT_USER + "\nauth.token="
                + MiniAccumuloSingleton.ROOT_PASSWORD + "\n", StandardCharsets.UTF_8);
        System.out.println("Accumulo " + cluster.getInstanceName() + " at " + cluster.getZooKeepers() + "; client properties in " + props);
        System.out.println("Ready. Stop with Ctrl-C.");
        Thread.currentThread().join();
    }
}
