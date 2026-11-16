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
package org.tripatha.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.Stores;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;
import org.tripatha.test.TestBackends;

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
