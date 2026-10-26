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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.index.IndexType;
import io.github.dgreco.rya.accumulo.index.IndexVerifier;

/** SPARQL updates keep the indexes exactly in step with the stored statements. */
class IndexUpdateIT extends IndexITBase {
    private static final String DATA = """
            INSERT DATA {
              <urn:a> rdfs:label "alpha fox" ; geo:asWKT "POINT(1 1)"^^geo:wktLiteral .
              <urn:b> rdfs:label "beta fox" ; geo:asWKT "POINT(2 2)"^^geo:wktLiteral .
              GRAPH <urn:g1> { <urn:c> rdfs:label "gamma fox" ; geo:asWKT "POINT(3 3)"^^geo:wktLiteral }
              GRAPH <urn:g2> { <urn:d> rdfs:label "delta fox" ; geo:asWKT "POLYGON((0 0, 5 0, 5 5, 0 0))"^^geo:wktLiteral }
            }""";
    private static final List<String> QUERIES = List.of(
            "SELECT * WHERE { ?s ?p ?l FILTER(text:matches(?l, \"fox\")) }",
            "SELECT * WHERE { GRAPH ?g { ?s ?p ?l } FILTER(text:matches(?l, \"fox\")) }",
            "SELECT * WHERE { ?s geo:asWKT ?w FILTER(geof:sfIntersects(?w, \"POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))\"^^geo:wktLiteral)) }");

    private void assertInStep(final AccumuloRdfConfiguration conf, final SailRepository repo, final String after)
            throws Exception {
        for (final IndexType type : IndexType.values()) {
            final IndexVerifier.Report report = IndexVerifier.verify(getClient(), conf, type);
            assertTrue(report.isClean(), after + ", " + type + ": " + report);
        }
        for (final String q : QUERIES) {
            assertAgrees(repo, q, null);
        }
    }

    @Test
    void everyKindOfUpdateKeepsTheIndexesInStep() throws Exception {
        final AccumuloRdfConfiguration conf = indexedConf();
        final SailRepository repo = open(conf, false);
        update(repo, DATA);
        assertInStep(conf, repo, "INSERT DATA");
        assertEquals(4, total(run(repo, QUERIES.get(0), true, null)));

        update(repo, "DELETE DATA { <urn:a> rdfs:label \"alpha fox\" }");
        assertInStep(conf, repo, "DELETE DATA");

        update(repo, "DELETE { ?s ?p ?w } WHERE { ?s ?p ?w FILTER(geof:sfWithin(?w, "
                + "\"POLYGON((1.5 1.5, 2.5 1.5, 2.5 2.5, 1.5 2.5, 1.5 1.5))\"^^geo:wktLiteral)) }");
        assertInStep(conf, repo, "DELETE WHERE with a geo filter");

        update(repo, "COPY <urn:g1> TO <urn:g3>");
        assertInStep(conf, repo, "COPY");
        update(repo, "MOVE <urn:g2> TO <urn:g4>");
        assertInStep(conf, repo, "MOVE");
        update(repo, "CLEAR GRAPH <urn:g1>");
        assertInStep(conf, repo, "CLEAR GRAPH");
        update(repo, "DROP DEFAULT");
        assertInStep(conf, repo, "DROP DEFAULT");
        assertEquals(2, total(run(repo, QUERIES.get(0), true, null)), "g3 and g4 are left");

        update(repo, DATA);
        update(repo, "DROP ALL");
        assertInStep(conf, repo, "DROP ALL");
        assertEquals(0, total(run(repo, QUERIES.get(0), true, null)));
    }
}
