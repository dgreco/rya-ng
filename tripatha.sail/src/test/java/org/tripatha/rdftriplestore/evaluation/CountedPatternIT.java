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
package org.tripatha.rdftriplestore.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tripatha.RyaSailITBase;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

/**
 * Patterns whose object no other part of the query uses are counted, not read (SCALE-STUDY.md
 * O26), and give the rows they would have given: every query here runs with
 * {@code query.join.countunused} on and off, with and without the label A, and those that count
 * say so in their trace.
 *
 * <p>The data has what a count could get wrong: subjects with no statement for a pattern (an
 * OPTIONAL keeps them once, a join drops them), a statement repeated in a named graph and under a
 * label (one row each, as a lookup gives), and statements a time-to-live leaves out.
 */
public class CountedPatternIT extends RyaSailITBase {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI KNOWS = VF.createIRI("urn:knows");
    private static final IRI LIKES = VF.createIRI("urn:likes");
    private static final IRI NAME = VF.createIRI("urn:name");
    private static final IRI CLASS = VF.createIRI("urn:C");
    private static final IRI G1 = VF.createIRI("urn:g1");
    private static final int SUBJECTS = 30;
    /** When the statements were written: {@link #OLD} ones fall outside the time-to-live tests use. */
    private static final long NOW = 5;
    private static final long OLD = 1;

    private static final String STAR = "?x a <urn:C> ; <urn:knows> ?f";

    /** Queries that count: a batched join, entity chains, an OPTIONAL, a count, DISTINCT, a graph, a blank node, LIMIT. */
    private static final List<String> COUNTED = List.of(
            "SELECT ?x WHERE { " + STAR + " }",
            "SELECT ?x ?n WHERE { ?x a <urn:C> ; <urn:name> ?n ; <urn:knows> ?f ; <urn:likes> ?l }",
            "SELECT ?x WHERE { ?x a <urn:C> OPTIONAL { ?x <urn:knows> ?f } }",
            "SELECT ?x ?n WHERE { ?x a <urn:C> ; <urn:name> ?n OPTIONAL { ?x <urn:knows> ?f } OPTIONAL { ?x <urn:likes> ?l } }",
            "SELECT (COUNT(*) AS ?c) WHERE { " + STAR + " }",
            "SELECT ?x (COUNT(*) AS ?c) WHERE { " + STAR + " } GROUP BY ?x",
            "SELECT DISTINCT ?x WHERE { " + STAR + " }",
            // Driven by one name, so the graph's pattern is looked up for it.
            "SELECT ?x WHERE { ?x <urn:name> \"n1\" . GRAPH <urn:g1> { ?x <urn:knows> ?f } }",
            "SELECT ?x WHERE { ?x a <urn:C> ; <urn:knows> [] }",
            "SELECT ?x WHERE { " + STAR + " } ORDER BY ?x LIMIT 7",
            "SELECT ?x ?y WHERE { ?x a <urn:C> ; <urn:knows> ?y . ?y <urn:knows> ?z }");

    /** Queries whose object is used, so nothing is counted; they must not change either. */
    private static final List<String> NOT_COUNTED = List.of(
            "SELECT * WHERE { " + STAR + " }",
            "SELECT (COUNT(DISTINCT *) AS ?c) WHERE { " + STAR + " }",
            "SELECT ?x WHERE { " + STAR + " FILTER(?f != <urn:s1>) }",
            "SELECT ?x WHERE { " + STAR + " VALUES ?f { <urn:s1> <urn:s2> } }",
            "SELECT ?x ?y WHERE { " + STAR + " BIND(?f AS ?y) }",
            "SELECT ?x WHERE { " + STAR + " FILTER EXISTS { ?f <urn:name> ?n } }",
            "SELECT ?x WHERE { ?x a <urn:C> ; <urn:knows> ?x }",
            "SELECT ?x ?f WHERE { " + STAR + " }",
            // COUNT(?f) counts bound values: it uses ?f (RDF4J's SAMPLE would too, but picks at random).
            "SELECT ?x (COUNT(?f) AS ?n) WHERE { " + STAR + " } GROUP BY ?x");

    /** Counted, but each row looked up: the graph is bound by each statement, or is the default graph alone. */
    private static final List<String> LOOKED_UP = List.of(
            "SELECT ?x ?g WHERE { ?x a <urn:C> . GRAPH ?g { ?x <urn:knows> ?f } }",
            "SELECT ?x WHERE { ?x a <urn:C> . GRAPH <http://rdf4j.org/schema/rdf4j#nil> { ?x <urn:knows> ?f } }");

    private final List<SailRepository> repositories = new ArrayList<>();

    @BeforeEach
    public void load() throws Exception {
        grantAuthorizations("A");
        final StoreRdfConfiguration conf = newConf();
        final RyaStoreDAO dao = newDao(conf);
        final List<RyaStatement> statements = new ArrayList<>();
        for (int i = 0; i < SUBJECTS; i++) {
            final String s = "urn:s" + i;
            if (i < 25) {
                statements.add(st(s, RDF.TYPE, CLASS.stringValue(), null, null, NOW));
            }
            statements.add(new RyaStatement(new RyaIRI(s), new RyaIRI(NAME.stringValue()), new RyaType("n" + i), null, null, null,
                    null, NOW));
            if (i % 3 != 0) { // every third subject knows nobody
                statements.add(st(s, KNOWS, "urn:s" + (i + 1) % SUBJECTS, null, null, NOW));
                statements.add(st(s, KNOWS, "urn:s" + (i + 2) % SUBJECTS, null, null, NOW));
            }
            if (i % 5 == 1) { // the same statement in a named graph: one more row
                statements.add(st(s, KNOWS, "urn:s" + (i + 1) % SUBJECTS, G1.stringValue(), null, NOW));
            }
            if (i % 7 == 2) { // and under a label: one more row for a reader holding it
                statements.add(st(s, KNOWS, "urn:s" + (i + 1) % SUBJECTS, null, "A", NOW));
            }
            if (i % 2 == 0) {
                statements.add(st(s, LIKES, "urn:s" + (i + 3) % SUBJECTS, null, null, NOW));
            }
            if (i % 4 == 1) { // an old statement, which a time-to-live leaves out
                statements.add(st(s, KNOWS, "urn:old" + i, null, null, OLD));
            }
        }
        dao.add(statements.iterator());
    }

    private static RyaStatement st(final String s, final IRI p, final String o, final String graph, final String label,
            final long timestamp) {
        return new RyaStatement(new RyaIRI(s), new RyaIRI(p.stringValue()), new RyaIRI(o), graph == null ? null : new RyaIRI(graph),
                null, label == null ? null : label.getBytes(StandardCharsets.UTF_8), null, timestamp);
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    @Test
    public void countedPatternsGiveTheRowsOfLookups() {
        // A store that would read to count (Accumulo's and HBase's, for now) counts the fetched lists instead.
        final boolean apart = getStore().countsWithoutReading(newConf().getTableLayoutStrategy().getSpo());
        for (final boolean labelled : List.of(true, false)) {
            final SailRepository plain = repository(labelled, false, null);
            final SailRepository counting = repository(labelled, true, null);
            final String which = labelled ? " (label A)" : "";
            for (final String query : COUNTED) {
                assertEquals(rows(plain, query), rows(counting, query), query + which);
                assertEquals(apart, counted(counting, query), "counted apart: " + query + which);
                assertFalse(counted(plain, query), "not counted when off: " + query + which);
            }
            for (final String query : NOT_COUNTED) {
                assertEquals(rows(plain, query), rows(counting, query), query + which);
                assertFalse(counted(counting, query), "not counted: " + query + which);
            }
            for (final String query : LOOKED_UP) {
                assertEquals(rows(plain, query), rows(counting, query), query + which);
            }
        }
    }

    /** The store's counts are the sizes of the lists a fetch gets, whether or not the store counts in its servers. */
    @Test
    public void batchCountsAreTheSizesOfTheFetchedLists() {
        for (final boolean labelled : List.of(true, false)) {
            final StoreRdfConfiguration conf = newConf();
            if (labelled) {
                conf.setAuths("A");
            }
            final RyaStoreDAO dao = newDao(conf);
            final List<LookupKey> keys = new ArrayList<>();
            for (int i = 0; i < SUBJECTS; i++) {
                final IRI s = VF.createIRI("urn:s" + i);
                keys.add(LookupKey.of(s, KNOWS, null));
                keys.add(LookupKey.of(s, LIKES, null));
                keys.add(LookupKey.of(s, KNOWS, null, G1));
            }
            final java.util.Map<LookupKey, Long> counts = BatchLookup.count(dao, conf, keys);
            final java.util.Map<LookupKey, List<org.eclipse.rdf4j.model.Statement>> fetched = BatchLookup.fetch(dao, conf, keys, 100_000);
            assertEquals(keys.size(), counts.size());
            long total = 0;
            for (final LookupKey key : keys) {
                assertEquals(fetched.get(key).size(), (long) counts.get(key), key + (labelled ? " (label A)" : ""));
                total += counts.get(key);
            }
            assertTrue(total > 0);
        }
    }

    @Test
    public void aCountKeepsToTheTimeToLive() {
        // A window from 3 to 6: the statements written at 5 count, the old ones, at 1, don't.
        final SailRepository plain = repository(true, false, 3L);
        final SailRepository counting = repository(true, true, 3L);
        for (final String query : COUNTED) {
            assertEquals(rows(plain, query), rows(counting, query), query + " (ttl)");
        }
        final String all = "SELECT ?x WHERE { VALUES ?x { <urn:s1> } ?x <urn:knows> ?f }";
        assertEquals(4, rows(repository(true, true, null), all).size(), "s1's old statement counts without a time-to-live");
        assertEquals(3, rows(counting, all).size(), "and not with one");
    }

    @Test
    public void anOutsideBindingOfTheObjectIsLookedUp() {
        final SailRepository plain = repository(false, false, null);
        final SailRepository counting = repository(false, true, null);
        final String query = "SELECT ?x WHERE { " + STAR + " }";
        assertEquals(rows(plain, query, "urn:s2"), rows(counting, query, "urn:s2"));
        assertFalse(rows(counting, query, "urn:s2").isEmpty());
    }

    @Test
    public void rowsRepeatAsManyTimesAsThePatternMatches() {
        // s1 knows s2 and s3, s2 again in urn:g1, and urn:old1: four rows. s2 knows s3 and s4, and
        // s3 again under A: three. s3 knows nobody.
        final SailRepository counting = repository(true, true, null);
        final List<String> rows = rows(counting, "SELECT ?x WHERE { VALUES ?x { <urn:s1> <urn:s2> <urn:s3> } ?x <urn:knows> ?f }");
        assertEquals(List.of("x=urn:s1 ", "x=urn:s1 ", "x=urn:s1 ", "x=urn:s1 ", "x=urn:s2 ", "x=urn:s2 ", "x=urn:s2 "), rows);
    }

    /** A repository reading with or without label A and the count, and with a time-to-live ending at 6 when given. */
    private SailRepository repository(final boolean labelled, final boolean countUnused, final Long ttlMillis) {
        final StoreRdfConfiguration conf = newConf();
        conf.setJoinCountUnused(countUnused);
        if (labelled) {
            conf.setAuths("A");
        }
        if (ttlMillis != null) {
            conf.setStartTime(6L);
            conf.setTtl(ttlMillis);
        }
        final SailRepository repo = new SailRepository(newStore(conf, false));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    private static boolean counted(final SailRepository repo, final String query) {
        final List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan && scan.reason() != null) {
                reasons.add(scan.reason());
            }
        })) {
            rows(repo, query);
        }
        synchronized (reasons) {
            return reasons.stream().anyMatch(r -> r.contains("counted lookup"));
        }
    }

    private static List<String> rows(final SailRepository repo, final String query) {
        return rows(repo, query, null);
    }

    /** The rows, as text, sorted, with {@code ?f} bound from outside when {@code f} isn't null. */
    private static List<String> rows(final SailRepository repo, final String query, final String f) {
        final List<String> rows = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TupleQuery q = conn.prepareTupleQuery(query);
            if (f != null) {
                q.setBinding("f", VF.createIRI(f));
            }
            try (TupleQueryResult result = q.evaluate()) {
                while (result.hasNext()) {
                    final BindingSet bs = result.next();
                    final StringBuilder b = new StringBuilder();
                    for (final String name : new TreeSet<>(bs.getBindingNames())) {
                        b.append(name).append('=').append(bs.getValue(name)).append(' ');
                    }
                    rows.add(b.toString());
                }
            }
        }
        Collections.sort(rows);
        return rows;
    }
}
