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
import java.util.List;
import java.util.TreeSet;

import org.eclipse.rdf4j.query.BindingSet;
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
import org.tripatha.rdftriplestore.RdfCloudTripleStore;
import org.tripatha.store.DatatypeMarkers;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

/**
 * A comparison filter on a pattern's object narrows the pattern's scan to value ranges (O14), and
 * answers stay those of the full scan, duplicates included: every query here is also run with
 * {@code query.valueranges} off, with and without a label, and with and without inference. The
 * ranges of a pattern in a join are counted to estimate it (O25), up to a cap: the answers are
 * checked with no count, with a cap every range reaches, and with the default.
 */
public class ValueRangeIT extends RyaSailITBase {
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String DATA = """
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            INSERT DATA {
              <urn:a1> <urn:n> 61 . <urn:a2> <urn:n> "60"^^xsd:int . <urn:a3> <urn:n> "59"^^xsd:long .
              <urn:a4> <urn:n> "61.5"^^xsd:double . <urn:a5> <urn:n> "NaN"^^xsd:double . <urn:a6> <urn:n> "INF"^^xsd:double .
              <urn:a7> <urn:n> "-INF"^^xsd:double . <urn:a8> <urn:n> "-0.0"^^xsd:double . <urn:a9> <urn:n> "0.0"^^xsd:float .
              <urn:a10> <urn:n> "60.000004"^^xsd:float . <urn:a11> <urn:n> "-5"^^xsd:byte . <urn:a12> <urn:n> "100"^^xsd:byte .
              <urn:a13> <urn:n> "70000"^^xsd:short . <urn:a14> <urn:n> 100000000000000000000000000000 .
              <urn:a15> <urn:n> -100000000000000000000000000000 . <urn:a16> <urn:n> "9223372036854775807"^^xsd:long .
              <urn:a17> <urn:n> "-2147483648"^^xsd:int . <urn:a18> <urn:n> "1152921504606847076"^^xsd:long .
              <urn:a1> <urn:n> 61 .
              <urn:a1> <urn:name> "one" . <urn:a4> <urn:name> "four" . <urn:a12> <urn:name> "twelve" .
              GRAPH <urn:g1> { <urn:b1> <urn:n> 75 . <urn:b2> <urn:n> "10"^^xsd:int . <urn:a1> <urn:n> 61 }
              GRAPH <urn:g2> { <urn:b3> <urn:n> "80.5"^^xsd:double }

              <urn:d1> <urn:t> "2020-01-01T00:00:00Z"^^xsd:dateTime . <urn:d2> <urn:t> "2020-01-01T10:00:00+05:00"^^xsd:dateTime .
              <urn:d3> <urn:t> "2020-01-01T12:00:00"^^xsd:dateTime . <urn:d4> <urn:t> "2019-12-31T23:59:59.999Z"^^xsd:dateTime .
              <urn:d5> <urn:t> "2020-01-02T00:00:00Z"^^xsd:dateTime . <urn:d6> <urn:t> "1500-06-01T00:00:00Z"^^xsd:dateTime .

              <urn:m1> <urn:mix> 61 . <urn:m2> <urn:mix> 61.5 . <urn:m3> <urn:mix> "59"^^xsd:int .
              <urn:s1> <urn:str> 61 . <urn:s2> <urn:str> "61" . <urn:s3> <urn:str> <urn:sixty-one> .

              <urn:n2> rdfs:subPropertyOf <urn:n> . <urn:c1> <urn:n2> 70 . <urn:c2> <urn:n2> "5"^^xsd:int .
            }""";

    /** {@code {P}} is the predicate and {@code {C}} the condition. */
    private static final List<String> QUERIES = List.of(
            "SELECT ?s ?o WHERE { ?s <{P}> ?o FILTER({C}) }",
            "SELECT ?s ?o WHERE { GRAPH <urn:g1> { ?s <{P}> ?o } FILTER({C}) }",
            "SELECT ?g ?s ?o WHERE { GRAPH ?g { ?s <{P}> ?o FILTER({C}) } }",
            "SELECT ?s ?o FROM <urn:g1> FROM <urn:g2> WHERE { ?s <{P}> ?o FILTER({C}) }",
            "SELECT ?s ?o ?nm WHERE { ?s <{P}> ?o . ?s <urn:name> ?nm FILTER({C}) }",
            "SELECT ?s ?o ?nm WHERE { ?s <{P}> ?o OPTIONAL { ?s <urn:name> ?nm } FILTER({C}) }",
            "SELECT ?s ?o WHERE { { ?s <{P}> ?o } UNION { ?s <urn:mix> ?o } FILTER({C}) }",
            "SELECT ?o WHERE { <urn:a1> <{P}> ?o FILTER({C}) }",
            "SELECT (COUNT(*) AS ?n) WHERE { ?s <{P}> ?o FILTER({C}) }",
            "SELECT ?s ?o WHERE { ?s a <urn:K> ; <urn:grp> <urn:G> ; <{P}> ?o FILTER({C}) }");

    private static final List<String> PREDICATES = List.of("urn:n", "urn:t", "urn:mix", "urn:str", "urn:only", "urn:big",
            "urn:score");
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    /** The star, {@code %s} being the condition on the score. */
    private static final String STAR = "SELECT ?x ?v WHERE { ?x a <urn:K> ; <urn:grp> <urn:G> ; <urn:score> ?v FILTER(%s) }";

    private static final List<String> CONDITIONS = List.of(
            "?o > 60", "?o >= 60", "?o < 60", "60 < ?o", "?o <= 60.0", "?o > 59.5", "?o > 6.0E1", "?o >= -0.0E0",
            "?o < \"NaN\"^^<" + XSD + "double>", "?o > \"INF\"^^<" + XSD + "double>", "?o > 10 && ?o < 100",
            "?o > 100 && ?o < 10", "?o = 61", "?o = 61.0", "?o > \"abc\"^^<" + XSD + "integer>",
            "?o >= 9223372036854775807", "?o < -129", "?o > -1", "?o <= \"1.152921504606847E18\"^^<" + XSD + "double>",
            "?o > \"6.0E1\"^^<" + XSD + "float>",
            "?o > \"2020-01-01T00:00:00Z\"^^<" + XSD + "dateTime>",
            "?o >= \"2020-01-01T00:00:00\"^^<" + XSD + "dateTime> && ?o < \"2020-01-02T00:00:00\"^^<" + XSD + "dateTime>",
            "?o < \"2020-01-01\"^^<" + XSD + "date>", "?o != 61");

    private final List<SailRepository> repositories = new ArrayList<>();

    @BeforeEach
    public void load() {
        grantAuthorizations("A");
        final SailRepository repo = repository(true, true, false);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(DATA).execute();
        }
        final StoreRdfConfiguration conf = newConf();
        final RyaStoreDAO dao = newDao(conf);
        try {
            // A type only a reader with label A may know of, and values in it.
            final List<RyaStatement> labelled = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                labelled.add(new RyaStatement(new RyaIRI("urn:l" + i), new RyaIRI("urn:only"),
                        new RyaType(org.eclipse.rdf4j.model.vocabulary.XSD.LONG, Integer.toString(58 + i)), null, null,
                        "A".getBytes(StandardCharsets.UTF_8), null, 1L));
                labelled.add(new RyaStatement(new RyaIRI("urn:u" + i), new RyaIRI("urn:only"),
                        new RyaType(org.eclipse.rdf4j.model.vocabulary.XSD.INT, Integer.toString(58 + i))));
            }
            labelled.add(new RyaStatement(new RyaIRI("urn:a1"), new RyaIRI("urn:n"), new RyaType(
                    org.eclipse.rdf4j.model.vocabulary.XSD.DOUBLE, "62.5"), null, null, "A".getBytes(StandardCharsets.UTF_8), null, 1L));
            for (int i = 1; i <= 200; i++) {
                labelled.add(new RyaStatement(new RyaIRI("urn:big" + i), new RyaIRI("urn:big"),
                        new RyaType(org.eclipse.rdf4j.model.vocabulary.XSD.INT, Integer.toString(i))));
            }
            // A star: 60 members of a class, 8 of them in a group, each with a score; and 20 high
            // scores only label A can see.
            for (int i = 1; i <= 60; i++) {
                final RyaIRI k = new RyaIRI("urn:k" + i);
                labelled.add(new RyaStatement(k, new RyaIRI(RDF_TYPE), new RyaIRI("urn:K")));
                if (i <= 8) {
                    labelled.add(new RyaStatement(k, new RyaIRI("urn:grp"), new RyaIRI("urn:G")));
                }
                labelled.add(new RyaStatement(k, new RyaIRI("urn:score"),
                        new RyaType(org.eclipse.rdf4j.model.vocabulary.XSD.INT, Integer.toString(i))));
            }
            for (int i = 1; i <= 20; i++) {
                labelled.add(new RyaStatement(new RyaIRI("urn:h" + i), new RyaIRI("urn:score"),
                        new RyaType(org.eclipse.rdf4j.model.vocabulary.XSD.INT, Integer.toString(100 + i)), null, null,
                        "A".getBytes(StandardCharsets.UTF_8), null, 1L));
            }
            dao.add(labelled.iterator());
        } finally {
            dao.destroy();
        }
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    @Test
    public void narrowedScansGiveTheAnswersOfFullScans() {
        // No count (the fixed estimate), a cap every range reaches, and the default cap.
        for (final Integer cap : java.util.Arrays.asList(0, 3, null)) {
            for (final boolean inference : cap == null ? List.of(false, true) : List.of(false)) {
                for (final boolean labelled : List.of(true, false)) {
                    final SailRepository ranges = repository(labelled, true, inference, cap);
                    final SailRepository full = repository(labelled, false, inference, cap);
                    for (final String query : QUERIES) {
                        for (final String predicate : PREDICATES) {
                            for (final String condition : CONDITIONS) {
                                final String q = query.replace("{P}", predicate).replace("{C}", condition);
                                assertEquals(rows(full, q), rows(ranges, q), q + (labelled ? " (label A)" : "")
                                        + (inference ? " (inference)" : "") + " (cap " + cap + ")");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aCountedRangeDrivesItsStarOnlyWhenItIsShort() {
        final String selective = String.format(STAR, "?v > 58");
        final String wide = String.format(STAR, "?v > 0");
        final SailRepository counted = repository(false, true, false, 10);
        final Read narrow = read(counted, selective);
        assertTrue(narrow.counted, "a pattern in a join is counted");
        assertEquals("ValueRangePattern", driver(narrow.plan), narrow.plan);
        assertTrue(narrow.plan.contains("cells]"), narrow.plan);
        final Read broad = read(counted, wide);
        // Counted up to the group's 8 members, the shortest other pattern, below the cap of 10.
        assertTrue(broad.plan.contains("at least 8 cells"), broad.plan);
        assertFalse(driver(broad.plan).equals("ValueRangePattern"), "60 scores pass: the group drives\n" + broad.plan);
        // A tenth of the 60 scores, below the group's 8: the fixed estimate drives with the range.
        assertEquals("ValueRangePattern", driver(read(repository(false, true, false, 0), wide).plan));
        assertEquals(rows(repository(false, false, false, 10), wide), rows(counted, wide));
    }

    @Test
    public void aCountSeesOnlyWhatTheReaderMaySee() {
        final String selective = String.format(STAR, "?v > 58");
        assertEquals("ValueRangePattern", driver(read(repository(false, true, false, 10), selective).plan),
                "without label A, 4 scores are counted");
        final Read withA = read(repository(true, true, false, 10), selective);
        assertTrue(withA.plan.contains("at least 8 cells"), "with label A, the 20 high scores count too\n" + withA.plan);
    }

    @Test
    public void aRangeIsCountedUpToTheShortestPatternJoinedWithIt() {
        // The range of ?v > 50 holds 12 scores (it is widened, from 49), against the class's 60
        // members: more than a cap of 5, fewer than the class.
        final String star = "SELECT ?x ?v WHERE { ?x a <urn:K> ; <urn:score> ?v FILTER(?v > 50) }";
        final Read byDefault = read(repository(false, true, false), star);
        assertTrue(byDefault.plan.contains("; 12 cells]"), "counted up to the class's 60, so exactly\n" + byDefault.plan);
        assertEquals("ValueRangePattern", driver(byDefault.plan), byDefault.plan);
        final Read capped = read(repository(false, true, false, 5), star);
        assertTrue(capped.plan.contains("at least 5 cells"), "the setting is a ceiling\n" + capped.plan);
        assertFalse(driver(capped.plan).equals("ValueRangePattern"), "unknown beyond 5: the class drives\n" + capped.plan);
        // With the group (8 members) in the star, the count stops at 8: the range can't drive past it.
        final Read grouped = read(repository(false, true, false), String.format(STAR, "?v > 0"));
        assertTrue(grouped.plan.contains("at least 8 cells"), grouped.plan);
        assertEquals(rows(repository(false, false, false), star), rows(repository(false, true, false), star));
    }

    @Test
    public void aPatternOnItsOwnIsNotCounted() {
        final Read alone = read(repository(true, true, false, 10), "SELECT ?s WHERE { ?s <urn:big> ?o FILTER(?o > 190) }");
        assertTrue(alone.narrowed);
        assertFalse(alone.counted, alone.plan);
    }

    @Test
    public void onlyTheValueRangesAreRead() {
        final String query = "SELECT ?s ?o WHERE { ?s <urn:big> ?o FILTER(?o > 190) }";
        final Read narrowed = read(repository(true, true, false), query);
        final Read full = read(repository(true, false, false), query);
        assertTrue(narrowed.narrowed, "the value ranges were used");
        assertFalse(full.narrowed);
        assertTrue(narrowed.cells < 30 && full.cells > 200, narrowed.cells + " cells read, against " + full.cells);
        assertTrue(narrowed.plan.contains("ValueRangePattern"), narrowed.plan);
    }

    @Test
    public void aPredicateWithATypeThatDoesNotSortLikeItsValuesIsReadWhole() {
        for (final String predicate : List.of("urn:mix", "urn:str")) {
            final Read read = read(repository(true, true, false), "SELECT ?s WHERE { ?s <" + predicate + "> ?o FILTER(?o > 60) }");
            assertFalse(read.narrowed, predicate + ": " + read.plan);
        }
        final Read dates = read(repository(true, true, false),
                "SELECT ?s WHERE { ?s <urn:t> ?o FILTER(?o > \"2020-01-01T00:00:00Z\"^^<" + XSD + "dateTime>) }");
        assertTrue(dates.narrowed, dates.plan);
    }

    @Test
    public void aStoreCreatedBeforeTheMarkersIsNotNarrowed() throws Exception {
        final StoreRdfConfiguration conf = newConf();
        try (CellWriter writer = getStore().newWriter(WriterOptions.DEFAULT)) {
            writer.table(conf.getTableLayoutStrategy().getNs()).write(new RowMutation("tripatha".getBytes(StandardCharsets.UTF_8))
                    .delete(DatatypeMarkers.FLAG_CF.getBytes(StandardCharsets.UTF_8), new byte[0], new byte[0]));
            writer.flush();
        }
        final String query = "SELECT ?s ?o WHERE { ?s <urn:n> ?o FILTER(?o > 60) }";
        final SailRepository older = repository(true, true, false);
        final Read read = read(older, query);
        assertFalse(read.narrowed, read.plan);
        assertEquals(rows(repository(true, false, false), query), rows(older, query));
    }

    // ------------------------------------------------------------------ helpers

    private record Read(boolean narrowed, boolean counted, long cells, String plan) {
    }

    /** The first pattern of the plan, which drives its joins. */
    private static String driver(final String plan) {
        for (final String line : plan.split("\n")) {
            for (final String node : List.of("ValueRangePattern", "SubjectMergeJoin", "StatementPattern")) {
                if (line.trim().startsWith(node)) {
                    return node;
                }
            }
        }
        return "none";
    }

    private static Read read(final SailRepository repo, final String query) {
        final boolean[] counted = new boolean[1];
        final boolean[] narrowed = new boolean[1];
        final long[] cells = new long[1];
        final StringBuilder plan = new StringBuilder();
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan && scan.reason().contains("value ranges: only")) {
                narrowed[0] = true;
            } else if (event instanceof TraceEvent.ScanStart scan && scan.reason().contains("value ranges: count")) {
                counted[0] = true;
            } else if (event instanceof TraceEvent.ScanEnd end) {
                cells[0] += Math.max(0, end.entries());
            } else if (event instanceof TraceEvent.Plan p && p.stage().equals("optimized")) {
                plan.append(p.tree());
            }
        })) {
            rows(repo, query);
        }
        return new Read(narrowed[0], counted[0], cells[0], plan.toString());
    }

    private SailRepository repository(final boolean labelled, final boolean valueRanges, final boolean inference) {
        return repository(labelled, valueRanges, inference, null);
    }

    /** @param cap the most cells counted to estimate a range; null for the default */
    private SailRepository repository(final boolean labelled, final boolean valueRanges, final boolean inference,
            final Integer cap) {
        final StoreRdfConfiguration conf = newConf();
        conf.setValueRanges(valueRanges);
        if (cap != null) {
            conf.setValueRangeCount(cap);
        }
        if (labelled) {
            conf.setAuths("A");
        }
        final SailRepository repo = new SailRepository(newStore(conf, inference));
        repo.init();
        if (inference) {
            try {
                ((RdfCloudTripleStore<?>) repo.getSail()).getInferenceEngine().refreshGraph();
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        }
        repositories.add(repo);
        return repo;
    }

    /** The rows as text, sorted, each as often as it was returned. */
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
        rows.sort(null);
        return rows;
    }
}
