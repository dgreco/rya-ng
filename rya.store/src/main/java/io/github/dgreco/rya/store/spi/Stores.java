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
package io.github.dgreco.rya.store.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

/** Finds backends by id among the {@link StoreProvider}s on the classpath. */
public final class Stores {
    private Stores() {
    }

    /** The provider of a backend. */
    public static StoreProvider provider(final String backend) {
        final List<String> found = new ArrayList<>();
        for (final StoreProvider p : ServiceLoader.load(StoreProvider.class)) {
            if (p.id().equals(backend)) {
                return p;
            }
            found.add(p.id());
        }
        throw new IllegalArgumentException("No storage backend '" + backend + "' on the classpath. Available: " + found);
    }

    /** Opens a store of a backend. */
    public static Store open(final String backend, final Properties settings) {
        return provider(backend).open(settings);
    }
}
