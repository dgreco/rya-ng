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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tripatha.api.RdfCloudTripleStoreConstants;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.persist.RdfEvalStatsDAO;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;

/**
 * The store side of value ranges (SCALE-STUDY.md O14) on the selected backend: what the datatype
 * markers record, who sees them, what removes them, the storage version that comes with them,
 * and what a value range reads.
 */
public class DatatypeMarkersIT extends StoreITBase {
    private static final String NS = "urn:test#";
    private static final RyaIRI S = new RyaIRI(NS + "s");
    private static final RyaIRI AGE = new RyaIRI(NS + "age");
    private static final RyaIRI NAME = new RyaIRI(NS + "name");
    private static final RyaIRI G = new RyaIRI(NS + "g");
    private static final byte[] TRIPATHA = "tripatha".getBytes(StandardCharsets.UTF_8);

    private StoreRdfConfiguration conf;
    private RyaStoreDAO dao;

    @BeforeEach
    public void setUp() {
        grantAuthorizations("A");
        conf = newConf();
        dao = new RyaStoreDAO(getStore(), conf);
        dao.init();
    }

    @AfterEach
    public void tearDown() {
        dao.destroy();
    }

    @Test
    public void aStoreCreatedNowHasCompleteMarkersAndTheNewVersion() {
        assertTrue(dao.hasDatatypeMarkers());
        assertEquals(RdfCloudTripleStoreConstants.VERSION.stringValue(), dao.getVersion());
    }

    @Test
    public void eachPredicatesTypesAreRecordedUnderTheirStatementsLabels() {
        conf.setMaintainStats(false); // written whether or not statistics are on
        final RyaStoreDAO noStats = new RyaStoreDAO(getStore(), conf);
        noStats.init();
        try {
            noStats.add(List.of(
                    statement(S, AGE, new RyaType(XSD.INT, "40"), null, null),
                    statement(new RyaIRI(NS + "t"), AGE, new RyaType(XSD.DOUBLE, "40.5"), G, null),
                    statement(new RyaIRI(NS + "u"), AGE, new RyaType(XSD.LONG, "41"), null, "A"),
                    statement(S, NAME, new RyaType("Sam"), null, null)).iterator());
        } finally {
            noStats.destroy();
        }
        assertEquals(Optional.of(Set.of(XSD.INT.stringValue(), XSD.DOUBLE.stringValue())), dao.objectDatatypes(AGE, withAuths()));
        assertEquals(Optional.of(Set.of(XSD.INT.stringValue(), XSD.DOUBLE.stringValue(), XSD.LONG.stringValue())),
                dao.objectDatatypes(AGE, withAuths("A")), "a reader with the label sees the type of the statement under it");
        assertEquals(Optional.of(Set.of(XSD.STRING.stringValue())), dao.objectDatatypes(NAME, withAuths("A")));
        assertEquals(Optional.of(Set.of()), dao.objectDatatypes(new RyaIRI(NS + "none"), withAuths("A")));
    }

    @Test
    public void deletesAndDroppingEveryGraphLeaveTheMarkers() throws Exception {
        final RyaStatement st = statement(S, AGE, new RyaType(XSD.INT, "40"), null, null);
        dao.add(st);
        dao.delete(st, conf);
        assertEquals(Optional.of(Set.of(XSD.INT.stringValue())), dao.objectDatatypes(AGE, conf));
        dao.add(statement(S, AGE, new RyaType(XSD.DOUBLE, "1.5"), G, null));
        dao.dropGraph(conf);
        try (CloseableIteration<RyaStatement> left = dao.getQueryEngine().query(new RyaStatement(null, AGE, null), conf)) {
            assertFalse(left.hasNext());
        }
        final RyaStoreEvalStatsDAO stats = new RyaStoreEvalStatsDAO(getStore(), conf);
        stats.init();
        assertTrue(stats.getCardinality(conf, RdfEvalStatsDAO.CARDINALITY_OF.PREDICATE,
                List.of(org.tripatha.api.resolver.RyaToRdfConversions.convertIRI(AGE))) <= 0, "the counters are dropped");
        // A writer adding meanwhile may have written its marker already: only a purge removes them.
        assertEquals(Optional.of(Set.of(XSD.INT.stringValue(), XSD.DOUBLE.stringValue())), dao.objectDatatypes(AGE, conf));
    }

    @Test
    public void anOlderStoreHasNoCompleteMarkersAndKeepsItsVersion() throws Exception {
        makeOlder();
        final RyaStoreDAO older = new RyaStoreDAO(getStore(), conf);
        older.init();
        try {
            assertFalse(older.hasDatatypeMarkers());
            assertEquals(Optional.empty(), older.objectDatatypes(AGE, conf));
            assertEquals(RdfCloudTripleStoreConstants.VERSION_WITHOUT_MARKERS, older.getVersion());
            older.add(statement(S, AGE, new RyaType(XSD.INT, "40"), null, null)); // still writable

            older.purge(conf);
            assertEquals(RdfCloudTripleStoreConstants.VERSION_WITHOUT_MARKERS, older.getVersion(),
                    "statements under labels the purger lacks may stay, without markers");
        } finally {
            older.destroy();
        }
        final RyaStoreDAO reopened = new RyaStoreDAO(getStore(), conf);
        reopened.init();
        try {
            assertFalse(reopened.hasDatatypeMarkers(), "a purge doesn't make an older store's markers complete");
        } finally {
            reopened.destroy();
        }
    }

    @Test
    public void aPurgeKeepsTheVersionAndTheFlag() {
        dao.add(statement(S, AGE, new RyaType(XSD.INT, "40"), null, null));
        dao.purge(conf);
        assertEquals(RdfCloudTripleStoreConstants.VERSION.stringValue(), dao.getVersion());
        final RyaStoreDAO again = new RyaStoreDAO(getStore(), conf);
        again.init();
        try {
            assertTrue(again.hasDatatypeMarkers());
            assertEquals(Optional.of(Set.of()), again.objectDatatypes(AGE, conf), "the purge removed the visible markers");
        } finally {
            again.destroy();
        }
    }

    @Test
    public void aNewerStoreCanBeReadButNotWritten() throws Exception {
        dao.add(statement(S, AGE, new RyaType(XSD.INT, "40"), null, null));
        dao.flush();
        writeNs(new RowMutation(TRIPATHA).put(utf8("version"), new byte[0], new byte[0], utf8("1.10.0")));
        final RyaStoreDAO older = new RyaStoreDAO(getStore(), conf);
        older.init();
        try {
            try (CloseableIteration<RyaStatement> found = older.getQueryEngine().query(new RyaStatement(S, AGE, null), conf)) {
                assertTrue(found.hasNext(), "still readable");
            }
            final RyaDAOException refused = assertThrows(RyaDAOException.class,
                    () -> older.add(statement(S, NAME, new RyaType("Sam"), null, null)));
            assertTrue(refused.getMessage().contains("1.10.0"), refused.getMessage());
            assertThrows(RyaDAOException.class, () -> older.delete(new RyaStatement(S, AGE, null), conf));
            assertThrows(RyaDAOException.class, () -> older.dropGraph(conf, G));
            assertThrows(RyaDAOException.class, () -> older.addNamespace("ex", NS));
            assertThrows(RyaDAOException.class, () -> older.purge(conf));
        } finally {
            older.destroy();
        }
        assertEquals("1.10.0", dao.getVersion(), "nothing was written");
    }

    @Test
    public void aValueRangeHoldsBothEndsAndOnlyItsType() throws Exception {
        final List<RyaStatement> statements = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            statements.add(statement(new RyaIRI(NS + "i" + i), AGE, new RyaType(XSD.INT, Integer.toString(i)), null, null));
        }
        statements.add(statement(new RyaIRI(NS + "l3"), AGE, new RyaType(XSD.LONG, "3"), null, null));
        statements.add(statement(new RyaIRI(NS + "d3"), AGE, new RyaType(XSD.DOUBLE, "3.0"), G, null));
        dao.add(statements.iterator());
        assertEquals(List.of("i2", "i3", "i4"), subjects(range(enc(2), enc(4)), null));
        assertEquals(List.of("i1", "i2"), subjects(range(null, enc(2)), null));
        assertEquals(List.of("i4", "i5"), subjects(range(enc(4), null), null));
        assertEquals(List.of(), subjects(range(enc(2), enc(4)), G), "none in that graph");
    }

    // ------------------------------------------------------------------ helpers

    private static org.tripatha.api.domain.RyaValueRange range(final byte[] start, final byte[] stop) {
        return new org.tripatha.api.domain.RyaValueRange(XSD.INT,
                (byte) org.tripatha.api.resolver.impl.IntRyaTypeResolver.INT_LITERAL_MARKER, start, stop, "test");
    }

    private static byte[] enc(final int value) {
        return utf8(org.tripatha.api.resolver.impl.IntRyaTypeResolver.INT_STRING_TYPE_ENCODER.encode(value));
    }

    private List<String> subjects(final org.tripatha.api.domain.RyaValueRange range, final RyaIRI graph) {
        final List<String> out = new java.util.ArrayList<>();
        try (CloseableIteration<RyaStatement> found = dao.getQueryEngine().query(new RyaStatement(null, AGE, range, graph), conf)) {
            while (found.hasNext()) {
                out.add(found.next().getSubject().getData().substring(NS.length()));
            }
        }
        return out;
    }

    private StoreRdfConfiguration newConf() {
        final StoreRdfConfiguration c = new StoreRdfConfiguration();
        c.setTablePrefix(getTablePrefix());
        return c;
    }

    private StoreRdfConfiguration withAuths(final String... auths) {
        final StoreRdfConfiguration c = new StoreRdfConfiguration(conf);
        c.setAuths(auths);
        return c;
    }

    /** Turns the store into one created before the markers: version 1.0.0 and no flag. */
    private void makeOlder() throws Exception {
        writeNs(new RowMutation(TRIPATHA).delete(utf8(DatatypeMarkers.FLAG_CF), new byte[0], new byte[0])
                .put(utf8("version"), new byte[0], new byte[0], utf8(RdfCloudTripleStoreConstants.VERSION_WITHOUT_MARKERS)));
    }

    private void writeNs(final RowMutation m) throws Exception {
        try (CellWriter writer = getStore().newWriter(WriterOptions.DEFAULT)) {
            writer.table(conf.getTableLayoutStrategy().getNs()).write(m);
            writer.flush();
        }
    }

    private static RyaStatement statement(final RyaIRI s, final RyaIRI p, final RyaType o, final RyaIRI g, final String label) {
        return new RyaStatement(s, p, o, g, null, label == null ? null : utf8(label), null, null);
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
