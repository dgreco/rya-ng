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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.security.Authorizations;

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

    /** One add() of many statements leaves exactly the counters of adding them one by one. */
    @Test
    public void batchAddCountsLikeSingleAdds() throws Exception {
        grantAuthorizations("A", "B");
        final List<RyaStatement> batch = new ArrayList<>();
        final Map<String, Long> expected = new TreeMap<>();
        for (int i = 0; i < 500; i++) {
            final RyaIRI s = new RyaIRI(NS + "s" + (i % 37));
            final RyaIRI p = new RyaIRI(NS + "p" + (i % 5));
            final RyaType o = i % 3 == 0 ? new RyaIRI(NS + "s" + (i % 11)) : new RyaType("v" + (i % 23) + "-" + i);
            final RyaIRI g = i % 4 == 0 ? null : (i % 2 == 0 ? G1 : G2);
            final String label = i % 5 == 0 ? "A" : i % 7 == 0 ? "B" : null;
            final RyaStatement st = new RyaStatement(s, p, o, g, null,
                    label == null ? null : label.getBytes(StandardCharsets.UTF_8), null, 1000L + i);
            batch.add(st);
            for (final Mutation m : EvalStatsMutations.counters(st, true)) {
                for (final ColumnUpdate u : m.getUpdates()) {
                    expected.merge(cell(m.getRow(), u.getColumnFamily(), u.getColumnQualifier(), u.getColumnVisibility()),
                            1L, Long::sum);
                }
            }
        }
        dao.add(batch.iterator());
        dao.flush();
        final Map<String, Long> actual = new TreeMap<>();
        try (Scanner scanner = getClient().createScanner(conf.getTableLayoutStrategy().getEval(), new Authorizations("A", "B"))) {
            for (final Map.Entry<Key, org.apache.accumulo.core.data.Value> e : scanner) {
                final Key k = e.getKey();
                actual.put(cell(k.getRowData().toArray(), k.getColumnFamilyData().toArray(),
                        k.getColumnQualifierData().toArray(), k.getColumnVisibilityData().toArray()),
                        Long.parseLong(e.getValue().toString()));
            }
        }
        assertEquals(expected, actual);
        assertEquals(Set.of(G1, G2), contexts(new Authorizations("A", "B")));
    }

    /** A graph dropped and then written again is listed again, even when writes are batched. */
    @Test
    public void markerRewrittenAfterDropGraph() {
        dao.add(new RyaStatement(ALICE, KNOWS, BOB, G1));
        dao.dropGraph(conf, G1);
        assertEquals(Set.of(), contexts());

        final AccumuloRdfConfiguration batched = new AccumuloRdfConfiguration(conf);
        batched.setFlush(false);
        final AccumuloRyaDAO writer = new AccumuloRyaDAO(getClient(), batched);
        writer.init();
        try {
            writer.add(List.of(new RyaStatement(ALICE, KNOWS, BOB, G1), new RyaStatement(BOB, KNOWS, ALICE, G1)).iterator());
            writer.flush();
            assertEquals(Set.of(G1), contexts());
        } finally {
            writer.destroy();
        }
    }

    /** A marker removed as stale (its graph looked empty) is written again by the next add. */
    @Test
    public void markerRestoredAfterBeingRemovedAsStale() throws Exception {
        try (BatchWriter markers = getClient().createBatchWriter(conf.getTableLayoutStrategy().getGraphs())) {
            markers.addMutation(EvalStatsMutations.graphMarker(new RyaStatement(ALICE, KNOWS, BOB, G2)));
        }
        assertEquals(Set.of(), contexts(), "a marker without data is dropped");
        dao.add(List.of(new RyaStatement(ALICE, KNOWS, BOB, G2)).iterator());
        assertEquals(Set.of(G2), contexts());
    }

    private static String cell(final byte[] row, final byte[] cf, final byte[] cq, final byte[] cv) {
        return new String(row, StandardCharsets.UTF_8) + "|" + new String(cf, StandardCharsets.UTF_8) + "|"
                + new String(cq, StandardCharsets.UTF_8) + "|" + new String(cv, StandardCharsets.UTF_8);
    }

    private Set<RyaIRI> contexts(final Authorizations auths) {
        final AccumuloRdfConfiguration withAuths = new AccumuloRdfConfiguration(conf);
        withAuths.setAuths(auths.toString().split(","));
        final Set<RyaIRI> graphs = new HashSet<>();
        try (CloseableIteration<RyaIRI> it = dao.getContextIDs(withAuths)) {
            it.forEachRemaining(graphs::add);
        }
        return graphs;
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
