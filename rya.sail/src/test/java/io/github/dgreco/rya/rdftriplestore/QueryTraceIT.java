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
package io.github.dgreco.rya.rdftriplestore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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

import io.github.dgreco.rya.RyaSailITBase;
import io.github.dgreco.rya.accumulo.trace.AccumuloTrace;
import io.github.dgreco.rya.accumulo.trace.TraceEvent;

public class QueryTraceIT extends RyaSailITBase {
    private static final String JOIN = "SELECT ?f ?a WHERE { <urn:alice> <urn:knows> ?f . ?f <urn:age> ?a }";

    private SailRepository repo;
    private RepositoryConnection conn;

    @BeforeEach
    public void load() {
        repo = new SailRepository(newStore(newConf(), false));
        repo.init();
        conn = repo.getConnection();
        final StringBuilder data = new StringBuilder("INSERT DATA {");
        for (int i = 0; i < 5; i++) {
            data.append(" <urn:alice> <urn:knows> <urn:f").append(i).append("> . <urn:f").append(i).append("> <urn:age> ")
                    .append(20 + i).append(" .");
        }
        conn.prepareUpdate(data.append(" }").toString()).execute();
    }

    @AfterEach
    public void close() {
        conn.close();
        repo.shutDown();
    }

    @Test
    public void aTracedQueryRunsLikeAnUntracedOneAndReportsItsPlanAndScans() {
        final Set<String> untraced = results(JOIN);
        final List<TraceEvent> events = new ArrayList<>();
        final Set<String> traced;
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add)) {
            traced = results(JOIN);
        }
        assertEquals(untraced, traced);
        assertEquals(5, traced.size());

        final List<TraceEvent.Plan> plans = events.stream().filter(e -> e instanceof TraceEvent.Plan).map(e -> (TraceEvent.Plan) e).toList();
        assertEquals(List.of("parsed", "optimized"), plans.stream().map(TraceEvent.Plan::stage).toList());
        assertTrue(plans.get(1).tree().contains("RyaBatchedJoin"), plans.get(1).tree());

        final List<TraceEvent.ScanStart> scans = events.stream().filter(e -> e instanceof TraceEvent.ScanStart)
                .map(e -> (TraceEvent.ScanStart) e).toList();
        assertTrue(scans.stream().anyMatch(s -> s.reason().startsWith("query planning › statistics:")), scans.toString());
        assertTrue(scans.stream().anyMatch(s -> s.reason().startsWith("lookup <urn:alice> <urn:knows> ? on SPO")), scans.toString());
        assertTrue(scans.stream().anyMatch(s -> s.reason().startsWith("batched join: ")
                && s.reason().contains("› batched lookup of ")), scans.toString());
        final TraceEvent.Summary summary = (TraceEvent.Summary) events.get(events.size() - 1);
        assertEquals(scans.size(), summary.scans());
        assertTrue(!AccumuloTrace.isActive());
    }

    @Test
    public void anUpdateReportsItsWrites() {
        final List<TraceEvent> events = new ArrayList<>();
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add)) {
            conn.prepareUpdate("INSERT DATA { <urn:bob> <urn:age> 30 }").execute();
        }
        assertTrue(events.stream().anyMatch(e -> e instanceof TraceEvent.Flush f
                && f.tables().stream().anyMatch(w -> w.table().endsWith("spo") && w.mutations() == 1)), events.toString());
    }

    private Set<String> results(final String query) {
        final Set<String> rows = new TreeSet<>();
        try (TupleQueryResult r = conn.prepareTupleQuery(query).evaluate()) {
            for (final BindingSet bs : r) {
                rows.add(bs.getValue("f") + " " + bs.getValue("a"));
            }
        }
        return rows;
    }
}
