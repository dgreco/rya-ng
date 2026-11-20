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
package org.tripatha.rdftriplestore.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.vocabulary.RDF;
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
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;

/** Class and predicate sizes from the counters (SCALE-STUDY.md O8), against exact counts. */
public class ApproxCountIT extends RyaSailITBase {
    private static final String PREFIX = "PREFIX stats: <urn:tripatha:stats#>\n";
    private static final String TYPE = RDF.TYPE.stringValue();

    private final List<SailRepository> repositories = new ArrayList<>();

    @BeforeEach
    public void load() {
        grantAuthorizations("A");
        final List<RyaStatement> statements = new ArrayList<>(List.of(
                st("urn:a", TYPE, "urn:Person", "urn:g1", null), st("urn:a", "urn:p", null, "urn:g1", null),
                st("urn:b", TYPE, "urn:Person", "urn:g2", null), st("urn:b", TYPE, "urn:Robot", "urn:g2", null),
                st("urn:b", "urn:p", null, "urn:g2", null),
                st("urn:c", TYPE, "urn:Robot", null, null), st("urn:c", "urn:p", null, null, null), st("urn:c", "urn:q", null, null, null),
                // Under a label: counted only for a reader holding it.
                st("urn:d", TYPE, "urn:Person", null, "A"), st("urn:d", "urn:p", null, null, "A"),
                // A statement in two graphs is two statements, and counts twice.
                st("urn:e", TYPE, "urn:Robot", "urn:g1", null), st("urn:e", TYPE, "urn:Robot", "urn:g2", null)));
        final RyaStoreDAO dao = newDao(newConf());
        try {
            dao.add(statements.iterator());
        } finally {
            dao.destroy();
        }
    }

    private static RyaStatement st(final String s, final String p, final String iri, final String graph, final String label) {
        return new RyaStatement(new RyaIRI(s), new RyaIRI(p), iri == null ? new RyaType("v-" + s) : new RyaIRI(iri),
                graph == null ? null : new RyaIRI(graph), null, label == null ? null : label.getBytes(StandardCharsets.UTF_8),
                null);
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    private SailRepository repository(final boolean labelled, final boolean stats) {
        final StoreRdfConfiguration conf = newConf();
        if (labelled) {
            conf.setAuths("A");
        }
        if (!stats) {
            conf.setUseStats(false);
        }
        final SailRepository repo = new SailRepository(newStore(conf, false));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    @Test
    public void classAndPredicateSizesAreTheExactCountsOfStoredStatements() {
        for (final boolean labelled : List.of(false, true)) {
            final SailRepository repo = repository(labelled, true);
            final String which = labelled ? " (label A)" : "";
            try (RepositoryConnection conn = repo.getConnection()) {
                final Map<String, Long> classes = counts(conn, "SELECT ?k ?n WHERE { ?k stats:approxInstances ?n }");
                assertEquals(counts(conn, "SELECT ?k (COUNT(*) AS ?n) WHERE { ?x a ?k } GROUP BY ?k"), classes, "classes" + which);
                assertEquals(Map.of("urn:Person", labelled ? 3L : 2L, "urn:Robot", 4L), classes, "classes" + which);

                final Map<String, Long> predicates = counts(conn, "SELECT ?k ?n WHERE { ?k stats:approxTriples ?n }");
                assertEquals(counts(conn, "SELECT ?k (COUNT(*) AS ?n) WHERE { ?s ?k ?o } GROUP BY ?k"), predicates,
                        "predicates" + which);
                assertEquals(Map.of(TYPE, labelled ? 7L : 6L, "urn:p", labelled ? 4L : 3L, "urn:q", 1L), predicates,
                        "predicates" + which);

                // A constant subject reads its own counter; BIND, FILTER and joins work as for any pattern.
                assertEquals(Map.of("urn:Person", labelled ? 3L : 2L),
                        counts(conn, "SELECT ?k ?n WHERE { BIND(<urn:Person> AS ?k) <urn:Person> stats:approxInstances ?n }"));
                assertEquals(Map.of("urn:q", 1L), counts(conn, "SELECT ?k ?n WHERE { BIND(<urn:q> AS ?k) ?k stats:approxTriples ?n }"));
                assertEquals(Map.of("urn:Robot", 4L), counts(conn, "SELECT ?k ?n WHERE { ?k stats:approxInstances ?n FILTER(?n > 3) }"));
                assertEquals(Map.of("urn:Person", labelled ? 3L : 2L, "urn:Robot", 4L),
                        counts(conn, "SELECT ?k ?n WHERE { <urn:b> a ?k . ?k stats:approxInstances ?n }"), "the classes of urn:b");
                assertEquals(Map.of(), counts(conn, "SELECT ?k ?n WHERE { BIND(<urn:None> AS ?k) <urn:None> stats:approxInstances ?n }"));
                assertEquals(Map.of(), counts(conn, "SELECT ?k ?n WHERE { BIND(1 AS ?k) ?k stats:approxTriples ?n }"));
            }
        }
    }

    @Test
    public void deletesCountDown() {
        final SailRepository repo = repository(false, true);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate("DELETE DATA { GRAPH <urn:g2> { <urn:b> a <urn:Robot> } }").execute();
            assertEquals(Map.of("urn:Person", 2L, "urn:Robot", 3L), counts(conn, "SELECT ?k ?n WHERE { ?k stats:approxInstances ?n }"));
            assertEquals(Map.of(TYPE, 5L), counts(conn, "SELECT ?k ?n WHERE { BIND(<" + TYPE + "> AS ?k) ?k stats:approxTriples ?n }"));
        }
    }

    @Test
    public void approximateCountsNeedStatistics() {
        final SailRepository repo = repository(false, false);
        try (RepositoryConnection conn = repo.getConnection()) {
            for (final String query : List.of("SELECT ?k ?n WHERE { ?k stats:approxInstances ?n }",
                    "SELECT ?k ?n WHERE { ?k stats:approxTriples ?n }")) {
                final RDF4JException e = assertThrows(RDF4JException.class, () -> counts(conn, query));
                boolean explained = false;
                for (Throwable t = e; t != null; t = t.getCause()) {
                    explained |= String.valueOf(t.getMessage()).contains("needs the statistics");
                }
                assertTrue(explained, query + ": " + e);
            }
        }
    }

    private static Map<String, Long> counts(final RepositoryConnection conn, final String query) {
        final Map<String, Long> counts = new TreeMap<>();
        try (TupleQueryResult result = conn.prepareTupleQuery(PREFIX + query).evaluate()) {
            for (final BindingSet bs : result) {
                counts.put(bs.getValue("k").stringValue(), ((Literal) bs.getValue("n")).longValue());
            }
        }
        return counts;
    }
}
