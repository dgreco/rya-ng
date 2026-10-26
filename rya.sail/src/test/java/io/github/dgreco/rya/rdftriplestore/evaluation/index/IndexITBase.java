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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;

import io.github.dgreco.rya.RyaSailITBase;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.index.IndexSearcher;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;

/** Helpers for search tests: a store with both indexes, and on/off comparisons. */
abstract class IndexITBase extends RyaSailITBase {
    static final ValueFactory VF = SimpleValueFactory.getInstance();
    static final String PREFIXES = """
            PREFIX text: <urn:rya-ng:text#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
            PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
            """;

    private final List<SailRepository> repositories = new ArrayList<>();

    @AfterEach
    void shutDownRepositories() {
        repositories.forEach(SailRepository::shutDown);
    }

    /** A configuration with both indexes on. */
    AccumuloRdfConfiguration indexedConf() {
        final AccumuloRdfConfiguration conf = newConf();
        conf.setTextIndex(true);
        conf.setGeoIndex(true);
        return conf;
    }

    SailRepository open(final AccumuloRdfConfiguration conf, final boolean inference) {
        final SailRepository repo = new SailRepository(newStore(conf, inference));
        repo.init();
        repositories.add(repo);
        return repo;
    }

    static void update(final SailRepository repo, final String update) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(PREFIXES + update).execute();
        }
    }

    /** Result rows (as variable → value maps) with their multiplicity. */
    static Map<Map<String, Value>, Integer> run(final SailRepository repo, final String query, final boolean useIndex,
            final String auths) {
        final Map<Map<String, Value>, Integer> rows = new HashMap<>();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TupleQuery q = conn.prepareTupleQuery(PREFIXES + query);
            q.setBinding(RdfCloudTripleStoreConfiguration.CONF_USE_INDEX, VF.createLiteral(useIndex));
            if (auths != null) {
                q.setBinding(RdfCloudTripleStoreConfiguration.CONF_QUERY_AUTH, VF.createLiteral(auths));
            }
            try (TupleQueryResult result = q.evaluate()) {
                while (result.hasNext()) {
                    rows.merge(asMap(result.next()), 1, Integer::sum);
                }
            }
        }
        return rows;
    }

    /**
     * Runs a query with and without the indexes, checks they agree, and checks whether the index
     * was used ({@code null}: either, the plan decides). Returns the rows.
     */
    static Map<Map<String, Value>, Integer> assertAgrees(final SailRepository repo, final String query,
            final Boolean indexUsed, final String auths) {
        final Map<Map<String, Value>, Integer> plain = run(repo, query, false, auths);
        final long before = IndexSearcher.getSearchCount();
        final Map<Map<String, Value>, Integer> indexed = run(repo, query, true, auths);
        final long searches = IndexSearcher.getSearchCount() - before;
        assertEquals(plain, indexed, query);
        if (Boolean.TRUE.equals(indexUsed)) {
            assertTrue(searches > 0, "the index was not used: " + query);
        } else if (Boolean.FALSE.equals(indexUsed)) {
            assertEquals(0, searches, "the index should not be used: " + query);
        }
        return plain;
    }

    static Map<Map<String, Value>, Integer> assertAgrees(final SailRepository repo, final String query,
            final Boolean indexUsed) {
        return assertAgrees(repo, query, indexUsed, null);
    }

    static boolean causedBy(final Throwable e, final Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    static int total(final Map<Map<String, Value>, Integer> rows) {
        return rows.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static Map<String, Value> asMap(final BindingSet bs) {
        final Map<String, Value> m = new HashMap<>();
        for (final Binding b : bs) {
            // Settings passed as bindings (query.useindex, query.auth) are echoed in the rows.
            if (!b.getName().startsWith("query.")) {
                m.put(b.getName(), b.getValue());
            }
        }
        return m;
    }
}
