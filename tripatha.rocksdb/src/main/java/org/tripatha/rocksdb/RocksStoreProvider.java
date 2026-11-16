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
package org.tripatha.rocksdb;

import java.nio.file.Path;
import java.util.Properties;

import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.StoreProvider;

/**
 * Opens {@link RocksStore}s. The only setting is {@value #PATH}: the directory of the database,
 * created if it doesn't exist.
 */
public final class RocksStoreProvider implements StoreProvider {
    /** The directory of the database. */
    public static final String PATH = "rocksdb.path";

    @Override
    public String id() {
        return RocksStore.ID;
    }

    @Override
    public Store open(final Properties settings) {
        final String path = settings.getProperty(PATH, "").trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("The RocksDB backend needs the directory of its database (" + PATH + ")");
        }
        return RocksStore.open(Path.of(path));
    }
}
