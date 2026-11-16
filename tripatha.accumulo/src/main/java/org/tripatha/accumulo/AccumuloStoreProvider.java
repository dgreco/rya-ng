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
package org.tripatha.accumulo;

import java.nio.file.Path;
import java.util.Properties;

import org.apache.accumulo.core.client.Accumulo;

import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.StoreProvider;

/**
 * Opens Accumulo stores. Settings:
 * <ul>
 *   <li>{@code accumulo.client-properties}: path of an {@code accumulo-client.properties} file;</li>
 *   <li>{@code accumulo.password}: the password, when the file has none (see {@link AccumuloClients}).</li>
 * </ul>
 */
public final class AccumuloStoreProvider implements StoreProvider {
    public static final String ID = "accumulo";
    /** An {@code accumulo-client.properties} file. When set, the instance, ZooKeepers and user below are ignored. */
    public static final String CLIENT_PROPERTIES = "accumulo.client-properties";
    public static final String INSTANCE = "accumulo.instance";
    public static final String ZOOKEEPERS = "accumulo.zookeepers";
    public static final String USER = "accumulo.user";
    /** The password; with a properties file, used only when the file leaves it out. */
    public static final String PASSWORD = "accumulo.password";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Store open(final Properties settings) {
        final String file = settings.getProperty(CLIENT_PROPERTIES);
        if (file != null && !file.isBlank()) {
            return AccumuloStore.owning(AccumuloClients.fromFile(Path.of(file.trim()), settings.getProperty(PASSWORD)));
        }
        return AccumuloStore.owning(Accumulo.newClient()
                .to(required(settings, INSTANCE), required(settings, ZOOKEEPERS))
                .as(required(settings, USER), required(settings, PASSWORD))
                .build());
    }

    private static String required(final Properties settings, final String key) {
        final String value = settings.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + CLIENT_PROPERTIES + ", or " + INSTANCE + ", " + ZOOKEEPERS + ", " + USER
                    + " and " + PASSWORD + ": " + key + " is missing");
        }
        return value;
    }
}
