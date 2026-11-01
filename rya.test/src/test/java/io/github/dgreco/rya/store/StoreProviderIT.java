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
package io.github.dgreco.rya.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.spi.Stores;
import io.github.dgreco.rya.store.spi.TableKind;
import io.github.dgreco.rya.store.spi.WriterOptions;
import io.github.dgreco.rya.test.StoreITBase;
import io.github.dgreco.rya.test.TestBackends;

/** A backend is found by its id on the classpath and opened from its settings, as the endpoint and loader do. */
class StoreProviderIT extends StoreITBase {

    @Test
    void theBackendOpensFromItsSettings() {
        final String table = getTablePrefix() + "provider";
        try (Store store = Stores.open(TestBackends.id(), backend().storeSettings())) {
            assertEquals(TestBackends.id(), store.backend());
            store.tables().create(table, TableKind.METADATA);
            try (CellWriter w = store.newWriter(WriterOptions.DEFAULT)) {
                w.table(table).write(new RowMutation(b("r")).put(b("f"), b("q"), new byte[0], b("v")));
            }
            try (CellScanner cells = store.scan(table, KeyRange.row(b("r")), ScanSpec.of(Auths.NONE))) {
                assertEquals("v", new String(cells.next().value(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void anUnknownBackendIsReported() {
        assertThrows(IllegalArgumentException.class, () -> Stores.open("nosuch", new Properties()));
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
