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
package io.github.dgreco.rya.rdftriplestore.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.RyaSailITBase;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;

public class GraphSizeIT extends RyaSailITBase {
    private static final String PREFIX = "PREFIX stats: <urn:rya-ng:stats#>\n";
    private static final String DATA = "INSERT DATA { GRAPH <urn:g1> { <urn:a> <urn:p> 1 . <urn:a> <urn:q> 2 } "
            + "GRAPH <urn:g2> { <urn:b> <urn:p> 3 } <urn:c> <urn:p> 4 }";

    @Test
    public void approxSizeListsNamedGraphsFromTheCounters() {
        final SailRepository repo = new SailRepository(newStore(newConf(), false));
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(DATA).execute();

            assertEquals(Map.of("urn:g1", 2L, "urn:g2", 1L), sizes(conn, "SELECT ?g ?n WHERE { ?g stats:approxSize ?n }"));
            assertEquals(Map.of("urn:g2", 1L), sizes(conn, "SELECT ?g ?n WHERE { BIND(<urn:g2> AS ?g) ?g stats:approxSize ?n }"));
            assertEquals(Map.of("urn:g1", 2L), sizes(conn, "SELECT ?g ?n WHERE { ?g stats:approxSize ?n FILTER(?n > 1) }"));
            // Joined with ordinary patterns: the graphs that hold a statement about <urn:b>.
            assertEquals(Map.of("urn:g2", 1L),
                    sizes(conn, "SELECT ?g ?n WHERE { ?g stats:approxSize ?n . GRAPH ?g { <urn:b> ?p ?o } }"));
        } finally {
            repo.shutDown();
        }
    }

    @Test
    public void approxSizeNeedsStatistics() {
        final AccumuloRdfConfiguration conf = newConf();
        conf.setUseStats(false);
        final SailRepository repo = new SailRepository(newStore(conf, false));
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            final RDF4JException e = assertThrows(RDF4JException.class,
                    () -> sizes(conn, "SELECT ?g ?n WHERE { ?g stats:approxSize ?n }"));
            boolean explained = false;
            for (Throwable t = e; t != null; t = t.getCause()) {
                explained |= String.valueOf(t.getMessage()).contains("needs the statistics");
            }
            assertTrue(explained, e.toString());
        } finally {
            repo.shutDown();
        }
    }

    private static Map<String, Long> sizes(final RepositoryConnection conn, final String query) {
        final Map<String, Long> sizes = new TreeMap<>();
        try (TupleQueryResult result = conn.prepareTupleQuery(PREFIX + query).evaluate()) {
            for (final BindingSet bs : result) {
                sizes.put(bs.getValue("g").stringValue(), ((Literal) bs.getValue("n")).longValue());
            }
        }
        return sizes;
    }
}
