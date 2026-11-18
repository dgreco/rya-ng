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
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tripatha.RyaSailITBase;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.rdftriplestore.RdfCloudTripleStore;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.IndexVerifier;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

/**
 * Merge joins of a star's constant patterns (O22) give the answers of the nested loops they
 * replace: every query here runs with {@code query.join.mergeratio} 0 (off) and with merges
 * forced, with and without the label the data's duplicates carry.
 *
 * <p>The data puts in each list what a merge could get wrong: a subject in some lists only;
 * statements repeated in a named graph and under a label (a nested loop gives one row per
 * statement); values with the same data but another type, which must not match; and subjects
 * whose UTF-16 order is not their stored (UTF-8) order.
 */
public class SubjectMergeIT extends RyaSailITBase {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI P = VF.createIRI("urn:p");
    private static final IRI Q = VF.createIRI("urn:q");
    private static final IRI R = VF.createIRI("urn:r");
    private static final IRI NAME = VF.createIRI("urn:name");
    private static final IRI AGE = VF.createIRI("urn:age");
    private static final IRI LINK = VF.createIRI("urn:link");
    private static final IRI A = VF.createIRI("urn:A");
    private static final IRI CLASS = VF.createIRI("urn:C");
    private static final IRI G1 = VF.createIRI("urn:g1");

    /** Subjects that sort differently by UTF-16 unit and by code point: U+E000, U+FFFD and U+1F600. */
    private static final List<String> ODD = List.of("urn:s", "urn:s�", "urn:s😀", "urn:s😀x");

    private static final List<String> QUERIES = List.of(
            "SELECT ?x WHERE { ?x <urn:p> <urn:A> . ?x <urn:q> \"x\" }",
            "SELECT ?x WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" ; <urn:r> 1 }",
            "SELECT ?x WHERE { ?x a <urn:C> ; <urn:p> <urn:A> ; <urn:q> \"x\" }",
            "SELECT ?x ?n ?a WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" ; <urn:name> ?n OPTIONAL { ?x <urn:age> ?a } }",
            "SELECT (COUNT(*) AS ?c) WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" }",
            "SELECT DISTINCT ?x WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" }",
            "SELECT ?x WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" FILTER(?x != <urn:s1>) }",
            "SELECT ?x ?y WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" ; <urn:link> ?y . ?y <urn:r> 1 }",
            // The star under an OPTIONAL is evaluated once per row, with ?x bound.
            "SELECT ?x ?n ?ok WHERE { ?x <urn:name> ?n OPTIONAL { ?x <urn:p> <urn:A> ; <urn:q> \"x\" BIND(true AS ?ok) } }",
            "SELECT ?x WHERE { GRAPH <urn:g1> { ?x <urn:p> <urn:A> ; <urn:q> \"x\" } }",
            "SELECT ?x WHERE { VALUES ?x { <urn:s1> <urn:s2> <urn:s3> } ?x <urn:p> <urn:A> ; <urn:q> \"x\" }",
            "SELECT ?x WHERE { ?x <urn:q> \"x\"^^<urn:dt> ; <urn:p> <urn:A> }",
            "SELECT ?x WHERE { ?x <urn:r> \"1\"^^<http://www.w3.org/2001/XMLSchema#int> ; <urn:p> <urn:A> }",
            "SELECT ?x WHERE { ?x <urn:p> <urn:A> ; <urn:p> <urn:A> }",
            "SELECT ?x WHERE { ?x <urn:p> <urn:nothing> ; <urn:q> \"x\" }");

    private final List<SailRepository> repositories = new ArrayList<>();
    private final List<String> subjects = new ArrayList<>();

    @BeforeEach
    public void load() {
        grantAuthorizations("A");
        for (int i = 0; i < 40; i++) {
            subjects.add("urn:s" + i);
        }
        subjects.addAll(ODD);
        final SailRepository repo = repository(true, 0);
        // While spo is empty: HBase can't split a region again before compacting it.
        split();
        try (RepositoryConnection conn = repo.getConnection()) {
            for (int i = 0; i < subjects.size(); i++) {
                final IRI s = VF.createIRI(subjects.get(i));
                final boolean odd = i >= 40;
                if (odd || i % 2 == 0) {
                    conn.add(s, P, A);
                }
                if (odd || i % 3 == 0) {
                    conn.add(s, Q, VF.createLiteral("x"));
                }
                if (i % 3 == 1) {
                    // The same data with another type, or a language: not "x".
                    conn.add(s, Q, VF.createLiteral("x", VF.createIRI("urn:dt")));
                    conn.add(s, Q, VF.createLiteral("x", "en"));
                }
                if (odd || i % 4 == 0) {
                    conn.add(s, R, VF.createLiteral(1));
                } else if (i % 4 == 2) {
                    conn.add(s, R, VF.createLiteral("1", XSD.INT));
                    conn.add(s, R, VF.createLiteral("1"));
                }
                if (i % 6 == 0) {
                    // Twice in the default scope: in the default graph and in a named one.
                    conn.add(s, P, A, G1);
                    conn.add(s, Q, VF.createLiteral("x"), G1);
                }
                if (i % 5 != 4) {
                    conn.add(s, NAME, VF.createLiteral("n" + i));
                }
                if (i % 2 == 1 || odd) {
                    conn.add(s, AGE, VF.createLiteral(i));
                }
                conn.add(s, LINK, VF.createIRI(subjects.get((i + 1) % subjects.size())));
            }
            // A class much longer than the other lists.
            for (int i = 0; i < 400; i++) {
                conn.add(VF.createIRI(i < subjects.size() ? subjects.get(i) : "urn:t" + i), RDF.TYPE, CLASS);
            }
        }
        // A statement already stored, again under a label: two cells, two rows for a reader with the label.
        final StoreRdfConfiguration conf = newConf();
        final RyaStoreDAO dao = newDao(conf);
        try {
            for (final String s : List.of("urn:s0", "urn:s6", "urn:s12", ODD.get(2))) {
                dao.add(new RyaStatement(new RyaIRI(s), new RyaIRI(P.stringValue()), new RyaIRI(A.stringValue()), null, null,
                        "A".getBytes(StandardCharsets.UTF_8), null, 1L));
            }
        } finally {
            dao.destroy();
        }
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    @Test
    public void mergedStarsGiveTheAnswersOfNestedLoops() {
        for (final boolean labelled : List.of(true, false)) {
            final SailRepository plain = repository(labelled, 0);
            final SailRepository merged = repository(labelled, 1_000_000);
            for (final String query : QUERIES) {
                assertEquals(rows(plain, query), rows(merged, query), query + (labelled ? " (label A)" : ""));
            }
        }
    }

    @Test
    public void duplicatesAreCountedAsTheNestedLoopCountsThem() {
        // In both lists: s0, s6, …, s36 and the four odd subjects, 11 in all. s0, s6, …, s36 and
        // the third odd one are also in g1, so each list has two statements for them: four rows.
        // s0, s6, s12 and the third odd one have a third p statement under label A: six rows for
        // a reader with the label. The three other odd subjects give one row each.
        final List<String> labelled = rows(repository(true, 1_000_000), QUERIES.get(0));
        assertEquals(4 * 6 + 4 * 4 + 3, labelled.size(), labelled.toString());
        assertEquals(11, new TreeSet<>(labelled).size());
        assertEquals(8 * 4 + 3, rows(repository(false, 1_000_000), QUERIES.get(0)).size());
    }

    @Test
    public void aStarIsReadInSubjectOrderWhenMerged() {
        final Read read = read(repository(true, 1_000_000), QUERIES.get(0));
        assertEquals(2, read.mergedLists, "both lists are read in subject order");
        assertEquals(0, read.prefetches, "and nothing is looked up per row");
        assertEquals(0, read(repository(true, 0), QUERIES.get(0)).mergedLists, "merges are off at 0");
    }

    @Test
    public void aRatioLeftUnsetIsTheBackendsOwn() {
        // The lists of the first query are within 1.5 times of each other: merged at any backend's ratio.
        final StoreRdfConfiguration conf = newConf();
        conf.unset(RdfCloudTripleStoreConfiguration.CONF_JOIN_MERGE_RATIO);
        conf.setJoinMergeSlices(1); // the whole lists of po, as the count below expects
        conf.setUseIndex(false);
        conf.setAuths("A");
        final SailRepository unset = new SailRepository(newStore(conf, false));
        unset.init();
        repositories.add(unset);
        assertEquals(getStore().capabilities().lookupCost(), conf.getJoinMergeRatio());
        assertEquals(2, read(unset, QUERIES.get(0)).mergedLists);

        final StoreRdfConfiguration off = newConf();
        off.setJoinMergeRatio(0);
        newStore(off, false);
        assertEquals(0, off.getJoinMergeRatio(), "an explicit 0 stays 0");
    }

    @Test
    public void attributesAboveAMergeAreStillPrefetchedPerEntity() {
        final Read read = read(repository(true, 1_000_000), QUERIES.get(3));
        assertEquals(2, read.mergedLists);
        assertTrue(read.entityPrefetches > 0, "the name and age lookups are an entity chain over the merge");
        assertTrue(read.plan.contains("SubjectMergeJoin") && read.plan.contains("RyaEntityPrefetch"), read.plan);
    }

    @Test
    public void aListMuchLongerThanTheShortestIsLookedUpInstead() {
        // The class has 400 subjects, the p and q lists 25 and 18 (plus duplicates): at a ratio
        // of 4 the class is looked up for the merge's rows.
        final Read read = read(repository(true, 4), QUERIES.get(2));
        assertEquals(2, read.mergedLists, read.plan);
        assertTrue(read.prefetches > 0, "the class is looked up, in batches");
        assertEquals(rows(repository(true, 0), QUERIES.get(2)), rows(repository(true, 4), QUERIES.get(2)));
    }

    @Test
    public void aStarInAGroupOfItsOwnIsMergedOnce() {
        // RDF4J evaluates a group with a scope of its own once, and joins it with the rows outside.
        final String query = "SELECT ?y ?x WHERE { ?y <urn:link> ?z . { ?x <urn:p> <urn:A> ; <urn:q> \"x\" BIND(1 AS ?one) } }";
        assertEquals(rows(repository(true, 0), query), rows(repository(true, 1_000_000), query));
        final Read read = read(repository(true, 1_000_000), query);
        assertEquals(2, read.mergedLists, "one merge, not one per row outside: " + read.plan);
    }

    @Test
    public void aLimitReturnsRowsOfTheFullAnswer() {
        final String query = QUERIES.get(0);
        final List<String> all = rows(repository(true, 0), query);
        final List<String> some = rows(repository(true, 1_000_000), query + " LIMIT 5");
        assertEquals(5, some.size());
        assertTrue(all.containsAll(some), some + " in " + all);
    }

    @Test
    public void inferredTypesAndPropertiesGiveTheSameAnswers() throws Exception {
        final SailRepository plain = repository(true, 0, true);
        final SailRepository merged = repository(true, 1_000_000, true);
        try (RepositoryConnection conn = plain.getConnection()) {
            conn.prepareUpdate("INSERT DATA { <urn:D> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <urn:C> . "
                    + "<urn:p2> <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <urn:p> . "
                    + "<urn:u1> a <urn:D> ; <urn:p> <urn:A> ; <urn:q> \"x\" . <urn:s9> <urn:p2> <urn:A> }").execute();
        }
        for (final SailRepository repo : List.of(plain, merged)) {
            ((RdfCloudTripleStore<?>) repo.getSail()).getInferenceEngine().refreshGraph();
        }
        for (final String query : List.of(QUERIES.get(0), QUERIES.get(2), QUERIES.get(3), QUERIES.get(4))) {
            final List<String> expected = rows(plain, query);
            assertEquals(expected, rows(merged, query), query);
        }
        // The inferred answers are there: u1 through its subclass, s9 through the sub-property.
        assertTrue(rows(plain, QUERIES.get(2)).contains("x=urn:u1 "));
        assertTrue(rows(plain, QUERIES.get(0)).contains("x=urn:s9 "));
    }

    @Test
    public void mergedRowsComeInTheStoredOrderOfTheirSubjects() throws Exception {
        // One slice is read in order; several only when lookups follow (here, of each subject's one link).
        for (final int slices : List.of(1, 8)) {
            final String query = slices == 1 ? QUERIES.get(0)
                    : "SELECT ?x WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" ; <urn:link> ?y }";
            final List<String> got = new ArrayList<>();
            for (final String row : rowsInOrder(repository(false, 1_000_000, false, slices), query)) {
                final String x = row.substring(2, row.length() - 1);
                if (got.isEmpty() || !got.get(got.size() - 1).equals(x)) {
                    got.add(x); // a subject in two graphs gives two adjacent rows
                }
            }
            final List<String> byBytes = new ArrayList<>(new java.util.HashSet<>(got));
            byBytes.sort((a, b) -> java.util.Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
            assertEquals(byBytes, got, slices + " slices");
            final List<String> utf16 = new ArrayList<>(got);
            Collections.sort(utf16);
            assertFalse(utf16.equals(byBytes), "the data must tell code point order from UTF-16 order");
        }
    }

    @Test
    public void starsReadFromTheEntityIndexGiveTheAnswersOfNestedLoops() throws Exception {
        // Built over the loaded data (BUILDING, then rebuilt: READY), in 4 shards.
        final StoreRdfConfiguration indexed = newConf();
        indexed.setEntityIndex(true);
        indexed.setEntityIndexShards(4);
        indexed.setRebuildIndexes(true);
        newDao(indexed).destroy();
        assertTrue(IndexVerifier.verify(getStore(), indexed, IndexType.ENTITY).isClean());
        for (final boolean labelled : List.of(true, false)) {
            // The index table exists now: every store opens with the index on, this one without using it.
            final SailRepository plain = indexed(labelled, 0, 1, false, false);
            // By the store where it can (Accumulo's servers, RocksDB's seeks), and on the client in 1 or 3 threads.
            for (final int threads : List.of(0, 1, 3)) {
                final boolean servers = threads == 0;
                final SailRepository fromIndex = indexed(labelled, 1_000_000, Math.max(1, threads), true, servers);
                for (final String query : QUERIES) {
                    assertEquals(rows(plain, query), rows(fromIndex, query), query + (labelled ? " (label A)" : "")
                            + ", " + threads + " threads");
                }
                final String[] reason = new String[1];
                try (StoreTrace.Scope scope = StoreTrace.start(event -> {
                    if (event instanceof TraceEvent.ScanStart scan && scan.reason().contains("merge join")) {
                        reason[0] = scan.reason();
                    }
                })) {
                    rows(fromIndex, QUERIES.get(0));
                }
                assertTrue(reason[0] != null && reason[0].contains("entity index"), String.valueOf(reason[0]));
                // The store intersects on Accumulo (in its servers) and RocksDB (native seeks); HBase not yet.
                assertEquals(servers && !onBackend("hbase"), reason[0].contains("by the store"), reason[0]);
            }
        }
    }

    @Test
    public void countsOfAStarAreTheStoresWhenItIntersects() throws Exception {
        final StoreRdfConfiguration indexed = newConf();
        indexed.setEntityIndex(true);
        indexed.setEntityIndexShards(4);
        indexed.setRebuildIndexes(true);
        newDao(indexed).destroy();
        final String star = "?x <urn:p> <urn:A> ; <urn:q> \"x\"";
        // Counted by the store: rows and subjects differ, the subjects of urn:g1 giving four rows each.
        final List<String> counted = List.of(
                "SELECT (COUNT(*) AS ?c) WHERE { " + star + " }",
                "SELECT (COUNT(?x) AS ?c) WHERE { " + star + " }",
                "SELECT (COUNT(DISTINCT ?x) AS ?c) WHERE { " + star + " }",
                "SELECT (COUNT(DISTINCT *) AS ?c) WHERE { " + star + " }",
                "SELECT (COUNT(*) AS ?c) (COUNT(DISTINCT ?x) AS ?d) WHERE { " + star + " }",
                "SELECT (COUNT(*) AS ?c) WHERE { ?x a <urn:C> ; <urn:p> <urn:A> ; <urn:q> \"x\" }",
                "SELECT (COUNT(*) AS ?c) WHERE { ?x <urn:p> <urn:nothing> ; <urn:q> \"x\" }",
                "SELECT ?y ?c WHERE { ?y <urn:name> \"n0\" { SELECT (COUNT(*) AS ?c) WHERE { " + star + " } } }",
                // HAVING adds a count of its own, under a name RDF4J makes up.
                "SELECT (COUNT(*) AS ?c) WHERE { " + star + " } HAVING (COUNT(DISTINCT ?x) > 5)",
                "SELECT (COUNT(*) AS ?c) WHERE { " + star + " } HAVING (COUNT(*) > 1000)");
        // One pattern, the size of a class: counted by the store too, even on its own.
        final List<String> onePattern = List.of(
                "SELECT (COUNT(*) AS ?c) WHERE { ?x <urn:p> <urn:A> }",
                "SELECT (COUNT(*) AS ?c) (COUNT(DISTINCT ?x) AS ?d) WHERE { ?x <urn:p> <urn:A> }",
                "SELECT (COUNT(?x) AS ?c) WHERE { ?x a <urn:C> }",
                "SELECT (COUNT(DISTINCT *) AS ?c) WHERE { ?x <urn:q> \"x\" }",
                "SELECT (COUNT(*) AS ?c) WHERE { ?x <urn:q> \"x\"^^<urn:dt> }",
                "SELECT (COUNT(*) AS ?c) WHERE { ?x <urn:p> <urn:nothing> }",
                "SELECT ?y ?c WHERE { ?y <urn:name> \"n0\" { SELECT (COUNT(*) AS ?c) WHERE { ?x a <urn:C> } } }");
        // Not counted by the store: a GROUP BY, a filter, another aggregate; for one pattern, a
        // dataset, a graph, a variable object or a constant subject.
        final List<String> grouped = List.of(
                "SELECT ?x (COUNT(*) AS ?c) WHERE { " + star + " } GROUP BY ?x",
                "SELECT (COUNT(*) AS ?c) WHERE { " + star + " FILTER(?x != <urn:s0>) }",
                "SELECT (COUNT(*) AS ?c) (SUM(1) AS ?s) WHERE { " + star + " }",
                "SELECT ?x ?n WHERE { ?x <urn:name> ?n FILTER EXISTS { SELECT (COUNT(*) AS ?c) WHERE { " + star + " } } }",
                "SELECT (COUNT(*) AS ?c) FROM <urn:g1> WHERE { ?x <urn:p> <urn:A> }",
                "SELECT (COUNT(*) AS ?c) WHERE { GRAPH ?g { ?x <urn:p> <urn:A> } }",
                "SELECT (COUNT(*) AS ?c) WHERE { ?x <urn:q> ?o }",
                "SELECT (COUNT(*) AS ?c) WHERE { <urn:s0> <urn:p> <urn:A> }",
                "SELECT ?x (COUNT(*) AS ?c) WHERE { ?x a <urn:C> } GROUP BY ?x");
        final boolean storeCounts = !onBackend("hbase");
        for (final boolean labelled : List.of(true, false)) {
            final SailRepository plain = indexed(labelled, 0, 1, false, false);
            // The shards counted on the query thread, or in groups on 3 threads.
            for (final int threads : List.of(1, 3)) {
                final SailRepository fromIndex = indexed(labelled, 1_000_000, threads, true, true);
                final String which = (labelled ? " (label A)" : "") + ", " + threads + " threads";
                for (final String query : counted) {
                    assertEquals(rows(plain, query), rows(fromIndex, query), query + which);
                    final List<String> reasons = reasons(fromIndex, query);
                    assertEquals(storeCounts, reasons.stream().anyMatch(r -> r.startsWith("count:")), query + which + ": " + reasons);
                    assertEquals(!storeCounts, reasons.stream().anyMatch(r -> r.startsWith("merge join")),
                            query + which + ": " + reasons);
                }
                for (final String query : onePattern) {
                    assertEquals(rows(plain, query), rows(fromIndex, query), query + which);
                    assertEquals(storeCounts, reasons(fromIndex, query).stream().anyMatch(r -> r.startsWith("count:")),
                            query + which);
                }
                for (final String query : grouped) {
                    assertEquals(rows(plain, query), rows(fromIndex, query), query + which);
                    assertFalse(reasons(fromIndex, query).stream().anyMatch(r -> r.startsWith("count:")), query + which);
                }
            }
        }
        // query.limit caps a pattern's rows, and so its count: left to RDF4J.
        final SailRepository limited = indexed(false, 1_000_000, 1, true, true);
        final SailRepository limitedPlain = indexed(false, 0, 1, false, false);
        for (final SailRepository repo : List.of(limited, limitedPlain)) {
            ((RdfCloudTripleStore<?>) repo.getSail()).getConf().setLimit(5L);
        }
        assertEquals(rows(limitedPlain, onePattern.get(2)), rows(limited, onePattern.get(2)));
        assertFalse(reasons(limited, onePattern.get(2)).stream().anyMatch(r -> r.startsWith("count:")));
        // The counts themselves, without the label: 11 subjects, 8 of them also in urn:g1 (every sixth,
        // one of the odd ones too), where each gives 2 × 2 rows: 8 × 4 + 3 = 35 rows.
        assertEquals(List.of("c=\"35\"^^<http://www.w3.org/2001/XMLSchema#integer> d=\"11\"^^<http://www.w3.org/2001/XMLSchema#integer> "),
                rows(indexed(false, 1_000_000, 1, true, true), counted.get(4)));
        // One pattern: 24 subjects have urn:p urn:A, 8 of them in urn:g1 too (every sixth, one odd one too).
        assertEquals(List.of("c=\"32\"^^<http://www.w3.org/2001/XMLSchema#integer> d=\"24\"^^<http://www.w3.org/2001/XMLSchema#integer> "),
                rows(indexed(false, 1_000_000, 1, true, true), onePattern.get(1)));
    }

    /** The reasons of the scans a query makes. */
    private static List<String> reasons(final SailRepository repo, final String query) {
        // Scans of other threads report here too.
        final List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan && scan.reason() != null) {
                reasons.add(scan.reason());
            }
        })) {
            rows(repo, query);
        }
        synchronized (reasons) {
            return List.copyOf(reasons);
        }
    }

    @Test
    public void manyMatchesFromTheEntityIndexAreJoinedInSortedRuns() throws Exception {
        // 3,000 more matches: more than the first sorted run (1,024) holds.
        final SailRepository repo = repository(false, 0);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            for (int i = 0; i < 3000; i++) {
                final IRI s = VF.createIRI(String.format("urn:m%05d", (i * 7919) % 3000));
                conn.add(s, P, A);
                conn.add(s, Q, VF.createLiteral("x"));
                if (i % 10 == 0) {
                    conn.add(s, Q, VF.createLiteral("x"), G1); // two rows for this match
                }
                conn.add(s, NAME, VF.createLiteral("m" + i));
            }
            conn.commit();
        }
        final StoreRdfConfiguration indexed = newConf();
        indexed.setEntityIndex(true);
        indexed.setEntityIndexShards(4);
        indexed.setRebuildIndexes(true);
        newDao(indexed).destroy();
        final String query = "SELECT ?x ?n WHERE { ?x <urn:p> <urn:A> ; <urn:q> \"x\" ; <urn:name> ?n }";
        final List<String> expected = rows(indexed(false, 0, 1, false, false), query);
        assertTrue(expected.size() > 3300, String.valueOf(expected.size()));
        for (final int threads : List.of(0, 1, 3)) {
            final SailRepository fromIndex = indexed(false, 1_000_000, Math.max(1, threads), true, threads == 0);
            assertEquals(expected, rows(fromIndex, query), threads + " threads");
            // The first run's matches come in subject order, and so do the rest's, in one run.
            final List<String> matches = new ArrayList<>();
            for (final String row : rowsInOrder(fromIndex, query)) {
                final String x = row.substring(row.indexOf("x=") + 2, row.indexOf(' ', row.indexOf("x=")));
                if (matches.isEmpty() || !matches.get(matches.size() - 1).equals(x)) {
                    matches.add(x);
                }
            }
            for (final List<String> run : List.of(matches.subList(0, 1024), matches.subList(1024, matches.size()))) {
                // In the stored order: UTF-8 bytes, which isn't String's for the odd subjects.
                final List<String> sorted = new ArrayList<>(run);
                sorted.sort((a, b) -> java.util.Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),
                        b.getBytes(StandardCharsets.UTF_8)));
                assertEquals(sorted, run, threads + " threads");
            }
            try (RepositoryConnection conn = fromIndex.getConnection();
                    TupleQueryResult result = conn.prepareTupleQuery(query + " LIMIT 5").evaluate()) {
                int n = 0;
                while (result.hasNext()) {
                    result.next();
                    n++;
                }
                assertEquals(5, n);
            }
        }
    }

    @Test
    public void slicedMergesGiveTheRowsOfOneSliceInTheSameOrderWhenLookupsFollow() throws Exception {
        // Lookups follow the merge in these: of the names (and ages), and of the links.
        final Set<String> ordered = Set.of(QUERIES.get(3), QUERIES.get(7));
        for (final boolean labelled : List.of(true, false)) {
            final SailRepository plain = repository(labelled, 0, false, 1);
            final SailRepository whole = repository(labelled, 1_000_000, false, 1);
            final SailRepository sliced = repository(labelled, 1_000_000, false, 8);
            for (final String query : QUERIES) {
                assertEquals(rows(plain, query), rows(sliced, query), query + (labelled ? " (label A)" : ""));
                if (ordered.contains(query)) {
                    assertEquals(rowsInOrder(whole, query), rowsInOrder(sliced, query), "the same order: " + query);
                }
            }
            // Five boundaries, six slices, each read for both patterns.
            assertEquals(12, read(sliced, QUERIES.get(0)).mergedLists());
            assertEquals(2, read(whole, QUERIES.get(0)).mergedLists());
            // A LIMIT closes the slices early.
            final List<String> limited = rows(sliced, QUERIES.get(0) + " LIMIT 3");
            assertEquals(3, limited.size());
            assertTrue(rows(plain, QUERIES.get(0)).containsAll(limited), limited.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Read(int mergedLists, long prefetches, long entityPrefetches, long lookups, String plan) {
    }

    private static Read read(final SailRepository repo, final String query) {
        final int[] merged = new int[1];
        final long[] prefetches = new long[2];
        final long[] lookups = new long[1];
        final StringBuilder plan = new StringBuilder();
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan) {
                if (scan.reason().contains("merge join")) {
                    merged[0]++;
                } else if (scan.reason().contains("entity prefetch")) {
                    prefetches[1]++;
                } else if (scan.reason().contains("batched")) {
                    prefetches[0]++;
                } else {
                    lookups[0]++;
                }
            } else if (event instanceof TraceEvent.Plan p && p.stage().equals("optimized")) {
                plan.append(p.tree());
            }
        })) {
            rows(repo, query);
        }
        return new Read(merged[0], prefetches[0] + prefetches[1], prefetches[1], lookups[0], plan.toString());
    }

    private SailRepository repository(final boolean labelled, final double mergeRatio) {
        return repository(labelled, mergeRatio, false);
    }

    private SailRepository repository(final boolean labelled, final double mergeRatio, final boolean inference) {
        return repository(labelled, mergeRatio, inference, null);
    }

    /** @param slices how many slices merges may use; null for one, the whole lists */
    private SailRepository repository(final boolean labelled, final double mergeRatio, final boolean inference,
            final Integer slices) {
        final StoreRdfConfiguration conf = newConf();
        conf.setJoinMergeRatio(mergeRatio);
        conf.setJoinMergeSlices(slices == null ? 1 : slices);
        // The lists of po, whose scans these tests count, even when -Dtripatha.test.indexes keeps the entity index.
        conf.setUseIndex(false);
        if (labelled) {
            conf.setAuths("A");
        }
        final SailRepository repo = new SailRepository(newStore(conf, inference));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    /** A store with the entity index on, used by queries or not, intersected in the servers or not. */
    private SailRepository indexed(final boolean labelled, final double mergeRatio, final int threads, final boolean useIndex,
            final boolean servers) {
        final StoreRdfConfiguration conf = newConf();
        conf.setEntityIndex(true);
        conf.setEntityIndexServers(servers);
        conf.setJoinMergeRatio(mergeRatio);
        conf.setJoinMergeSlices(threads);
        conf.setUseIndex(useIndex);
        if (labelled) {
            conf.setAuths("A");
        }
        final SailRepository repo = new SailRepository(newStore(conf, false));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    /**
     * Splits spo at five subjects, among them the odd ones, so that merges can have slices: real
     * splits on Accumulo and HBase, hints on RocksDB. Tests ask for slices with
     * {@code repository(…, slices)}.
     */
    private void split() {
        final java.util.SortedSet<byte[]> rows = new java.util.TreeSet<>(java.util.Arrays::compareUnsigned);
        for (final String subject : List.of("urn:s15", "urn:s3", "urn:s31", ODD.get(1), ODD.get(2))) {
            rows.add(subject.getBytes(StandardCharsets.UTF_8));
        }
        getStore().tables().addSplits(newConf().getTableLayoutStrategy().getSpo(), rows);
    }

    /** The rows, as text, in the order they came. */
    private static List<String> rowsInOrder(final SailRepository repo, final String query) {
        final List<String> rows = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection(); TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
            while (result.hasNext()) {
                final BindingSet bs = result.next();
                final StringBuilder b = new StringBuilder();
                for (final String name : new TreeSet<>(bs.getBindingNames())) {
                    b.append(name).append('=').append(bs.getValue(name)).append(' ');
                }
                rows.add(b.toString());
            }
        }
        return rows;
    }

    /** The rows, as text, sorted: a multiset. */
    private static List<String> rows(final SailRepository repo, final String query) {
        final List<String> rows = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection(); TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
            while (result.hasNext()) {
                final BindingSet bs = result.next();
                final StringBuilder b = new StringBuilder();
                for (final String name : new TreeSet<>(bs.getBindingNames())) {
                    b.append(name).append('=').append(bs.getValue(name)).append(' ');
                }
                rows.add(b.toString());
            }
        }
        Collections.sort(rows);
        return rows;
    }
}
