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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

/**
 * A {@code STRSTARTS} filter on a pattern's object narrows the pattern's scan to the prefix
 * (O13), and answers stay those of the full scan: every query here is also run with
 * {@code query.prefixranges} off. (Writing the filter as {@code (…) || false} would not do:
 * RDF4J folds it back to the plain condition.)
 */
public class ObjectPrefixIT extends RyaSailITBase {
    private static final String DATA = """
            INSERT DATA {
              <urn:a1> <urn:code> "abc-1" . <urn:a2> <urn:code> "abc-2"@en . <urn:a3> <urn:code> "abd-3" .
              <urn:a4> <urn:code> "xabc" . <urn:a5> <urn:code> <urn:abc:iri> . <urn:a6> <urn:code> 42 .
              <urn:a7> <urn:code> "abc"^^<urn:dt> . <urn:a8> <urn:code> "abcdef"^^<http://www.w3.org/2001/XMLSchema#string> .
              <urn:a9> <urn:code> "abc-9"@ar--rtl . <urn:a10> <urn:code> "ab" . <urn:a11> <urn:code> "4abc" .
              <urn:r> <urn:code> <<( <urn:x> <urn:y> "abc" )>> .
              <urn:a1> <urn:name> "abc-name" . <urn:a3> <urn:name> "Three" .
              GRAPH <urn:g1> { <urn:b1> <urn:code> "abc-g" . <urn:b2> <urn:code> "zzz" }
            }""";

    /** {@code %s} is the filter condition. */
    private static final List<String> QUERIES = List.of(
            "SELECT ?s ?o WHERE { ?s <urn:code> ?o FILTER(%s) }",
            "SELECT ?s ?p ?o WHERE { ?s ?p ?o FILTER(%s) }",
            "SELECT ?s ?o WHERE { GRAPH <urn:g1> { ?s <urn:code> ?o } FILTER(%s) }",
            "SELECT ?g ?s ?o WHERE { GRAPH ?g { ?s <urn:code> ?o FILTER(%s) } }",
            "SELECT ?s ?o ?n WHERE { ?s <urn:code> ?o . ?s <urn:name> ?n FILTER(%s) }",
            "SELECT ?s ?o ?n WHERE { ?s <urn:code> ?o OPTIONAL { ?s <urn:name> ?n } FILTER(%s) }",
            "SELECT ?o WHERE { <urn:a1> <urn:code> ?o FILTER(%s) }",
            "SELECT ?s ?o WHERE { ?s <urn:code> ?o FILTER(%s) } ORDER BY ?o LIMIT 2");

    private static final List<String> CONDITIONS = List.of(
            "STRSTARTS(?o, \"abc\")",
            "STRSTARTS(?o, \"abc\"@en)",
            "STRSTARTS(?o, \"abc\") && STRLEN(?o) > 3",
            "STRSTARTS(?o, \"a\")",
            "STRSTARTS(STR(?o), \"4\")",
            "STRSTARTS(?o, \"\")");

    private final List<SailRepository> repositories = new ArrayList<>();

    @BeforeEach
    public void load() {
        grantAuthorizations("A");
        final SailRepository repo = repository(true, true);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(DATA).execute();
        }
        final StoreRdfConfiguration conf = newConf();
        final RyaStoreDAO dao = newDao(conf);
        try {
            dao.add(new RyaStatement(new RyaIRI("urn:c1"), new RyaIRI("urn:code"), new RyaType("abc-secret"), null, null,
                    "A".getBytes(StandardCharsets.UTF_8), null, 1L));
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
        for (final boolean labelled : List.of(true, false)) {
            final SailRepository ranges = repository(labelled, true);
            final SailRepository full = repository(labelled, false);
            for (final String query : QUERIES) {
                for (final String condition : CONDITIONS) {
                    final String q = String.format(query, condition);
                    assertEquals(rows(full, q), rows(ranges, q), q + (labelled ? " (label A)" : ""));
                }
            }
        }
    }

    @Test
    public void onlyTheMatchingPrefixIsRead() {
        final SailRepository repo = repository(true, true);
        final String narrowed = "SELECT ?s ?o WHERE { ?s <urn:code> ?o FILTER(STRSTARTS(?o, \"abc\")) }";
        final Read fast = read(repo, narrowed);
        final Read full = read(repository(true, false), narrowed);
        assertTrue(fast.narrowed, "the STRSTARTS range was used");
        assertFalse(full.narrowed);
        assertTrue(fast.cells < full.cells, fast.cells + " cells read, against " + full.cells);
        // The strings and language-tagged strings starting with "abc", the labelled one included.
        final Set<String> subjects = new TreeSet<>();
        for (final String row : rows(repo, narrowed)) {
            subjects.add(row.substring(row.indexOf("s=") + 2, row.indexOf(' ', row.indexOf("s="))));
        }
        assertTrue(subjects.containsAll(Set.of("urn:a1", "urn:a2", "urn:a8", "urn:c1")), subjects.toString());
        assertFalse(subjects.contains("urn:a3") || subjects.contains("urn:a4") || subjects.contains("urn:a5")
                || subjects.contains("urn:a6") || subjects.contains("urn:a10"), subjects.toString());
    }

    // ------------------------------------------------------------------ helpers

    private record Read(boolean narrowed, long cells) {
    }

    private static Read read(final SailRepository repo, final String query) {
        final boolean[] narrowed = new boolean[1];
        final long[] cells = new long[1];
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan && scan.reason().contains("STRSTARTS")) {
                narrowed[0] = true;
            } else if (event instanceof TraceEvent.ScanEnd end) {
                cells[0] += Math.max(0, end.entries());
            }
        })) {
            rows(repo, query);
        }
        return new Read(narrowed[0], cells[0]);
    }

    private SailRepository repository(final boolean labelled, final boolean prefixRanges) {
        final StoreRdfConfiguration conf = newConf();
        conf.setPrefixRanges(prefixRanges);
        if (labelled) {
            conf.setAuths("A");
        }
        final SailRepository repo = new SailRepository(newStore(conf, false));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    private static Set<String> rows(final SailRepository repo, final String query) {
        final Set<String> rows = new HashSet<>();
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
}
