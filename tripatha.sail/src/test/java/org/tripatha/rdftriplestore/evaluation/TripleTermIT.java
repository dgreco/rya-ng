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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.tripatha.RyaSailITBase;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RDF 1.2 triple terms in queries and updates: {@code <<( s p o )>>} patterns, reified triples
 * {@code << s p o >>}, annotations {@code {| |}}, nesting, the triple functions, and templates.
 */
public class TripleTermIT extends RyaSailITBase {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String PREFIXES = "PREFIX : <urn:> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ";

    private static final String DATA = PREFIXES + """
            INSERT DATA {
              :alice :knows :bob {| :since 2020 ; :source :census |} .
              :alice :knows :carol .
              :r1 rdf:reifies <<( :alice :knows :carol )>> ; :since 2021 .
              :bob :knows :carol .
              :r2 rdf:reifies <<( :alice :likes "tea"@en )>> ; :certainty 0.5 .
              :r3 rdf:reifies <<( :dave :says <<( :alice :knows :bob )>> )>> .
              :r5 rdf:reifies <<( :erin :says <<( :frank :knows :grace )>> )>> .
              GRAPH :g { :r4 rdf:reifies <<( :alice :knows :bob )>> . :r4 :since 1999 }
            }""";

    private final List<SailRepository> repositories = new ArrayList<>();
    private SailRepository repo;

    @BeforeEach
    public void load() {
        repo = repository(newConf().getJoinBatchSize(), true, false);
        update(DATA);
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    @Test
    public void aConstantTermIsAnExactLookup() {
        final Set<String> found = column("SELECT ?r WHERE { ?r rdf:reifies <<( :alice :knows :bob )>> }", "r");
        assertEquals(2, found.size(), found.toString());
        assertTrue(found.contains("urn:r4"), found.toString());
        assertEquals(Set.of(), column("SELECT ?r WHERE { ?r rdf:reifies <<( :alice :knows :nobody )>> }", "r"));
        assertEquals(Set.of("urn:r4"), column("SELECT ?r WHERE { GRAPH :g { ?r rdf:reifies <<( :alice :knows :bob )>> } }", "r"));
        // A constant term is a value whether or not it is stored.
        final List<Map<String, Value>> bound = rows("SELECT ?t WHERE { BIND(<<( :x :y :z )>> AS ?t) }");
        assertEquals(List.of(Map.of("t", term("urn:x", "urn:y", VF.createIRI("urn:z")))), bound);
    }

    @Test
    public void partlyBoundAndUnboundTerms() {
        assertEquals(4, rows("SELECT * WHERE { ?r rdf:reifies <<( :alice ?p ?o )>> }").size());
        assertEquals(Set.of("urn:r2"), column("SELECT ?r WHERE { ?r rdf:reifies <<( ?s :likes ?o )>> }", "r"));
        assertEquals(Set.of("urn:r2"), column("SELECT ?r WHERE { ?r rdf:reifies <<( ?s ?p \"tea\"@en )>> }", "r"));
        assertEquals(6, rows("SELECT * WHERE { ?r rdf:reifies <<( ?s ?p ?o )>> }").size());
        assertEquals(Set.of("urn:r1"), column("SELECT ?r WHERE { ?r rdf:reifies <<( ?s ?p ?o )>> ; :since 2021 }", "r"));
        // The same variable twice must match itself; a literal can't be a subject.
        assertEquals(0, rows("SELECT * WHERE { ?r rdf:reifies <<( ?x :knows ?x )>> }").size());
        assertEquals(0, rows("SELECT * WHERE { VALUES ?s { \"alice\" } ?r rdf:reifies <<( ?s ?p ?o )>> }").size());
        assertEquals(3, rows("SELECT * WHERE { VALUES ?s { :alice :zed } ?r rdf:reifies <<( ?s :knows ?o )>> }").size());
    }

    @Test
    public void reifiedTriplesAndAnnotations() {
        final Map<String, String> since = new HashMap<>();
        for (final Map<String, Value> row : rows("SELECT ?o ?d WHERE { << :alice :knows ?o >> :since ?d }")) {
            since.merge(row.get("o").stringValue(), row.get("d").stringValue(), (a, b) -> new TreeSet<>(List.of(a, b)).toString());
        }
        assertEquals(Map.of("urn:bob", "[1999, 2020]", "urn:carol", "2021"), since);

        assertEquals(3, rows("SELECT ?o ?d WHERE { :alice :knows ?o {| :since ?d |} }").size());
        assertEquals(Set.of("urn:census"), column("SELECT ?src WHERE { :alice :knows :bob {| :source ?src |} }", "src"));
        // :bob :knows :carol is asserted, but nothing reifies it.
        assertEquals(0, rows("SELECT * WHERE { :bob :knows ?o {| ?p ?v |} }").size());
        assertEquals(Set.of("urn:carol"), column("SELECT ?o WHERE { << :alice :knows ?o ~ :r1 >> :since ?d }", "o"));
    }

    @Test
    public void nestedTerms() {
        assertEquals(Set.of("urn:bob"),
                column("SELECT ?x WHERE { ?r rdf:reifies <<( :dave :says <<( :alice :knows ?x )>> )>> }", "x"));
        assertEquals(Set.of("urn:r3"),
                column("SELECT ?r WHERE { ?r rdf:reifies <<( :dave :says <<( :alice :knows :bob )>> )>> }", "r"));
        assertEquals(Set.of("urn:r3", "urn:r5"),
                column("SELECT ?r WHERE { ?r rdf:reifies <<( ?who :says <<( ?s ?p ?o )>> )>> }", "r"));
        // Used only inside another term, so it is found by looking inside the stored terms:
        // <<( :frank :knows :grace )>> is the object of no statement.
        final List<Map<String, Value>> inner = rows("SELECT ?s ?o WHERE { BIND(<<( ?s :knows ?o )>> AS ?t) FILTER(?s = :frank) }");
        assertEquals(List.of(Map.of("s", VF.createIRI("urn:frank"), "o", VF.createIRI("urn:grace"))), inner);
        assertEquals(3, rows("SELECT DISTINCT ?s ?o WHERE { BIND(<<( ?s :knows ?o )>> AS ?t) }").size());
    }

    @Test
    public void functions() {
        assertEquals(6, rows("SELECT ?t WHERE { ?r rdf:reifies ?t FILTER(isTRIPLE(?t)) }").size());
        assertEquals(Set.of("urn:alice", "urn:dave", "urn:erin"),
                column("SELECT ?s WHERE { ?r rdf:reifies ?t BIND(SUBJECT(?t) AS ?s) }", "s"));
        assertEquals(Set.of("urn:r1"),
                column("SELECT ?r WHERE { ?r rdf:reifies ?t FILTER(?t = TRIPLE(:alice, :knows, :carol)) }", "r"));
        assertEquals(Set.of("urn:r3", "urn:r5"),
                column("SELECT ?r WHERE { ?r rdf:reifies ?t FILTER(isTRIPLE(OBJECT(?t))) }", "r"));
    }

    @Test
    public void updatesAndConstruct() {
        update(PREFIXES + "INSERT { ?r :checked true } WHERE { ?r rdf:reifies <<( :alice :knows ?o )>> }");
        assertEquals(3, rows("SELECT ?r WHERE { ?r :checked true }").size());

        update(PREFIXES + "INSERT { ?s :stated <<( ?s :knows ?o )>> } WHERE { ?s :knows ?o }");
        assertEquals(Set.of("urn:alice", "urn:bob"), column("SELECT ?s WHERE { ?s :stated <<( ?s :knows :carol )>> }", "s"));
        assertEquals(3, rows("SELECT * WHERE { ?s :stated ?t }").size());

        update(PREFIXES + "DELETE WHERE { ?r rdf:reifies <<( :alice :likes ?o )>> }");
        assertEquals(Set.of(), column("SELECT ?r WHERE { ?r rdf:reifies <<( ?s :likes ?o )>> }", "r"));
        assertEquals(Set.of("urn:r2"), column("SELECT ?r WHERE { ?r :certainty ?c }", "r"));

        update(PREFIXES + "DELETE { ?r rdf:reifies ?t } INSERT { ?r :was ?t } WHERE { ?r rdf:reifies ?t . ?r :since 2021 }");
        assertEquals(Set.of("urn:r1"), column("SELECT ?r WHERE { ?r :was <<( :alice :knows :carol )>> }", "r"));
        assertEquals(4, rows("SELECT * WHERE { ?r rdf:reifies ?t }").size());

        update(PREFIXES + "DELETE DATA { GRAPH :g { :r4 rdf:reifies <<( :alice :knows :bob )>> } }");
        assertEquals(1, rows("SELECT ?r WHERE { ?r rdf:reifies <<( :alice :knows :bob )>> }").size());

        final List<Statement> built = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection(); GraphQueryResult result = conn.prepareGraphQuery(
                PREFIXES + "CONSTRUCT { ?r :about ?t . ?r :inner <<( ?s :p ?o )>> } WHERE { ?r rdf:reifies ?t . ?r rdf:reifies <<( ?s ?p ?o )>> }")
                .evaluate()) {
            result.forEach(built::add);
        }
        assertEquals(6, built.size(), built.toString());
        assertTrue(built.stream().allMatch(st -> st.getObject() instanceof TripleTerm), built.toString());
    }

    @Test
    public void everyPlanGivesTheSameResults() throws Exception {
        final List<String> queries = List.of(
                "SELECT * WHERE { ?r rdf:reifies <<( :alice :knows :bob )>> }",
                "SELECT * WHERE { ?r rdf:reifies <<( :alice ?p ?o )>> }",
                "SELECT * WHERE { ?r rdf:reifies <<( ?s ?p ?o )>> . ?s :knows ?x }",
                "SELECT * WHERE { ?s :knows ?x . ?r rdf:reifies <<( ?s :knows ?x )>> OPTIONAL { ?r :since ?d } }",
                "SELECT * WHERE { << :alice :knows ?o >> :since ?d }",
                "SELECT * WHERE { :alice :knows ?o {| :since ?d |} }",
                "SELECT * WHERE { ?r rdf:reifies <<( ?who :says <<( ?s ?p ?o )>> )>> . ?s :knows ?o }",
                "SELECT * WHERE { ?a :knows ?b OPTIONAL { ?r rdf:reifies <<( ?a :knows ?b )>> . ?r :since ?d } }",
                "SELECT * WHERE { { ?r rdf:reifies <<( :alice ?p ?o )>> } UNION { ?r rdf:reifies <<( :dave ?p ?o )>> } }",
                "SELECT * WHERE { GRAPH ?g { ?r rdf:reifies <<( ?s ?p ?o )>> } }",
                "SELECT (COUNT(*) AS ?n) WHERE { ?r rdf:reifies <<( ?s ?p ?o )>> }");
        final SailRepository perRow = repository(1, true, false);
        final List<SailRepository> others = List.of(repository(1000, true, false), repository(2, true, false),
                repository(2, false, false), repository(1000, true, true));
        for (final String query : queries) {
            final Map<Map<String, Value>, Integer> expected = counted(perRow, query);
            assertFalse(expected.isEmpty(), "no results for " + query);
            for (final SailRepository other : others) {
                assertEquals(expected, counted(other, query), query);
            }
        }
    }

    @Test
    public void tracedQueriesRunTheSameAndReadOnlyWhatTheyNeed() {
        // What each query reads beyond exact lookups: nothing, the range of terms with the
        // pattern's constant subject, or every stored triple term.
        final Map<String, String> reads = new java.util.LinkedHashMap<>();
        reads.put("SELECT * WHERE { ?r rdf:reifies <<( :alice :knows :bob )>> }", "");
        reads.put("SELECT * WHERE { ?r rdf:reifies <<( ?s :knows ?o )>> . ?r :since ?d }", "");
        // Join ordering starts from the few :since statements, so the reifiers are already bound.
        reads.put("SELECT * WHERE { ?r rdf:reifies <<( :alice ?p ?o )>> . ?r :since ?d }", "");
        reads.put("SELECT * WHERE { ?r rdf:reifies <<( :alice ?p ?o )>> }", "range");
        reads.put("SELECT * WHERE { ?r ?how <<( :alice :likes ?o )>> }", "range");
        reads.put("SELECT * WHERE { GRAPH :g { ?r rdf:reifies <<( :alice ?p ?o )>> } }", "range");
        reads.put("SELECT * WHERE { ?r rdf:reifies <<( :dave :says <<( :alice :knows ?x )>> )>> }", "range");
        reads.put("SELECT * WHERE { :alice :knows ?o {| :since ?d |} }", "");
        reads.put("SELECT * WHERE { << :alice :knows ?o >> ?about ?v }", "range");
        reads.put("SELECT * WHERE { BIND(<<( ?s :knows ?o )>> AS ?t) }", "all");
        reads.forEach((query, expected) -> {
            final Map<Map<String, Value>, Integer> untraced = counted(repo, query);
            final List<TraceEvent> events = new ArrayList<>();
            final Map<Map<String, Value>, Integer> traced;
            try (StoreTrace.Scope scope = StoreTrace.start(events::add)) {
                traced = counted(repo, query);
            }
            assertEquals(untraced, traced, query);
            assertFalse(traced.isEmpty(), query);
            final List<String> reasons = events.stream().filter(e -> e instanceof TraceEvent.ScanStart)
                    .map(e -> ((TraceEvent.ScanStart) e).reason()).toList();
            assertEquals(expected.equals("range"), reasons.stream().anyMatch(r -> r.contains("triple terms: only statements about")),
                    query + " " + reasons);
            assertEquals(expected.equals("all"), reasons.stream().anyMatch(r -> r.contains("triple terms: the pattern's term is not bound")),
                    query + " " + reasons);
        });
    }

    /** The narrowed range must find exactly what reading every statement and filtering would. */
    @Test
    public void theRangeOfTermsAboutASubjectIsExact() {
        // Subjects that sort around :alice, a string that looks like her terms, and other objects.
        update(PREFIXES + """
                INSERT DATA {
                  :x1 rdf:reifies <<( :alic :knows :bob )>> , <<( :alice2 :knows :bob )>> , <<( :alicE :knows :bob )>> , :alice , "alice" .
                  :x2 :other <<( :alice :knows :zed )>> .
                  GRAPH :g2 { :x3 rdf:reifies <<( :alice :knows <<( :alice :knows :bob )>> )>> }
                }""");
        assertEquals(Set.of("urn:r1", "urn:r2", "urn:r4", "urn:x3"), withoutBlankNodes(
                column("SELECT ?r WHERE { ?r rdf:reifies <<( :alice ?p ?o )>> }", "r")));
        assertEquals(Set.of("urn:r1", "urn:r4", "urn:x3"), withoutBlankNodes(
                column("SELECT ?r WHERE { ?r rdf:reifies <<( :alice :knows ?o )>> }", "r")));
        assertEquals(Set.of("urn:r1", "urn:r2", "urn:r4", "urn:x2", "urn:x3"), withoutBlankNodes(
                column("SELECT ?r WHERE { ?r ?how <<( :alice ?p ?o )>> }", "r")));
        assertEquals(Set.of("urn:x3"), column("SELECT ?r WHERE { GRAPH :g2 { ?r rdf:reifies <<( :alice ?p ?o )>> } }", "r"));
        assertEquals(Set.of("urn:r4", "urn:x3"),
                column("SELECT ?r WHERE { GRAPH ?g { ?r rdf:reifies <<( :alice ?p ?o )>> } FILTER(?g IN (:g, :g2)) }", "r"));
        assertEquals(Set.of("urn:x1"), column("SELECT ?r WHERE { ?r rdf:reifies <<( :alic ?p ?o )>> }", "r"));
    }

    private static Set<String> withoutBlankNodes(final Set<String> values) {
        values.removeIf(v -> v.startsWith(org.tripatha.api.domain.RyaSchema.BNODE_NAMESPACE));
        return values;
    }

    // ------------------------------------------------------------------ helpers

    private SailRepository repository(final int batchSize, final boolean stats, final boolean inference) {
        final StoreRdfConfiguration conf = newConf();
        conf.setJoinBatchSize(batchSize);
        conf.setUseStats(stats);
        final SailRepository repository = new SailRepository(newStore(conf, inference));
        repository.init();
        repositories.add(repository);
        return repository;
    }

    private void update(final String update) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(update).execute();
        }
    }

    private static TripleTerm term(final String s, final String p, final Value o) {
        return VF.createTripleTerm(VF.createIRI(s), VF.createIRI(p), o);
    }

    private List<Map<String, Value>> rows(final String query) {
        final List<Map<String, Value>> rows = new ArrayList<>();
        counted(repo, query).forEach((row, n) -> {
            for (int i = 0; i < n; i++) {
                rows.add(row);
            }
        });
        return rows;
    }

    private Set<String> column(final String query, final String name) {
        final Set<String> values = new TreeSet<>();
        rows(query).forEach(row -> values.add(row.get(name).stringValue()));
        return values;
    }

    private static Map<Map<String, Value>, Integer> counted(final SailRepository repository, final String query) {
        final Map<Map<String, Value>, Integer> rows = new HashMap<>();
        try (RepositoryConnection conn = repository.getConnection();
                TupleQueryResult result = conn.prepareTupleQuery(PREFIXES + query).evaluate()) {
            while (result.hasNext()) {
                final Map<String, Value> row = new HashMap<>();
                for (final Binding b : (BindingSet) result.next()) {
                    row.put(b.getName(), b.getValue());
                }
                rows.merge(row, 1, Integer::sum);
            }
        }
        return rows;
    }
}
