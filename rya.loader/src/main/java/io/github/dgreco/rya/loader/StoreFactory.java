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
package io.github.dgreco.rya.loader;

import io.github.dgreco.rya.accumulo.AccumuloStore;
import io.github.dgreco.rya.hbase.HBaseStoreProvider;
import io.github.dgreco.rya.store.spi.Store;

/** Opens the store of {@code --backend}: Accumulo (the default) or HBase. */
final class StoreFactory {
    private StoreFactory() {
    }

    static Store open(final LoaderOptions o) {
        return switch (o.backend()) {
            case "accumulo" -> AccumuloStore.owning(ClientFactory.create(o));
            case "hbase" -> {
                final java.util.Properties settings = o.hbaseSettings();
                // Label administration runs as an HBase superuser, which the check would refuse.
                if (o.command == LoaderOptions.Command.LABELS) {
                    settings.setProperty("hbase.check-labels", "false");
                }
                yield new HBaseStoreProvider().open(settings);
            }
            default -> throw new IllegalArgumentException("--backend must be accumulo or hbase, not " + o.backend());
        };
    }
}
