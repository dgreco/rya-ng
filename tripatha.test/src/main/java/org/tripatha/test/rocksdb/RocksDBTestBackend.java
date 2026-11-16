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
package org.tripatha.test.rocksdb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.tripatha.rocksdb.RocksStore;
import org.tripatha.rocksdb.RocksStoreProvider;
import org.tripatha.store.spi.Store;
import org.tripatha.test.TestBackend;

/**
 * The RocksDB backend for tests: one database per JVM, in a new temporary directory (or the one
 * named by {@code -Dtripatha.test.rocksdbPath}), kept open until the JVM exits. Tests isolate
 * themselves with unique table prefixes, as on the other backends.
 *
 * <p>The backend has no accounts, so every label is held; the labels a test grants are only
 * remembered, for {@link #labels()}.
 */
public final class RocksDBTestBackend implements TestBackend {
    private final Path directory;
    private final RocksStore keeper;
    private final Set<String> labels = new LinkedHashSet<>();

    public RocksDBTestBackend() {
        final String configured = System.getProperty("tripatha.test.rocksdbPath", "").trim();
        try {
            this.directory = configured.isEmpty() ? Files.createTempDirectory("tripatha-rocksdb") : Path.of(configured);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        this.keeper = RocksStore.open(directory);
    }

    @Override
    public String id() {
        return RocksStore.ID;
    }

    @Override
    public Store openStore() {
        return RocksStore.open(directory);
    }

    @Override
    public synchronized void grantLabels(final String... granted) {
        labels.addAll(Arrays.asList(granted));
    }

    @Override
    public synchronized List<String> labels() {
        return new ArrayList<>(labels);
    }

    @Override
    public void deleteTables(final String prefix) {
        for (final String table : keeper.tables().list()) {
            if (table.startsWith(prefix)) {
                keeper.tables().delete(table);
            }
        }
    }

    @Override
    public void compact(final String table) {
        keeper.compact(table);
    }

    @Override
    public Properties storeSettings() {
        final Properties settings = new Properties();
        settings.setProperty(RocksStoreProvider.PATH, directory.toString());
        return settings;
    }

    @Override
    public Map<String, String> endpointProperties() {
        return Map.of("tripatha.backend", RocksStore.ID, "tripatha.rocksdb.path", directory.toString());
    }
}
