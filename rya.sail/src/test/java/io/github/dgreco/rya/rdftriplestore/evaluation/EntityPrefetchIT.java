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
package io.github.dgreco.rya.rdftriplestore.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.dgreco.rya.RyaSailITBase;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.domain.StatementMetadata;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceOptimizer;
import io.github.dgreco.rya.store.RyaStoreDAO;
import io.github.dgreco.rya.store.StoreRdfConfiguration;
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
 * Entity prefetch must give exactly the results, in the same order, of the plan without it,
 * and must replace the per-join and per-row lookups with one fetch per window.
 */
public class EntityPrefetchIT extends RyaSailITBase {
    private static final int PEOPLE = 40;

    private static final List<String> QUERIES = List.of(
            // required attributes
            "SELECT * WHERE { ?s a <urn:Person> ; <urn:name> ?n ; <urn:worksFor> ?c }",
            // optional attributes: some missing, some with several values
            "SELECT * WHERE { ?s a <urn:Person> OPTIONAL { ?s <urn:email> ?e } OPTIONAL { ?s <urn:age> ?a } }",
            // both, with a condition inside an OPTIONAL
            "SELECT * WHERE { ?s a <urn:Person> ; <urn:name> ?n OPTIONAL { ?s <urn:age> ?a FILTER(?a > 20) } "
                    + "OPTIONAL { ?s <urn:email> ?e } }",
            // a filtered required attribute stays in the driver
            "SELECT * WHERE { ?s a <urn:Person> ; <urn:age> ?a FILTER(?a > 30) OPTIONAL { ?s <urn:email> ?e } }",
            // attributes of two entities the driver binds
            "SELECT * WHERE { ?s a <urn:Person> ; <urn:worksFor> ?c . ?c <urn:name> ?cn . ?c <urn:city> ?y . ?s <urn:name> ?n }",
            // named graphs
            "SELECT * WHERE { ?s a <urn:Person> OPTIONAL { GRAPH ?g { ?s <urn:nick> ?k } } "
                    + "OPTIONAL { GRAPH <urn:g1> { ?s <urn:nick> ?k2 } } }",
            "SELECT * WHERE { ?s a <urn:Person> OPTIONAL { ?s <urn:nick> ?k } ?s <urn:name> ?n }",
            // a variable bound by an earlier OPTIONAL
            "SELECT * WHERE { ?s a <urn:Person> ; <urn:worksFor> ?c OPTIONAL { ?c <urn:name> ?n } OPTIONAL { ?s <urn:name> ?n } }",
            // the entity on the object side
            "SELECT * WHERE { ?c a <urn:Company> OPTIONAL { ?s <urn:worksFor> ?c } OPTIONAL { ?c <urn:city> ?y } }",
            // every attribute
            "SELECT * WHERE { ?s a <urn:Person> ; <urn:age> ?a OPTIONAL { ?s ?p ?o } }",
            // a link between entities
            "SELECT * WHERE { ?s <urn:knows> ?t OPTIONAL { ?t <urn:email> ?e } OPTIONAL { ?s <urn:email> ?f } }",
            "SELECT (COUNT(*) AS ?n) WHERE { ?s a <urn:Person> OPTIONAL { ?s <urn:email> ?e } OPTIONAL { ?s <urn:age> ?a } }");

    /** Three optional attributes and a required one: four lookups per person. */
    private static final String STAR = "SELECT * WHERE { ?s a <urn:Person> ; <urn:name> ?n "
            + "OPTIONAL { ?s <urn:email> ?e } OPTIONAL { ?s <urn:age> ?a } OPTIONAL { ?s <urn:nick> ?k } }";

    private final List<SailRepository> repositories = new ArrayList<>();

    @BeforeEach
    public void load() {
        try (RepositoryConnection conn = repository(1000, c -> { }).getConnection()) {
            conn.prepareUpdate(data()).execute();
        }
    }

    @AfterEach
    public void shutDown() {
        repositories.forEach(SailRepository::shutDown);
    }

    @Test
    public void resultsEqualThoseWithoutPrefetch() {
        final SailRepository perRow = repository(1, c -> { });
        final SailRepository plain = repository(1000, c -> c.setJoinEntityPrefetch(false));
        final SailRepository prefetch = repository(1000, c -> { });
        final SailRepository smallWindows = repository(3, c -> { });
        final SailRepository tinyLimit = repository(1000, c -> c.setJoinPrefetchLimit(5));
        for (final String query : QUERIES) {
            final List<Map<String, Value>> expected = run(plain, query);
            assertFalse(expected.isEmpty(), "no results for " + query);
            assertEquals(counts(run(perRow, query)), counts(expected), query);
            assertEquals(expected, run(prefetch, query), "same rows in the same order: " + query);
            assertEquals(counts(expected), counts(run(smallWindows, query)), query);
            assertEquals(counts(expected), counts(run(tinyLimit, query)), query);
        }
    }

    @Test
    public void oneFetchServesAWholeWindow() {
        final StoreTripleSource<StoreRdfConfiguration> plain = evaluate(STAR, c -> c.setJoinEntityPrefetch(false));
        final StoreTripleSource<StoreRdfConfiguration> prefetch = evaluate(STAR, c -> { });

        assertTrue(plain.getStoreLookupCount() > 3 * PEOPLE,
                "each OPTIONAL looks up one row at a time: " + plain.getStoreLookupCount());

        assertEquals(1, prefetch.getStoreLookupCount(), "only the driver's scan goes to the store");
        assertEquals(1, prefetch.getPrefetchCount());
        assertEquals(0, prefetch.getPrefetchOverflowCount());
    }

    @Test
    public void windowsTooLargeToHoldAreHalved() {
        final StoreTripleSource<StoreRdfConfiguration> limited = evaluate(STAR, c -> c.setJoinPrefetchLimit(10));
        assertTrue(limited.getPrefetchOverflowCount() > 0, "the first window holds more than 10 statements");
        assertTrue(limited.getPrefetchCount() > 1, "smaller windows are still fetched together");
    }

    @Test
    public void limitAndAskStopEarly() {
        final SailRepository repo = repository(1000, c -> { });
        assertEquals(3, run(repo, "SELECT * WHERE { ?s a <urn:Person> OPTIONAL { ?s <urn:email> ?e } } LIMIT 3").size());
        try (RepositoryConnection conn = repo.getConnection()) {
            assertTrue(conn.prepareBooleanQuery(
                    "ASK { ?s a <urn:Person> OPTIONAL { ?s <urn:email> ?e } FILTER(?e = \"p8@x\") }").evaluate());
            assertFalse(conn.prepareBooleanQuery(
                    "ASK { ?s a <urn:Person> OPTIONAL { ?s <urn:email> ?e } FILTER(?e = \"none@x\") }").evaluate());
        }
    }

    @Test
    public void labelsStillHideAttributes() {
        grantAuthorizations("A", "B");
        final RyaStoreDAO dao = newDao(newConf());
        final RyaIRI secret = new RyaIRI("urn:secret");
        for (int i = 0; i < PEOPLE; i++) {
            dao.add(new RyaStatement(new RyaIRI("urn:p" + i), secret, new RyaType("s" + i), null, null,
                    new StatementMetadata(), (i % 2 == 0 ? "A" : "B").getBytes(StandardCharsets.UTF_8)));
        }
        dao.destroy();

        final String query = "SELECT * WHERE { ?s a <urn:Person> OPTIONAL { ?s <urn:secret> ?x } }";
        final List<Map<String, Value>> expected = run(repository(1, c -> c.setAuths("A")), query);
        final List<Map<String, Value>> prefetched = run(repository(1000, c -> c.setAuths("A")), query);
        assertEquals(counts(expected), counts(prefetched));
        final List<Value> secrets = prefetched.stream().map(row -> row.get("x")).filter(v -> v != null).toList();
        assertEquals(PEOPLE / 2, secrets.size(), secrets.toString());
        for (final Value v : secrets) {
            assertEquals(0, Integer.parseInt(v.stringValue().substring(1)) % 2, "label B is hidden: " + v);
        }
    }

    @Test
    public void inferredTypesDriveAChain() throws Exception {
        final SailRepository perRow = repository(1, true, c -> { });
        final SailRepository prefetch = repository(1000, true, c -> { });
        try (RepositoryConnection conn = perRow.getConnection()) {
            conn.prepareUpdate("INSERT DATA { <urn:Manager> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <urn:Person> . "
                    + "<urn:m1> a <urn:Manager> ; <urn:name> \"M1\" ; <urn:email> \"m1@x\" }").execute();
        }
        for (final SailRepository repo : List.of(perRow, prefetch)) {
            ((RdfCloudTripleStore<?>) repo.getSail()).getInferenceEngine().refreshGraph();
        }
        final String query = "SELECT * WHERE { ?s a <urn:Person> ; <urn:name> ?n OPTIONAL { ?s <urn:email> ?e } }";
        final List<Map<String, Value>> expected = run(perRow, query);
        assertTrue(expected.stream().anyMatch(row -> "urn:m1".equals(row.get("s").stringValue())), expected.toString());
        assertEquals(counts(expected), counts(run(prefetch, query)));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * People with a name (some with two), an employer, and optional age, emails and a nickname
     * in a named graph; companies with a name and a city.
     */
    private static String data() {
        final StringBuilder b = new StringBuilder("INSERT DATA {\n");
        for (int j = 0; j < 3; j++) {
            b.append("<urn:c").append(j).append("> a <urn:Company> ; <urn:name> \"C").append(j)
                    .append("\" ; <urn:city> \"City").append(j).append("\" .\n");
        }
        for (int i = 0; i < PEOPLE; i++) {
            final String p = "<urn:p" + i + ">";
            b.append(p).append(" a <urn:Person> ; <urn:name> \"P").append(i).append("\" ; <urn:worksFor> <urn:c")
                    .append(i % 3).append("> .\n");
            if (i % 7 == 0) {
                b.append(p).append(" <urn:name> \"Alt").append(i).append("\"@en .\n");
            }
            if (i % 3 != 0) {
                b.append(p).append(" <urn:age> ").append(i).append(" .\n");
            }
            if (i % 4 == 0) {
                b.append(p).append(" <urn:email> \"p").append(i).append("@x\" .\n");
            }
            if (i % 8 == 0) {
                b.append(p).append(" <urn:email> \"other").append(i).append("@x\" .\n");
            }
            if (i % 5 == 0) {
                b.append("GRAPH <urn:g1> { ").append(p).append(" <urn:nick> \"n").append(i).append("\" }\n");
            }
            if (i % 6 == 0) {
                b.append(p).append(" <urn:knows> <urn:p").append((i + 1) % PEOPLE).append("> .\n");
            }
        }
        return b.append("}").toString();
    }

    private SailRepository repository(final int batchSize, final Consumer<StoreRdfConfiguration> settings) {
        return repository(batchSize, false, settings);
    }

    private SailRepository repository(final int batchSize, final boolean inference,
            final Consumer<StoreRdfConfiguration> settings) {
        final StoreRdfConfiguration conf = newConf();
        conf.setJoinBatchSize(batchSize);
        settings.accept(conf);
        final SailRepository repo = new SailRepository(newStore(conf, inference));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    private static List<Map<String, Value>> run(final SailRepository repo, final String query) {
        final List<Map<String, Value>> rows = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
            while (result.hasNext()) {
                final Map<String, Value> row = new HashMap<>();
                for (final Binding b : result.next()) {
                    row.put(b.getName(), b.getValue());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<Map<String, Value>, Integer> counts(final List<Map<String, Value>> rows) {
        final Map<Map<String, Value>, Integer> counts = new HashMap<>();
        rows.forEach(row -> counts.merge(row, 1, Integer::sum));
        return counts;
    }

    /** Runs the query straight through a strategy so the triple source's counters can be read. */
    private StoreTripleSource<StoreRdfConfiguration> evaluate(final String query,
            final Consumer<StoreRdfConfiguration> settings) {
        final StoreRdfConfiguration conf = newConf();
        conf.setJoinBatchSize(1000);
        settings.accept(conf);
        final RyaStoreDAO dao = newDao(conf);
        final StoreTripleSource<StoreRdfConfiguration> source = new StoreTripleSource<>(conf, dao);
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
        assertTrue(n >= PEOPLE, "expected a row per person at least, got " + n);
        dao.destroy();
        return source;
    }
}
