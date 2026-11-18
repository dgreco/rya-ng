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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaTripleTerm;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.persist.RdfEvalStatsDAO.CARDINALITY_OF;
import org.tripatha.api.resolver.RyaToRdfConversions;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.IndexVerifier;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.test.StoreITBase;

/**
 * RDF 1.2 triple terms are ordinary object values to the store: the DAO, the query engine, the
 * counters and the backends handle them with no code of their own. This checks that they do.
 */
class TripleTermStoreIT extends StoreITBase {
    private static final RyaIRI REIFIES = new RyaIRI(RDF.NAMESPACE + "reifies");
    private static final RyaIRI ALICE = new RyaIRI("urn:alice");
    private static final RyaIRI KNOWS = new RyaIRI("urn:knows");
    private static final RyaIRI G = new RyaIRI("urn:g");

    private StoreRdfConfiguration conf;
    private RyaStoreDAO dao;
    private RyaStoreEvalStatsDAO stats;

    @BeforeEach
    void setUp() throws Exception {
        grantAuthorizations("A", "B");
        conf = new StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        conf.setTextIndex(true);
        conf.setGeoIndex(true);
        conf.setAuths("A", "B");
        dao = new RyaStoreDAO(getStore(), conf);
        dao.init();
        stats = new RyaStoreEvalStatsDAO(getStore(), conf);
        stats.init();
    }

    @AfterEach
    void tearDown() {
        dao.destroy();
    }

    private static RyaTripleTerm term(final RyaType object) {
        return new RyaTripleTerm(ALICE, KNOWS, object);
    }

    private List<RyaStatement> query(final RyaStatement pattern) throws Exception {
        final List<RyaStatement> found = new ArrayList<>();
        try (CloseableIteration<RyaStatement> it = dao.getQueryEngine().query(pattern, conf)) {
            while (it.hasNext()) {
                found.add(it.next());
            }
        }
        return found;
    }

    private long count(final CARDINALITY_OF card, final RyaType... values) {
        final List<Value> rdf = new ArrayList<>();
        for (final RyaType value : values) {
            rdf.add(RyaToRdfConversions.convertValue(value));
        }
        return (long) stats.getCardinality(conf, card, rdf);
    }

    private int cells(final IndexType type) throws Exception {
        int n = 0;
        try (CellScanner scanner = getStore().scan(type.table(conf.getTableLayoutStrategy()), KeyRange.all(),
                ScanSpec.of(Auths.of("A", "B")))) {
            while (scanner.hasNext()) {
                scanner.next();
                n++;
            }
        }
        return n;
    }

    @Test
    void addLookUpAndDeleteExactly() throws Exception {
        final RyaTripleTerm bob = term(new RyaIRI("urn:bob"));
        final RyaTripleTerm text = term(new RyaType(RDF.LANGSTRING, "Bob the builder", "en"));
        final RyaTripleTerm nested = new RyaTripleTerm(new RyaIRI("urn:carol"), new RyaIRI("urn:says"), bob);
        final byte[] labelA = "A".getBytes(StandardCharsets.UTF_8);
        final List<RyaStatement> statements = List.of(
                new RyaStatement(new RyaIRI("urn:r1"), REIFIES, bob),
                new RyaStatement(new RyaIRI("urn:r2"), REIFIES, bob, G),
                new RyaStatement(new RyaIRI("urn:r3"), REIFIES, text, null, null, labelA, null),
                new RyaStatement(new RyaIRI("urn:r4"), REIFIES, nested),
                // Values whose text is a triple term's data, or its inner object: neither is the term.
                new RyaStatement(new RyaIRI("urn:r5"), REIFIES, new RyaType(bob.getData())),
                new RyaStatement(new RyaIRI("urn:r6"), REIFIES, new RyaIRI("urn:bob")));
        dao.add(statements.iterator());
        dao.flush();

        // Every table, by exact value.
        assertEquals(List.of(bob), query(new RyaStatement(new RyaIRI("urn:r1"), null, null)).stream().map(RyaStatement::getObject).toList());
        assertEquals(2, query(new RyaStatement(null, null, bob)).size());
        assertEquals(2, query(new RyaStatement(null, REIFIES, bob)).size());
        assertEquals(1, query(new RyaStatement(null, REIFIES, bob, G)).size());
        assertEquals(1, query(new RyaStatement(new RyaIRI("urn:r4"), REIFIES, nested)).size());
        assertEquals(1, query(new RyaStatement(null, null, text)).size());
        final RyaTripleTerm read = (RyaTripleTerm) query(new RyaStatement(new RyaIRI("urn:r4"), null, null)).get(0).getObject();
        assertEquals(bob, read.getObject());
        assertEquals(6, query(new RyaStatement(null, REIFIES, null)).size());

        // The counters are keyed on a value's data, whatever its type, so urn:r5's string counts here too.
        assertEquals(3, count(CARDINALITY_OF.OBJECT, bob));
        assertEquals(3, count(CARDINALITY_OF.PREDICATEOBJECT, REIFIES, bob));
        assertEquals(1, count(CARDINALITY_OF.OBJECT, nested));
        assertEquals(1, count(CARDINALITY_OF.OBJECT, new RyaIRI("urn:bob")));

        // The text inside a triple term is not indexed: once the plain string is gone, nothing is.
        assertTrue(cells(IndexType.TEXT) > 0);
        dao.delete(statements.get(4), conf);
        dao.flush();
        assertEquals(0, cells(IndexType.TEXT));
        assertEquals(1, query(new RyaStatement(null, null, text)).size());
        assertEquals(2, count(CARDINALITY_OF.OBJECT, bob));
        for (final IndexType type : IndexType.values()) {
            if (type.isEnabled(conf)) {
                assertTrue(IndexVerifier.verify(getStore(), conf, type).isClean(), type.toString());
            }
        }

        // An exact delete removes that term's statements only.
        dao.delete(new RyaStatement(null, REIFIES, bob), conf);
        dao.flush();
        assertEquals(0, query(new RyaStatement(null, null, bob)).size());
        assertEquals(3, query(new RyaStatement(null, REIFIES, null)).size());
        assertTrue(count(CARDINALITY_OF.OBJECT, bob) <= 0);
        assertTrue(count(CARDINALITY_OF.PREDICATEOBJECT, REIFIES, bob) <= 0);
        assertEquals(1, count(CARDINALITY_OF.OBJECT, nested));

        dao.delete(query(new RyaStatement(null, REIFIES, null)).iterator(), conf);
        dao.flush();
        assertEquals(0, query(new RyaStatement(null, REIFIES, null)).size());
        assertTrue(count(CARDINALITY_OF.OBJECT, nested) <= 0);
        assertTrue(count(CARDINALITY_OF.PREDICATE, REIFIES) <= 0);
        assertEquals(0, cells(IndexType.TEXT));
    }

    @Test
    void aTermTooLongForARowIsRefusedOnHBase() throws Exception {
        final RyaStatement statement = new RyaStatement(new RyaIRI("urn:r"), new RyaIRI(RDFS.COMMENT.stringValue()),
                term(new RyaType("x".repeat(40_000))));
        if (onBackend("hbase")) {
            assertThrows(RuntimeException.class, () -> {
                dao.add(statement);
                dao.flush();
            });
        } else {
            dao.add(statement);
            dao.flush();
            assertEquals(List.of(statement.getObject()),
                    query(new RyaStatement(new RyaIRI("urn:r"), null, null)).stream().map(RyaStatement::getObject).toList());
        }
    }
}
