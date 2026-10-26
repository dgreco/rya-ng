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
package io.github.dgreco.rya.rdftriplestore.evaluation.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;

/** Text search returns the same rows with the index as without it, and uses it when it can. */
class TextSearchIT extends IndexITBase {
    private static final String DATA = """
            INSERT DATA {
              <urn:d1> rdfs:label "The quick brown fox" ; a <urn:Doc> .
              <urn:d2> rdfs:label "Quick thinking"@en .
              <urn:d3> rdfs:label "Le renard brun rapide"@fr .
              <urn:d4> rdfs:comment "A brown dog, not a fox" ; a <urn:Doc> .
              <urn:d5> rdfs:label "Café crème" .
              <urn:d6> rdfs:label "cafeteria menu" .
              <urn:d7> <urn:code> "fox"^^xsd:token .
              <urn:d8> rdfs:label "Foxes and hounds" .
              <urn:d10> <urn:sub> "quick fox via a subproperty" .
              <urn:d12> <urn:name> "a plain name, no animal" .
              <urn:sub> rdfs:subPropertyOf <urn:name> .
              GRAPH <urn:g1> { <urn:d9> rdfs:label "brown fox in a graph" ; a <urn:Doc> .
                               <urn:d1> rdfs:label "quick fox again, in g1" }
              GRAPH <urn:g2> { <urn:d11> rdfs:comment "another brown fox" }
            }""";

    private static String select(final String where) {
        return "SELECT * WHERE { " + where + " }";
    }

    private static String label(final String query) {
        return select("?d rdfs:label ?l FILTER(text:matches(?l, \"" + query.replace("\"", "\\\"") + "\"))");
    }

    private SailRepository loaded(final AccumuloRdfConfiguration conf, final boolean inference) throws Exception {
        final SailRepository repo = open(conf, inference);
        update(repo, DATA);
        if (inference) {
            ((RdfCloudTripleStore<?>) repo.getSail()).getInferenceEngine().refreshGraph();
        }
        return repo;
    }

    private static Set<String> docs(final Map<Map<String, Value>, Integer> rows) {
        return rows.keySet().stream().map(r -> r.get("d").stringValue()).collect(Collectors.toSet());
    }

    @Test
    void queriesAgreeWithAndWithoutTheIndex() throws Exception {
        final SailRepository repo = loaded(indexedConf(), false);
        for (final String q : List.of("fox", "quick fox", "fox OR dog", "brown NOT dog", "caf*", "\"brown fox\"",
                "QUICK", "cafe", "renard", "quick AND (fox OR thinking)", "nothing-here")) {
            assertAgrees(repo, label(q), true);
        }
        assertEquals(Set.of("urn:d1", "urn:d9"), docs(assertAgrees(repo, label("brown fox"), true)));
        assertEquals(Set.of("urn:d5"), docs(assertAgrees(repo, label("CAFÉ"), true)));

        // Every predicate, joins in both orders, OPTIONAL, UNION, COUNT, two filters.
        assertAgrees(repo, select("?d ?p ?l FILTER(text:matches(?l, \"fox\"))"), true);
        // Joins: the plan may start from the 3 documents and look each one up directly (no index).
        assertAgrees(repo, select("?d a <urn:Doc> . ?d ?p ?l FILTER(text:matches(?l, \"brown\"))"), null);
        assertAgrees(repo, select("?d ?p ?l . ?d a <urn:Doc> FILTER(text:matches(?l, \"brown\"))"), null);
        assertAgrees(repo, select("?d a <urn:Doc> . ?d rdfs:label ?l FILTER(text:matches(?l, \"brown fox\"))"), true);
        assertAgrees(repo, select("?d rdfs:label ?l . ?d a <urn:Doc> FILTER(text:matches(?l, \"brown fox\"))"), true);
        assertAgrees(repo, select("?d ?p ?l OPTIONAL { ?d a ?type } FILTER(text:matches(?l, \"fox\"))"), true);
        assertAgrees(repo, select("{ ?d rdfs:label ?l } UNION { ?d rdfs:comment ?l } FILTER(text:matches(?l, \"brown\"))"), true);
        assertAgrees(repo, "SELECT (COUNT(*) AS ?n) WHERE { ?d ?p ?l FILTER(text:matches(?l, \"fox\")) }", true);
        assertAgrees(repo, select("?d ?p ?l FILTER(text:matches(?l, \"fox\") && text:matches(?l, \"quick\"))"), true);
        // The typed literal "fox"^^xsd:token is not text, with or without the index.
        assertFalse(docs(assertAgrees(repo, select("?d ?p ?l FILTER(text:matches(?l, \"fox\"))"), true)).contains("urn:d7"));
    }

    @Test
    void graphsAndDatasets() throws Exception {
        final SailRepository repo = loaded(indexedConf(), false);
        assertAgrees(repo, select("GRAPH ?g { ?d ?p ?l } FILTER(text:matches(?l, \"fox\"))"), true);
        assertEquals(Set.of("urn:d1", "urn:d9"),
                docs(assertAgrees(repo, select("GRAPH <urn:g1> { ?d ?p ?l } FILTER(text:matches(?l, \"fox\"))"), true)));
        assertAgrees(repo, "SELECT * FROM <urn:g1> WHERE { ?d ?p ?l FILTER(text:matches(?l, \"fox\")) }", true);
        assertAgrees(repo, "SELECT * FROM NAMED <urn:g2> WHERE { GRAPH ?g { ?d ?p ?l } FILTER(text:matches(?l, \"fox\")) }", true);
        assertAgrees(repo, "SELECT * FROM <urn:nowhere> WHERE { ?d ?p ?l FILTER(text:matches(?l, \"fox\")) }", true);
    }

    @Test
    void queriesTheIndexCannotNarrowStillWork() throws Exception {
        final SailRepository repo = loaded(indexedConf(), false);
        assertTrue(total(assertAgrees(repo, label("NOT fox"), false)) > 0);
        assertAgrees(repo, label("fo*"), false);
        assertAgrees(repo, select("<urn:d1> rdfs:label ?l FILTER(text:matches(?l, \"fox\"))"), false);
        assertAgrees(repo, select("?d rdfs:label ?l FILTER(text:matches(?l, \"fox\") || ?l = \"x\")"), false);
        assertEquals(2, total(assertAgrees(repo, label("fox") + " ORDER BY ?d ?l LIMIT 2", true)));
    }

    @Test
    void inferredSubPropertiesAreSearchedToo() throws Exception {
        // (Rya's inference never expands RDF/RDFS predicates such as rdfs:label, so use urn:name.)
        final SailRepository repo = loaded(indexedConf(), true);
        assertEquals(Set.of("urn:d10"), docs(assertAgrees(repo,
                select("?d <urn:name> ?l FILTER(text:matches(?l, \"quick fox\"))"), true)));
        assertEquals(Set.of("urn:d10", "urn:d12"), docs(assertAgrees(repo,
                select("?d <urn:name> ?l FILTER(text:matches(?l, \"fox OR name\"))"), true)));
    }

    @Test
    void aPredicateListLimitsWhatIsIndexed() throws Exception {
        final AccumuloRdfConfiguration conf = indexedConf();
        conf.setTextIndexPredicates(List.of(RDFS.LABEL.stringValue()));
        final SailRepository repo = loaded(conf, false);
        assertAgrees(repo, label("fox"), true);
        assertTrue(total(assertAgrees(repo, select("?d rdfs:comment ?l FILTER(text:matches(?l, \"fox\"))"), false)) > 0,
                "comments are not indexed, but still searchable");
        assertAgrees(repo, select("?d ?p ?l FILTER(text:matches(?l, \"fox\"))"), false);
    }

    @Test
    void malformedQueriesFailTheSameWayWithOrWithoutTheIndex() throws Exception {
        final SailRepository repo = loaded(indexedConf(), false);
        for (final boolean useIndex : new boolean[] { true, false }) {
            final Exception e = assertThrows(Exception.class, () -> run(repo, label("fox AND"), useIndex, null));
            assertTrue(causedBy(e, MalformedQueryException.class), e.toString());
        }
    }
}
