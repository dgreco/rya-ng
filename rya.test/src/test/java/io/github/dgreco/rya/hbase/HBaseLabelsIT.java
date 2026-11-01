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
package io.github.dgreco.rya.hbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.test.StoreITBase;
import io.github.dgreco.rya.test.hbase.MiniHBaseSingleton;

/** HBase only: the checks that keep HBase's labels as strict as Accumulo's. */
class HBaseLabelsIT extends StoreITBase {

    @BeforeEach
    void onlyOnHBase() {
        assumeBackend("hbase");
    }

    /** A superuser sees every cell whatever it asks for, so a store refuses to open as one. */
    @Test
    void aSuperuserStoreRefusesToOpen() {
        final RyaDAOException e = assertThrows(RyaDAOException.class,
                () -> HBaseStore.wrap(MiniHBaseSingleton.adminConnection(), null));
        assertTrue(e.getMessage().contains("doesn't enforce labels"), e.getMessage());
    }

    /**
     * Without {@code hbase.user} the provider connects as the process user (here an HBase superuser),
     * so the store opens only with the label check off.
     */
    @Test
    void withoutAUserTheProviderConnectsAsTheProcessUser() {
        final Properties settings = backend().storeSettings();
        settings.remove(HBaseStoreProvider.USER);
        final RyaDAOException e = assertThrows(RyaDAOException.class, () -> new HBaseStoreProvider().open(settings));
        assertTrue(e.getMessage().contains("doesn't enforce labels"), e.getMessage());

        settings.setProperty(HBaseStoreProvider.CHECK_LABELS, "false");
        try (Store store = new HBaseStoreProvider().open(settings)) {
            assertEquals(System.getProperty("user.name"), store.access().whoami());
        }
    }

    /** HBase drops labels a reader doesn't hold; the canaries tell which configured labels are missing. */
    @Test
    void missingLabelsAreFoundThroughTheirCanaries() {
        grantAuthorizations("A");
        HBaseLabels.register(MiniHBaseSingleton.adminConnection(), List.of("notGranted"));
        HBaseLabels.writeCanaries(MiniHBaseSingleton.adminConnection(), HBaseLabels.DEFAULT_TABLE, List.of("notGranted"));
        final HBaseStore store = (HBaseStore) getStore();
        assertEquals(List.of("notGranted", "neverRegistered"),
                HBaseLabels.missingCanaries(store, HBaseLabels.DEFAULT_TABLE, List.of("A", "notGranted", "neverRegistered")));
    }

    /** Rows longer than HBase allows are refused with a clear error, before anything is sent. */
    @Test
    void overlongRowsAreRefused() {
        final var conf = new io.github.dgreco.rya.store.StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        final var dao = new io.github.dgreco.rya.store.RyaStoreDAO(getStore(), conf);
        dao.init();
        try {
            final RyaDAOException e = assertThrows(RyaDAOException.class, () -> dao.add(new io.github.dgreco.rya.api.domain.RyaStatement(
                    new io.github.dgreco.rya.api.domain.RyaIRI("urn:s"), new io.github.dgreco.rya.api.domain.RyaIRI("urn:p"),
                    new io.github.dgreco.rya.api.domain.RyaType("x".repeat(40_000)))));
            assertTrue(String.valueOf(e.getMessage()).contains("limit") || String.valueOf(e.getCause()).contains("limit"), e.toString());
            // A lookup of such a literal reads nothing, as on Accumulo, instead of failing.
            try (var found = dao.getQueryEngine().query(new io.github.dgreco.rya.api.domain.RyaStatement(
                    new io.github.dgreco.rya.api.domain.RyaIRI("urn:s"), new io.github.dgreco.rya.api.domain.RyaIRI("urn:p"),
                    new io.github.dgreco.rya.api.domain.RyaType("x".repeat(40_000))), conf)) {
                assertTrue(!found.hasNext());
            }
        } finally {
            dao.destroy();
        }
    }
}
