package io.github.dgreco.rya.rdftriplestore.evaluation;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.dgreco.rya.RyaSailITBase;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceOptimizer;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Batched join lookups must give exactly the results of one lookup per row, and must
 * actually replace the per-row lookups.
 */
public class BatchedJoinIT extends RyaSailITBase {

    private static final String DATA = """
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            INSERT DATA {
              <urn:p0> <urn:knows> <urn:p1> . <urn:p1> <urn:knows> <urn:p2> . <urn:p2> <urn:knows> <urn:p3> .
              <urn:p3> <urn:knows> <urn:p4> . <urn:p4> <urn:knows> <urn:p5> . <urn:p5> <urn:knows> <urn:p6> .
              <urn:p6> <urn:knows> <urn:p7> . <urn:p7> <urn:knows> <urn:p8> . <urn:p8> <urn:knows> <urn:p9> .
              <urn:p0> <urn:knows> <urn:p5> . <urn:p2> <urn:knows> <urn:p7> .
              <urn:p1> <urn:name> "One" . <urn:p2> <urn:name> "Two" . <urn:p3> <urn:name> "Three" .
              <urn:p4> <urn:name> "Four" . <urn:p5> <urn:name> "Five" . <urn:p6> <urn:name> "Six" .
              <urn:p7> <urn:name> "Seven" . <urn:p8> <urn:name> "Eight" . <urn:p9> <urn:name> "Nine" .
              <urn:p5> <urn:name> "Cinq"@fr .
              <urn:p1> <urn:age> 11 . <urn:p2> <urn:age> 22 . <urn:p5> <urn:age> 5 . <urn:p7> <urn:age> 77 .
              <urn:p3> <urn:likes> <urn:p3> . <urn:p4> <urn:likes> <urn:p5> . <urn:p7> <urn:likes> <urn:p7> .
              GRAPH <urn:g1> { <urn:p1> <urn:knows> <urn:p9> . <urn:p9> <urn:knows> <urn:p1> . <urn:p9> <urn:name> "Nine" }
              GRAPH <urn:g2> { <urn:p2> <urn:knows> <urn:p8> . <urn:p8> <urn:knows> <urn:p0> }
              <urn:q1> <urn:wants> "2" . <urn:q2> <urn:wants> "2"^^xsd:integer . <urn:t> <urn:val> "2"^^xsd:integer .
            }""";

    private static final List<String> QUERIES = List.of(
            "SELECT * WHERE { ?a <urn:knows> ?b . ?b <urn:name> ?n }",
            "SELECT * WHERE { ?a <urn:knows> ?b . ?b <urn:knows> ?c . ?c <urn:name> ?n }",
            "SELECT * WHERE { GRAPH ?g { ?a <urn:knows> ?b } ?b <urn:name> ?n }",
            "SELECT * WHERE { ?a <urn:knows> ?b . GRAPH <urn:g1> { ?b <urn:knows> ?c } }",
            "SELECT * WHERE { ?a <urn:knows> ?b . GRAPH ?g { ?b <urn:knows> ?c } }",
            "SELECT * WHERE { ?a <urn:knows> ?b . ?b <urn:likes> ?b }",
            "SELECT * WHERE { ?a <urn:knows> ?b . ?b <urn:age> ?age FILTER(?age > 10) }",
            "SELECT * WHERE { ?a <urn:knows> ?b OPTIONAL { ?b <urn:likes> ?l } ?b <urn:name> ?n }",
            "SELECT * WHERE { { ?a <urn:knows> ?b } UNION { ?a <urn:likes> ?b } ?b <urn:name> ?n }",
            "SELECT * FROM <urn:g1> WHERE { ?a <urn:knows> ?b . ?b <urn:knows> ?c }",
            "SELECT ?q ?x WHERE { ?q <urn:wants> ?v . ?x <urn:val> ?v }",
            "SELECT * WHERE { ?a <urn:knows> <urn:p5> . ?a <urn:knows> ?b . ?b <urn:name> ?n }",
            "SELECT (COUNT(*) AS ?n) WHERE { ?a <urn:knows> ?b . ?b <urn:knows> ?c }");

    private final List<SailRepository> repositories = new ArrayList<>();

    @BeforeEach
    public void load() {
        final SailRepository repo = repository(1000, true);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(DATA).execute();
        }
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    @Test
    public void batchedResultsEqualPerRowResults() {
        final SailRepository perRow = repository(1, true);
        final SailRepository batched = repository(1000, true);
        final SailRepository smallChunks = repository(3, true);
        final SailRepository noStats = repository(3, false);
        for (final String query : QUERIES) {
            final Map<Map<String, Value>, Integer> expected = run(perRow, query);
            assertFalse(expected.isEmpty(), "no results for " + query);
            assertEquals(expected, run(batched, query), query);
            assertEquals(expected, run(smallChunks, query), query);
            assertEquals(expected, run(noStats, query), query);
        }
    }

    @Test
    public void plainAndTypedLiteralsDoNotMatchEachOther() {
        // Both left rows bind ?v to a "2": one plain, one typed. Only the typed value is stored under urn:val.
        final Map<Map<String, Value>, Integer> rows =
                run(repository(1000, false), "SELECT ?q ?x WHERE { ?q <urn:wants> ?v . ?x <urn:val> ?v }");
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(1, rows.values().iterator().next());
        assertEquals("urn:q2", rows.keySet().iterator().next().get("q").stringValue());
    }

    @Test
    public void limitAndAskStopEarly() {
        final SailRepository batched = repository(3, true);
        final Map<Map<String, Value>, Integer> limited =
                run(batched, "SELECT * WHERE { ?a <urn:knows> ?b . ?b <urn:name> ?n } LIMIT 2");
        assertEquals(2, limited.values().stream().mapToInt(Integer::intValue).sum());
        try (RepositoryConnection conn = batched.getConnection()) {
            assertTrue(conn.prepareBooleanQuery("ASK { ?a <urn:knows> ?b . ?b <urn:name> \"Five\" }").evaluate());
            assertFalse(conn.prepareBooleanQuery("ASK { ?a <urn:knows> ?b . ?b <urn:name> \"None\" }").evaluate());
        }
    }

    @Test
    public void inferenceJoinsAreBatchedToo() throws Exception {
        final SailRepository perRow = repository(1, true, true);
        try (RepositoryConnection conn = perRow.getConnection()) {
            conn.prepareUpdate("INSERT DATA { <urn:Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <urn:Animal> . "
                    + "<urn:Cat> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <urn:Animal> . "
                    + "<urn:rex> a <urn:Dog> . <urn:tom> a <urn:Cat> . <urn:rex> <urn:name> \"Rex\" . "
                    + "<urn:tom> <urn:name> \"Tom\" }").execute();
        }
        final SailRepository batched = repository(1000, true, true);
        for (final SailRepository repo : List.of(perRow, batched)) {
            ((RdfCloudTripleStore<?>) repo.getSail()).getInferenceEngine().refreshGraph();
        }
        final String query = "SELECT ?x ?n WHERE { ?x a <urn:Animal> . ?x <urn:name> ?n }";
        final Map<Map<String, Value>, Integer> expected = run(perRow, query);
        assertEquals(2, expected.size(), expected.toString());
        assertEquals(expected, run(batched, query));
    }

    @Test
    public void oneBatchReplacesThePerRowLookups() {
        final String query = "SELECT * WHERE { ?a <urn:knows> ?b . ?b <urn:name> ?n }";

        final StoreTripleSource<AccumuloRdfConfiguration> perRow = evaluate(query, 1);
        final StoreTripleSource<AccumuloRdfConfiguration> batched = evaluate(query, 1000);

        assertEquals(0, perRow.getPrefetchCount());
        assertEquals(0, perRow.getCacheHitCount());
        assertTrue(perRow.getStoreLookupCount() > 10, "one lookup per left row: " + perRow.getStoreLookupCount());

        assertEquals(1, batched.getPrefetchCount());
        assertEquals(1, batched.getStoreLookupCount(), "only the left-hand scan goes to the store");
        assertEquals(perRow.getStoreLookupCount() - 1, batched.getCacheHitCount());
    }

    // ------------------------------------------------------------------ helpers

    private SailRepository repository(final int batchSize, final boolean stats) {
        return repository(batchSize, stats, false);
    }

    private SailRepository repository(final int batchSize, final boolean stats, final boolean inference) {
        final AccumuloRdfConfiguration conf = newConf();
        conf.setJoinBatchSize(batchSize);
        conf.setUseStats(stats);
        final SailRepository repo = new SailRepository(newStore(conf, inference));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    private static Map<Map<String, Value>, Integer> run(final SailRepository repo, final String query) {
        final Map<Map<String, Value>, Integer> rows = new HashMap<>();
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
            while (result.hasNext()) {
                rows.merge(asMap(result.next()), 1, Integer::sum);
            }
        }
        return rows;
    }

    private static Map<String, Value> asMap(final BindingSet bs) {
        final Map<String, Value> m = new HashMap<>();
        for (final Binding b : bs) {
            m.put(b.getName(), b.getValue());
        }
        return m;
    }

    /** Runs the query straight through a strategy so the triple source's counters can be read. */
    private StoreTripleSource<AccumuloRdfConfiguration> evaluate(final String query, final int batchSize) {
        final AccumuloRdfConfiguration conf = newConf();
        conf.setJoinBatchSize(batchSize);
        final AccumuloRyaDAO dao = newDao(conf);
        final StoreTripleSource<AccumuloRdfConfiguration> source = new StoreTripleSource<>(conf, dao);
        final RyaDefaultStatistics stats = new RyaDefaultStatistics();
        final RyaEvaluationStrategy strategy = new RyaEvaluationStrategy(source, null, null, stats);
        strategy.setOptimizerPipeline(new RyaQueryOptimizerPipeline(strategy, source, stats,
                new InferenceOptimizer(conf, null)));
        final TupleExpr parsed = new QueryRoot(new SPARQLParser().parseQuery(query, null).getTupleExpr());
        int n = 0;
        try (CloseableIteration<BindingSet> it = strategy.evaluate(
                strategy.optimize(parsed, stats, EmptyBindingSet.getInstance()), EmptyBindingSet.getInstance())) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        assertTrue(n > 5, "expected join results, got " + n);
        dao.destroy();
        return source;
    }
}
