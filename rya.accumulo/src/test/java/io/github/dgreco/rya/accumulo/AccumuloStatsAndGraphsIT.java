package io.github.dgreco.rya.accumulo;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.domain.StatementMetadata;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO.CARDINALITY_OF;
import io.github.dgreco.rya.test.accumulo.AccumuloITBase;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AccumuloStatsAndGraphsIT extends AccumuloITBase {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String NS = "urn:test#";

    private static final RyaIRI ALICE = new RyaIRI(NS + "alice");
    private static final RyaIRI BOB = new RyaIRI(NS + "bob");
    private static final RyaIRI KNOWS = new RyaIRI(NS + "knows");
    private static final RyaIRI NAME = new RyaIRI(NS + "name");
    private static final RyaIRI G1 = new RyaIRI(NS + "g1");
    private static final RyaIRI G2 = new RyaIRI(NS + "g2");

    private AccumuloRdfConfiguration conf;
    private AccumuloRyaDAO dao;
    private AccumuloRdfEvalStatsDAO stats;

    @BeforeEach
    public void setUp() throws Exception {
        grantAuthorizations("A");
        conf = new AccumuloRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        dao = new AccumuloRyaDAO(getClient(), conf);
        dao.init();
        stats = new AccumuloRdfEvalStatsDAO(getClient(), conf);
        stats.init();
    }

    @AfterEach
    public void tearDown() {
        dao.destroy();
    }

    @Test
    public void countersFollowAddsAndDeletes() {
        dao.add(new RyaStatement(ALICE, KNOWS, BOB));
        dao.add(new RyaStatement(ALICE, NAME, new RyaType("Alice")));
        dao.add(new RyaStatement(BOB, KNOWS, ALICE));

        assertEquals(2.0, card(CARDINALITY_OF.SUBJECT, iri(ALICE)));
        assertEquals(2.0, card(CARDINALITY_OF.PREDICATE, iri(KNOWS)));
        assertEquals(1.0, card(CARDINALITY_OF.OBJECT, iri(BOB)));
        assertEquals(1.0, card(CARDINALITY_OF.OBJECT, VF.createLiteral("Alice")));
        assertEquals(1.0, card(CARDINALITY_OF.SUBJECTPREDICATE, iri(ALICE), iri(KNOWS)));
        assertEquals(1.0, card(CARDINALITY_OF.PREDICATEOBJECT, iri(KNOWS), iri(BOB)));
        assertEquals(-1.0, card(CARDINALITY_OF.SUBJECT, VF.createIRI(NS, "nobody")));

        dao.delete(new RyaStatement(ALICE, KNOWS, BOB), conf);
        assertEquals(1.0, card(CARDINALITY_OF.SUBJECT, iri(ALICE)));
        assertEquals(1.0, card(CARDINALITY_OF.PREDICATE, iri(KNOWS)));
        assertEquals(0.0, card(CARDINALITY_OF.OBJECT, iri(BOB)));
    }

    @Test
    public void countersRespectVisibility() {
        dao.add(new RyaStatement(ALICE, KNOWS, BOB, null, null, new StatementMetadata(), "A".getBytes(StandardCharsets.UTF_8)));

        assertEquals(-1.0, card(CARDINALITY_OF.SUBJECT, iri(ALICE)));
        final AccumuloRdfConfiguration withA = new AccumuloRdfConfiguration(conf);
        withA.setAuth("A");
        assertEquals(1.0, stats.getCardinality(withA, CARDINALITY_OF.SUBJECT, List.of(iri(ALICE))));
    }

    @Test
    public void countersPerGraph() {
        dao.add(new RyaStatement(ALICE, KNOWS, BOB, G1));
        dao.add(new RyaStatement(ALICE, KNOWS, ALICE, G2));
        dao.add(new RyaStatement(ALICE, NAME, new RyaType("Alice")));

        assertEquals(3.0, card(CARDINALITY_OF.SUBJECT, iri(ALICE)));
        assertEquals(1.0, stats.getCardinality(conf, CARDINALITY_OF.SUBJECT, List.of(iri(ALICE)), iri(G1)));
    }

    @Test
    public void contextIdsListNonEmptyGraphs() {
        dao.add(new RyaStatement(ALICE, KNOWS, BOB, G1));
        dao.add(new RyaStatement(BOB, KNOWS, ALICE, G2));
        dao.add(new RyaStatement(ALICE, NAME, new RyaType("Alice")));
        assertEquals(Set.of(G1, G2), contexts());

        dao.delete(new RyaStatement(ALICE, KNOWS, BOB, G1), conf);
        assertEquals(Set.of(G2), contexts());

        dao.dropGraph(conf, G2);
        assertEquals(Set.of(), contexts());
    }

    @Test
    public void contextIdsHideGraphsWithOnlyInvisibleData() {
        dao.add(new RyaStatement(ALICE, KNOWS, BOB, G1, null, new StatementMetadata(), "A".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Set.of(), contexts());
        final AccumuloRdfConfiguration withA = new AccumuloRdfConfiguration(conf);
        withA.setAuth("A");
        try (CloseableIteration<RyaIRI> it = dao.getContextIDs(withA)) {
            assertEquals(G1, it.next());
        }
    }

    @Test
    public void sizeCountsWholeStoreOrGraphs() {
        dao.add(new RyaStatement(ALICE, KNOWS, BOB, G1));
        dao.add(new RyaStatement(BOB, KNOWS, ALICE, G2));
        dao.add(new RyaStatement(ALICE, NAME, new RyaType("Alice")));
        dao.add(new RyaStatement(BOB, NAME, new RyaType("Bob"), null, null, new StatementMetadata(), "A".getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, dao.size(conf));
        assertEquals(1, dao.size(conf, G1));
        assertEquals(2, dao.size(conf, G1, G2));
        assertEquals(1, dao.size(conf, (RyaIRI) null));
        final AccumuloRdfConfiguration withA = new AccumuloRdfConfiguration(conf);
        withA.setAuth("A");
        assertEquals(4, dao.size(withA));
    }

    private double card(final CARDINALITY_OF card, final Value... values) {
        return stats.getCardinality(conf, card, List.of(values));
    }

    private static IRI iri(final RyaIRI iri) {
        return VF.createIRI(iri.getData());
    }

    private Set<RyaIRI> contexts() {
        final Set<RyaIRI> out = new HashSet<>();
        try (CloseableIteration<RyaIRI> it = dao.getContextIDs(conf)) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }
}
