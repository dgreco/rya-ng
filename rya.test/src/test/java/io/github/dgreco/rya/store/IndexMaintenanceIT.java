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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.TablePermission;
import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.store.index.IndexType;
import io.github.dgreco.rya.store.index.IndexVerifier;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.accumulo.AccumuloStore;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.test.StoreITBase;
import io.github.dgreco.rya.test.accumulo.AccumuloTestBackend;
import io.github.dgreco.rya.test.accumulo.MiniAccumuloSingleton;

/** The text and geo index tables stay exactly in step with the statement tables. */
class IndexMaintenanceIT extends StoreITBase {
    private static final RyaIRI LABEL = new RyaIRI(RDFS.LABEL.stringValue());
    private static final RyaIRI COMMENT = new RyaIRI(RDFS.COMMENT.stringValue());
    private static final RyaIRI AS_WKT = new RyaIRI(GEO.AS_WKT.stringValue());
    private static final RyaIRI G1 = new RyaIRI("urn:g1");
    private static final RyaIRI G2 = new RyaIRI("urn:g2");

    private final List<RyaStoreDAO> daos = new ArrayList<>();

    @BeforeEach
    void auths() throws Exception {
        grantAuthorizations("A", "B");
    }

    @AfterEach
    void close() {
        daos.forEach(RyaStoreDAO::destroy);
    }

    private StoreRdfConfiguration conf(final boolean text, final boolean geo) {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        conf.setTextIndex(text);
        conf.setGeoIndex(geo);
        conf.setAuths("A", "B");
        return conf;
    }

    private RyaStoreDAO open(final StoreRdfConfiguration conf) {
        final RyaStoreDAO dao = new RyaStoreDAO(getStore(), conf);
        daos.add(dao);
        dao.init();
        return dao;
    }

    private static RyaStatement st(final String s, final RyaIRI p, final RyaType o, final RyaIRI graph, final String qualifier,
            final String label) {
        return new RyaStatement(new RyaIRI(s), p, o, graph, qualifier,
                label == null ? null : label.getBytes(StandardCharsets.UTF_8), null);
    }

    private void assertInStep(final StoreRdfConfiguration conf) throws Exception {
        for (final IndexType type : IndexType.values()) {
            if (type.isEnabled(conf)) {
                final IndexVerifier.Report report = IndexVerifier.verify(getStore(), conf, type);
                assertTrue(report.isClean(), type + ": " + report);
            }
        }
    }

    private int entries(final IndexType type) throws Exception {
        int n = 0;
        try (CellScanner scanner = getStore().scan(type.table(conf(true, true).getTableLayoutStrategy()), KeyRange.all(),
                ScanSpec.of(Auths.of("A", "B")))) {
            while (scanner.hasNext()) {
                scanner.next();
                n++;
            }
        }
        return n;
    }

    private List<RyaStatement> sample() {
        return List.of(
                st("urn:a", LABEL, new RyaType("Hello brave new World"), null, null, null),
                st("urn:a", LABEL, new RyaType(RDF.LANGSTRING, "Ciao mondo", "it"), G1, null, null),
                st("urn:b", COMMENT, new RyaType("hello again"), G2, null, "A"),
                st("urn:b", COMMENT, new RyaType("hello again"), G2, null, "B"),
                st("urn:c", LABEL, new RyaType("twin"), null, "q1", null),
                st("urn:c", LABEL, new RyaType("twin"), null, "q2", null),
                st("urn:p1", AS_WKT, new RyaType(GEO.WKT_LITERAL, "POINT(12.49 41.89)"), null, null, "A"),
                st("urn:p2", AS_WKT, new RyaType(GEO.WKT_LITERAL, "POLYGON((12 41, 13 41, 13 42, 12 42, 12 41))"), G1, null, null),
                st("urn:p3", AS_WKT, new RyaType(GEO.WKT_LITERAL, "not wkt"), null, null, null));
    }

    @Test
    void entriesFollowAddsAndDeletes() throws Exception {
        final StoreRdfConfiguration conf = conf(true, true);
        final RyaStoreDAO dao = open(conf);
        assertTrue(dao.getIndexCatalog().isReady(IndexType.TEXT));
        assertTrue(dao.getIndexCatalog().isReady(IndexType.GEO));
        dao.add(sample().iterator());
        dao.flush();
        assertInStep(conf);
        assertTrue(entries(IndexType.TEXT) > 0 && entries(IndexType.GEO) > 1);

        // A user holding only A deletes "hello again": B's copy and its index entries stay.
        final StoreRdfConfiguration onlyA = new StoreRdfConfiguration(conf);
        onlyA.setAuths("A");
        dao.delete(new RyaStatement(new RyaIRI("urn:b"), COMMENT, null), onlyA);
        dao.flush();
        assertInStep(conf);

        // One of two qualifier variants.
        dao.delete(new RyaStatement(new RyaIRI("urn:c"), LABEL, null), conf);
        dao.flush();
        assertInStep(conf);

        for (final RyaStatement s : sample()) {
            dao.delete(new RyaStatement(s.getSubject(), s.getPredicate(), null), conf);
        }
        dao.flush();
        assertInStep(conf);
        assertEquals(0, entries(IndexType.TEXT));
        assertEquals(0, entries(IndexType.GEO));
    }

    @Test
    void reinsertingThenDeletingLeavesNothing() throws Exception {
        final StoreRdfConfiguration conf = conf(true, false);
        final RyaStoreDAO dao = open(conf);
        final RyaStatement first = st("urn:x", LABEL, new RyaType("again and again"), null, null, null);
        dao.add(first);
        dao.flush();
        Thread.sleep(2);
        dao.add(st("urn:x", LABEL, new RyaType("again and again"), null, null, null));
        dao.flush();
        assertInStep(conf);
        dao.delete(new RyaStatement(new RyaIRI("urn:x"), null, null), conf);
        dao.flush();
        assertInStep(conf);
        assertEquals(0, entries(IndexType.TEXT));
    }

    @Test
    void droppingGraphsClearsTheirEntries() throws Exception {
        final StoreRdfConfiguration conf = conf(true, true);
        final RyaStoreDAO dao = open(conf);
        dao.add(sample().iterator());
        dao.flush();
        dao.dropGraph(conf, G1);
        assertInStep(conf);
        dao.dropGraph(conf, (RyaIRI) null);
        assertInStep(conf);
        assertTrue(entries(IndexType.TEXT) > 0, "graph g2 is still there");
        dao.dropGraph(conf);
        assertInStep(conf);
        assertEquals(0, entries(IndexType.TEXT));
        assertEquals(0, entries(IndexType.GEO));
    }

    @Test
    void anIndexEnabledOnExistingDataWaitsForARebuild() throws Exception {
        final RyaStoreDAO plain = open(conf(false, false));
        plain.add(sample().iterator());
        plain.destroy();

        final StoreRdfConfiguration withText = conf(true, false);
        final RyaStoreDAO late = open(withText);
        assertFalse(late.getIndexCatalog().isReady(IndexType.TEXT), "existing statements are not indexed yet");
        assertFalse(IndexVerifier.verify(getStore(), withText, IndexType.TEXT).isClean());
        late.destroy();

        // Still not ready on a plain restart; the rebuild flag builds it.
        final RyaStoreDAO restarted = open(conf(true, false));
        assertFalse(restarted.getIndexCatalog().isReady(IndexType.TEXT));
        restarted.destroy();
        final StoreRdfConfiguration rebuild = conf(true, false);
        rebuild.setRebuildIndexes(true);
        final RyaStoreDAO rebuilt = open(rebuild);
        assertTrue(rebuilt.getIndexCatalog().isReady(IndexType.TEXT));
        assertInStep(withText);
        rebuilt.destroy();
        assertTrue(open(conf(true, false)).getIndexCatalog().isReady(IndexType.TEXT), "READY is remembered");
    }

    @Test
    void anIndexTableWithItsIndexOffStopsTheStore() {
        open(conf(true, false)).destroy();
        final RyaDAOException e = assertThrows(RyaDAOException.class, () -> open(conf(false, false)));
        assertTrue(e.getMessage().contains(getTablePrefix() + "text"), e.getMessage());
    }

    @Test
    void changingTheIndexedPredicatesNeedsARebuild() throws Exception {
        final RyaStoreDAO all = open(conf(true, false));
        all.add(sample().iterator());
        all.destroy();

        final StoreRdfConfiguration labelsOnly = conf(true, false);
        labelsOnly.setTextIndexPredicates(List.of(RDFS.LABEL.stringValue()));
        final RyaDAOException e = assertThrows(RyaDAOException.class, () -> open(labelsOnly));
        assertTrue(e.getMessage().contains("different settings"), e.getMessage());

        labelsOnly.setRebuildIndexes(true);
        final RyaStoreDAO rebuilt = open(labelsOnly);
        assertTrue(rebuilt.getIndexCatalog().isReady(IndexType.TEXT));
        assertInStep(labelsOnly);
    }

    @Test
    void purgeKeepsTheIndexRecords() throws Exception {
        final StoreRdfConfiguration conf = conf(true, true);
        final RyaStoreDAO dao = open(conf);
        dao.add(sample().iterator());
        dao.purge(conf);
        assertEquals(0, entries(IndexType.TEXT));
        dao.destroy();
        final RyaStoreDAO reopened = open(conf(true, true));
        assertTrue(reopened.getIndexCatalog().isReady(IndexType.TEXT));
        assertTrue(reopened.getIndexCatalog().isReady(IndexType.GEO));
    }

    /** Accumulo only: needs a user with read permission alone. */
    @Test
    void aReadOnlyUserCanOpenAnIndexedStore() throws Exception {
        assumeBackend("accumulo");
        open(conf(true, true)).add(sample().iterator());
        final String user = "reader" + getTablePrefix().replace("_", "");
        final PasswordToken password = new PasswordToken("reader-password");
        try (AccumuloClient root = AccumuloTestBackend.newClient()) {
            root.securityOperations().createLocalUser(user, password);
            try {
                root.securityOperations().changeUserAuthorizations(user, new Authorizations("A", "B"));
                for (final String table : root.tableOperations().list()) {
                    if (table.startsWith(getTablePrefix())) {
                        root.securityOperations().grantTablePermission(user, table, TablePermission.READ);
                    }
                }
                readOnly(user, password);
            } finally {
                root.securityOperations().dropLocalUser(user);
            }
        }
    }

    private void readOnly(final String user, final PasswordToken password) throws Exception {
        {
            try (AccumuloClient reader = Accumulo.newClient().from(MiniAccumuloSingleton.getClientProperties())
                    .as(user, password).build()) {
                final RyaStoreDAO dao = new RyaStoreDAO(AccumuloStore.wrap(reader), conf(true, true));
                dao.init();
                try {
                    assertTrue(dao.getIndexCatalog().isReady(IndexType.TEXT));
                    assertTrue(dao.getIndexCatalog().isReady(IndexType.GEO));
                } finally {
                    dao.destroy();
                }
            }
        }
    }
}
