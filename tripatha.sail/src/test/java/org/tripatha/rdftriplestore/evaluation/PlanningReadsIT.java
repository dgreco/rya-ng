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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

/**
 * The counters join ordering reads, read together before it starts (SCALE-STUDY.md M14): the
 * same estimates, so the same plans and rows, in one round trip instead of one per pattern.
 */
public class PlanningReadsIT extends RyaSailITBase {
    private static final String TYPE = RDF.TYPE.stringValue();
    private static final List<String> QUERIES = List.of(
            "SELECT ?x ?n ?f WHERE { ?x a <urn:C> ; <urn:name> ?n ; <urn:knows> ?f }",
            "SELECT ?x ?f ?g WHERE { ?x <urn:knows> ?f . ?f <urn:knows> ?g . ?g a <urn:D> }",
            "SELECT ?p ?o WHERE { <urn:s3> ?p ?o . <urn:s3> <urn:knows> ?f }",
            "SELECT ?x ?n WHERE { ?x <urn:knows> <urn:s5> ; <urn:name> ?n }",
            "SELECT ?x ?n ?e WHERE { ?x a <urn:C> ; <urn:name> ?n OPTIONAL { ?x <urn:email> ?e } }",
            "SELECT ?x WHERE { { ?x a <urn:C> } UNION { ?x a <urn:D> } ?x <urn:name> ?n }",
            "SELECT ?x ?n WHERE { GRAPH <urn:g1> { ?x <urn:knows> ?f } ?x <urn:name> ?n }",
            "SELECT ?x ?n WHERE { ?x a ?k ; <urn:name> ?n FILTER(?k = <urn:C>) }");

    private final List<SailRepository> repositories = new ArrayList<>();

    @BeforeEach
    public void load() {
        grantAuthorizations("A");
        final List<RyaStatement> statements = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final String s = "urn:s" + i;
            statements.add(st(s, TYPE, i % 3 == 0 ? "urn:D" : "urn:C", null, null));
            statements.add(new RyaStatement(new RyaIRI(s), new RyaIRI("urn:name"), new RyaType("n" + i)));
            statements.add(st(s, "urn:knows", "urn:s" + (i + 1) % 40, i % 4 == 0 ? "urn:g1" : null, null));
            if (i % 5 == 0) {
                statements.add(st(s, "urn:knows", "urn:s5", null, "A"));
            }
            if (i % 2 == 0) {
                statements.add(new RyaStatement(new RyaIRI(s), new RyaIRI("urn:email"), new RyaType(i + "@example.org")));
            }
        }
        final RyaStoreDAO dao = newDao(newConf());
        try {
            dao.add(statements.iterator());
        } finally {
            dao.destroy();
        }
    }

    private static RyaStatement st(final String s, final String p, final String o, final String graph, final String label) {
        return new RyaStatement(new RyaIRI(s), new RyaIRI(p), new RyaIRI(o), graph == null ? null : new RyaIRI(graph), null,
                label == null ? null : label.getBytes(StandardCharsets.UTF_8), null);
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    private SailRepository repository(final boolean prefetch, final boolean labelled) {
        final StoreRdfConfiguration conf = newConf();
        conf.setStatsPrefetch(prefetch);
        if (labelled) {
            conf.setAuths("A");
        }
        final SailRepository repo = new SailRepository(newStore(conf, false));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    /** What one traced run gives: the rows, the optimized plan, and its statistics reads before the first scan. */
    private record Run(List<String> rows, String plan, int singleReads, int batchReads) {
    }

    @Test
    public void theCountersAreReadTogetherForTheSamePlans() {
        for (final boolean labelled : List.of(false, true)) {
            final SailRepository together = repository(true, labelled);
            final SailRepository oneByOne = repository(false, labelled);
            int batched = 0;
            for (final String query : QUERIES) {
                final String which = query + (labelled ? " (label A)" : "");
                final Run on = run(together, query);
                final Run off = run(oneByOne, query);
                assertEquals(off.rows(), on.rows(), which);
                assertEquals(off.plan(), on.plan(), "the same estimates, so the same plan: " + which);
                assertEquals(0, off.batchReads(), which);
                if (off.singleReads() >= 2) {
                    assertEquals(1, on.batchReads(), "one batch: " + which);
                    assertEquals(0, on.singleReads(), "no read one by one: " + which);
                    batched++;
                } else {
                    assertEquals(off.singleReads(), on.singleReads() + on.batchReads(), which);
                }
            }
            assertTrue(batched >= 5, "most queries read two counters or more: " + batched);
        }
    }

    private static Run run(final SailRepository repo, final String query) {
        final List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> rows = new ArrayList<>();
        try (StoreTrace.Scope scope = StoreTrace.start(events::add); RepositoryConnection conn = repo.getConnection();
                TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
            for (final BindingSet bs : result) {
                rows.add(bs.toString());
            }
        }
        Collections.sort(rows);
        String plan = null;
        double optimized = Double.MAX_VALUE;
        int single = 0;
        int batch = 0;
        synchronized (events) {
            for (final TraceEvent e : events) {
                if (e instanceof TraceEvent.Plan p && p.stage().equals("optimized")) {
                    plan = p.tree();
                    optimized = p.at();
                }
            }
            for (final TraceEvent e : events) {
                if (e instanceof TraceEvent.ScanStart s && s.at() <= optimized && s.reason() != null && s.reason().contains("statistics: ")) {
                    if (s.reason().contains("read together")) {
                        batch++;
                    } else {
                        single++;
                    }
                }
            }
        }
        assertTrue(plan != null, "the trace has the optimized plan");
        return new Run(rows, plan, single, batch);
    }
}
