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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.dgreco.rya.RyaSailITBase;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.domain.StatementMetadata;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * For every key, a batch must return exactly what a single lookup of that key returns, even when
 * several keys share scan ranges.
 */
public class BatchLookupIT extends RyaSailITBase {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI VAL = VF.createIRI("urn:val");
    private static final IRI P = VF.createIRI("urn:p");
    private static final IRI S = VF.createIRI("urn:s");
    private static final IRI V = VF.createIRI("urn:v");
    private static final IRI G = VF.createIRI("urn:g");
    private static final IRI G1 = VF.createIRI("urn:G1");

    private AccumuloRdfConfiguration conf;
    private AccumuloRyaDAO dao;

    @BeforeEach
    public void load() throws Exception {
        grantAuthorizations("A", "B");
        conf = newConf();
        conf.setAuths("A", "B");
        dao = newDao(conf);
        final RyaIRI p = new RyaIRI("urn:p");
        // Only a custom-typed "2": its range is the same as a plain "2" key's range.
        dao.add(new RyaStatement(new RyaIRI("urn:s1"), new RyaIRI("urn:val"), new RyaType(VF.createIRI("urn:dt"), "2")));
        // Two objects under one subject/predicate: (s,p,*) and (s,p,o) overlap.
        dao.add(new RyaStatement(new RyaIRI("urn:s"), p, new RyaIRI("urn:o")));
        dao.add(new RyaStatement(new RyaIRI("urn:s"), p, new RyaIRI("urn:o2")));
        // The same triple under two visibility labels: two stored cells.
        dao.add(new RyaStatement(new RyaIRI("urn:v"), p, new RyaIRI("urn:o"), null, null, new StatementMetadata(),
                "A".getBytes(StandardCharsets.UTF_8)));
        dao.add(new RyaStatement(new RyaIRI("urn:v"), p, new RyaIRI("urn:o"), null, null, new StatementMetadata(),
                "B".getBytes(StandardCharsets.UTF_8)));
        // The same triple in the default graph and in a named graph.
        dao.add(new RyaStatement(new RyaIRI("urn:g"), p, new RyaIRI("urn:o")));
        dao.add(new RyaStatement(new RyaIRI("urn:g"), p, new RyaIRI("urn:o"), new RyaIRI("urn:G1")));
    }

    @AfterEach
    public void close() {
        dao.destroy();
    }

    @Test
    public void sameRangeDifferentType() {
        final LookupKey plain = LookupKey.of(null, VAL, VF.createLiteral("2"));
        final LookupKey typed = LookupKey.of(null, VAL, VF.createLiteral("2", VF.createIRI("urn:dt")));
        final Map<LookupKey, List<Statement>> batch = fetch(plain, typed);
        assertEquals(0, batch.get(plain).size());
        assertEquals(1, batch.get(typed).size());
        assertMatchesSingleLookups(batch);
    }

    @Test
    public void overlappingRangesCountEachCellOnce() {
        final LookupKey all = LookupKey.of(S, P, null);
        final LookupKey one = LookupKey.of(S, P, VF.createIRI("urn:o"));
        final Map<LookupKey, List<Statement>> batch = fetch(all, one);
        assertEquals(2, batch.get(all).size());
        assertEquals(1, batch.get(one).size());
        assertMatchesSingleLookups(batch);
    }

    @Test
    public void oneTripleUnderTwoVisibilities() {
        final LookupKey key = LookupKey.of(V, P, null);
        final LookupKey other = LookupKey.of(S, P, null);
        final Map<LookupKey, List<Statement>> batch = fetch(key, other);
        assertEquals(2, batch.get(key).size());
        assertMatchesSingleLookups(batch);
    }

    @Test
    public void graphConditions() {
        final LookupKey anyGraph = LookupKey.of(G, P, null);
        final LookupKey defaultGraph = LookupKey.of(G, P, null, (Resource) null);
        final LookupKey named = LookupKey.of(G, P, null, G1);
        final Map<LookupKey, List<Statement>> batch = fetch(anyGraph, defaultGraph, named);
        assertEquals(2, batch.get(anyGraph).size());
        assertEquals(1, batch.get(defaultGraph).size());
        assertEquals(1, batch.get(named).size());
        assertMatchesSingleLookups(batch);
    }

    private Map<LookupKey, List<Statement>> fetch(final LookupKey... keys) {
        final Map<LookupKey, List<Statement>> batch = BatchLookup.fetch(dao, conf, List.of(keys), 1000);
        assertNotNull(batch);
        return batch;
    }

    /** Each key's batch result equals, as a multiset, what a lookup without any prefetch returns. */
    private void assertMatchesSingleLookups(final Map<LookupKey, List<Statement>> batch) {
        final StoreTripleSource<AccumuloRdfConfiguration> source = new StoreTripleSource<>(conf, dao);
        for (final Map.Entry<LookupKey, List<Statement>> e : batch.entrySet()) {
            final LookupKey k = e.getKey();
            final Map<Statement, Integer> single = new HashMap<>();
            try (CloseableIteration<? extends Statement> it = source.getStatements(k.subject(), k.predicate(), k.object(),
                    k.contexts().toArray(new Resource[0]))) {
                while (it.hasNext()) {
                    single.merge(it.next(), 1, Integer::sum);
                }
            }
            final Map<Statement, Integer> batched = new HashMap<>();
            e.getValue().forEach(st -> batched.merge(st, 1, Integer::sum));
            assertEquals(single, batched, k.toString());
        }
    }
}
