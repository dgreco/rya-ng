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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.security.visibility.VisibilityClient;

import io.github.dgreco.rya.hbase.HBaseLabels;
import io.github.dgreco.rya.hbase.HBaseStore;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.test.TestBackend;

/** The shared {@link MiniHBaseSingleton} cluster; tests run as {@value MiniHBaseSingleton#TEST_USER}. */
public final class HBaseTestBackend implements TestBackend {

    @Override
    public String id() {
        return "hbase";
    }

    /** {@code -Drya.test.hbaseCounters=increment} runs the tests with HBase increments instead of the coprocessor. */
    public static HBaseStore.CounterMode counterMode() {
        return HBaseStore.CounterMode.valueOf(System.getProperty("rya.test.hbaseCounters", "coprocessor").trim()
                .toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public Store openStore() {
        return HBaseStore.owning(MiniHBaseSingleton.newTestConnection(), MiniHBaseSingleton.testUser(),
                HBaseStore.Options.DEFAULT.withCounters(counterMode()));
    }

    @Override
    public void grantLabels(final String... labels) {
        final List<String> list = Arrays.asList(labels);
        HBaseLabels.register(MiniHBaseSingleton.adminConnection(), list);
        HBaseLabels.grant(MiniHBaseSingleton.adminConnection(), MiniHBaseSingleton.TEST_USER, list);
        HBaseLabels.writeCanaries(MiniHBaseSingleton.adminConnection(), HBaseLabels.DEFAULT_TABLE, list);
    }

    @Override
    public List<String> labels() {
        try {
            final List<String> out = new ArrayList<>();
            for (final var auth : VisibilityClient.getAuths(MiniHBaseSingleton.adminConnection(), MiniHBaseSingleton.TEST_USER)
                    .getAuthList()) {
                out.add(auth.toStringUtf8());
            }
            out.remove(HBaseStore.NONE_LABEL);
            return out;
        } catch (final Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Properties storeSettings() {
        final var conf = MiniHBaseSingleton.configuration();
        final Properties settings = new Properties();
        settings.setProperty("hbase.conf.hbase.zookeeper.quorum", conf.get("hbase.zookeeper.quorum"));
        settings.setProperty("hbase.conf.hbase.zookeeper.property.clientPort", conf.get("hbase.zookeeper.property.clientPort"));
        settings.setProperty("hbase.user", MiniHBaseSingleton.TEST_USER);
        settings.setProperty("hbase.counters", counterMode().name().toLowerCase(java.util.Locale.ROOT));
        return settings;
    }

    @Override
    public Map<String, String> endpointProperties() {
        final var conf = MiniHBaseSingleton.configuration();
        return Map.of("rya.backend", "hbase",
                "rya.hbase.conf[hbase.zookeeper.quorum]", conf.get("hbase.zookeeper.quorum"),
                "rya.hbase.conf[hbase.zookeeper.property.clientPort]", conf.get("hbase.zookeeper.property.clientPort"),
                "rya.hbase.user", MiniHBaseSingleton.TEST_USER,
                "rya.hbase.counters", counterMode().name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void compact(final String table) {
        try (Admin admin = MiniHBaseSingleton.adminConnection().getAdmin()) {
            final TableName name = TableName.valueOf(table);
            admin.flush(name);
            admin.majorCompact(name);
            final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
            // The request is asynchronous: wait until it has started and finished.
            Thread.sleep(200);
            while (admin.getCompactionState(name) != org.apache.hadoop.hbase.client.CompactionState.NONE) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("compaction of " + table + " did not finish");
                }
                Thread.sleep(100);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Deletes the tables in the background: each test has its own prefix, so nothing waits for
     * them, and a delete costs about 0.4 s per table.
     */
    @Override
    public void deleteTables(final String prefix) {
        final List<TableName> names;
        try (Admin admin = MiniHBaseSingleton.adminConnection().getAdmin()) {
            names = List.of(admin.listTableNames(Pattern.compile(Pattern.quote(prefix) + ".*")));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        if (names.isEmpty()) {
            return;
        }
        DELETER.submit(() -> {
            try (Admin admin = MiniHBaseSingleton.adminConnection().getAdmin()) {
                for (final TableName name : names) {
                    admin.disableTable(name);
                    admin.deleteTable(name);
                }
            } catch (final Exception e) {
                // The cluster may be shutting down; leftover tables die with it.
            }
        });
    }

    private static final java.util.concurrent.ExecutorService DELETER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "hbase-test-table-delete");
        t.setDaemon(true);
        return t;
    });
}
