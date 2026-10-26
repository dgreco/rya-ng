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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.security.Authorizations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for integration tests that need Accumulo. Each test gets:
 * <ul>
 *   <li>a root {@link AccumuloClient} on the shared {@link MiniAccumuloSingleton} cluster,</li>
 *   <li>a unique table prefix, whose tables are deleted after the test.</li>
 * </ul>
 */
public abstract class AccumuloITBase {
    private static final AtomicInteger COUNTER = new AtomicInteger(1);

    private AccumuloClient client;
    private String tablePrefix;

    @BeforeEach
    public void setUpAccumulo() throws Exception {
        tablePrefix = "it" + COUNTER.getAndIncrement() + "_";
        client = Accumulo.newClient().from(MiniAccumuloSingleton.getClientProperties()).build();
    }

    @AfterEach
    public void tearDownAccumulo() throws Exception {
        if (client != null) {
            try {
                for (final String table : client.tableOperations().list()) {
                    if (table.startsWith(tablePrefix)) {
                        client.tableOperations().delete(table);
                    }
                }
            } finally {
                client.close();
                client = null;
            }
        }
    }

    /** @return a root client on the shared cluster; closed after each test. */
    public AccumuloClient getClient() {
        return client;
    }

    /** @return client properties for building additional clients (instance, ZooKeepers, root credentials). */
    public Properties getClientProperties() {
        return MiniAccumuloSingleton.getClientProperties();
    }

    /** @return a table prefix unique to this test, e.g. {@code it7_}. */
    public String getTablePrefix() {
        return tablePrefix;
    }

    public String getInstanceName() {
        return MiniAccumuloSingleton.getCluster().getInstanceName();
    }

    public String getZookeepers() {
        return MiniAccumuloSingleton.getCluster().getZooKeepers();
    }

    public String getUsername() {
        return MiniAccumuloSingleton.ROOT_USER;
    }

    public String getPassword() {
        return MiniAccumuloSingleton.ROOT_PASSWORD;
    }

    /**
     * Adds scan authorizations to the root user. A real cluster rejects scans with auths the
     * user does not hold. Auths are only ever added, so tests sharing the cluster don't interfere.
     */
    public void grantAuthorizations(final String... auths) throws Exception {
        synchronized (AccumuloITBase.class) {
            final Authorizations current = client.securityOperations().getUserAuthorizations(getUsername());
            final List<byte[]> merged = new ArrayList<>(current.getAuthorizations());
            final List<String> have = new ArrayList<>();
            for (final byte[] a : merged) {
                have.add(new String(a, java.nio.charset.StandardCharsets.UTF_8));
            }
            for (final String a : auths) {
                if (!have.contains(a)) {
                    merged.add(a.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            client.securityOperations().changeUserAuthorizations(getUsername(), new Authorizations(merged));
        }
    }

    /** @return the root user's current scan authorizations. */
    public List<String> getUserAuthorizations() throws Exception {
        final List<String> out = new ArrayList<>();
        for (final byte[] a : client.securityOperations().getUserAuthorizations(getUsername()).getAuthorizations()) {
            out.add(new String(a, java.nio.charset.StandardCharsets.UTF_8));
        }
        return Collections.unmodifiableList(out);
    }
}
