package org.tripatha.rdftriplestore.evaluation;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.tripatha.RyaSailITBase;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.domain.StatementMetadata;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

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

    private StoreRdfConfiguration conf;
    private RyaStoreDAO dao;

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

    /**
     * Keys of one shape that leave the object free, an entity chain's links, read disjoint ranges:
     * the batch skips its check for cells it has seen, and still answers as single lookups do,
     * labels and graphs included.
     */
    @Test
    public void keysOfOneShapeWithoutAnObjectAnswerAsSingleLookups() {
        dao.add(new RyaStatement(new RyaIRI("urn:s"), new RyaIRI("urn:q"), new RyaType("x")));
        dao.add(new RyaStatement(new RyaIRI("urn:s"), new RyaIRI("urn:q"), new RyaType("y"), new RyaIRI("urn:G1")));
        dao.add(new RyaStatement(new RyaIRI("urn:s"), new RyaIRI("urn:pq"), new RyaType("z")));
        final IRI q = VF.createIRI("urn:q");
        final IRI pq = VF.createIRI("urn:pq");
        final List<LookupKey> keys = List.of(LookupKey.of(S, P, null), LookupKey.of(S, q, null), LookupKey.of(S, pq, null),
                LookupKey.of(V, P, null), LookupKey.of(G, P, null), LookupKey.of(G, q, null));
        final Map<LookupKey, List<Statement>> batch = BatchLookup.fetch(dao, conf, keys, 1000);
        assertNotNull(batch);
        assertEquals(2, batch.get(keys.get(0)).size(), "urn:o and urn:o2");
        assertEquals(2, batch.get(keys.get(1)).size(), "x, and y in urn:G1");
        assertEquals(1, batch.get(keys.get(2)).size(), "urn:pq is not urn:p's prefix range");
        assertEquals(2, batch.get(keys.get(3)).size(), "one triple under the labels A and B");
        assertEquals(2, batch.get(keys.get(4)).size(), "the default graph and urn:G1");
        assertEquals(0, batch.get(keys.get(5)).size());
        assertMatchesSingleLookups(batch);
    }

    /** Subjects urn:t0 … urn:t8, each with urn:p and urn:q: two of them under the labels A and B, one in urn:G1. */
    private List<LookupKey> manySubjects() {
        final List<RyaStatement> statements = new ArrayList<>();
        final List<LookupKey> keys = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            final RyaIRI t = new RyaIRI("urn:t" + i);
            statements.add(new RyaStatement(t, new RyaIRI("urn:p"), new RyaIRI("urn:o" + i)));
            statements.add(new RyaStatement(t, new RyaIRI("urn:q"), new RyaType("v" + i)));
            keys.add(LookupKey.of(VF.createIRI("urn:t" + i), P, null));
            keys.add(LookupKey.of(VF.createIRI("urn:t" + i), VF.createIRI("urn:q"), null));
        }
        statements.add(new RyaStatement(new RyaIRI("urn:t3"), new RyaIRI("urn:q"), new RyaType("w"), null, null, new StatementMetadata(),
                "A".getBytes(StandardCharsets.UTF_8)));
        statements.add(new RyaStatement(new RyaIRI("urn:t3"), new RyaIRI("urn:q"), new RyaType("w"), null, null, new StatementMetadata(),
                "B".getBytes(StandardCharsets.UTF_8)));
        statements.add(new RyaStatement(new RyaIRI("urn:t7"), new RyaIRI("urn:p"), new RyaIRI("urn:x"), new RyaIRI("urn:G1")));
        dao.add(statements.iterator());
        return keys;
    }

    /**
     * A disjoint batch is read on several threads (O16): one scan per chunk, the later ones
     * labelled as parts, and the same answers as one thread and as single lookups.
     */
    @Test
    public void aDisjointBatchIsReadOnSeveralThreads() {
        final List<LookupKey> keys = manySubjects();
        conf.setJoinLookupThreads(1);
        final Map<LookupKey, List<Statement>> oneThread = BatchLookup.fetch(dao, conf, keys, 1000);
        conf.setJoinLookupThreads(3);
        conf.setJoinLookupThreadKeys(1);
        final List<Integer> ranges = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final Map<LookupKey, List<Statement>> threads;
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan) {
                synchronized (ranges) {
                    ranges.add(scan.rangeCount());
                    reasons.add(scan.reason());
                }
            }
        })) {
            threads = BatchLookup.fetch(dao, conf, keys, 1000);
        }
        assertNotNull(threads);
        assertEquals(List.of(6, 6, 6), ranges.stream().sorted().toList(), "18 lookups in three chunks: " + ranges);
        assertTrue(reasons.stream().anyMatch(r -> r != null && r.contains("part 2 of 3")), reasons.toString());
        assertEquals(oneThread, threads);
        assertEquals(3, threads.get(LookupKey.of(VF.createIRI("urn:t3"), VF.createIRI("urn:q"), null)).size(), "v3, and w under A and B");
        assertEquals(2, threads.get(LookupKey.of(VF.createIRI("urn:t7"), P, null)).size(), "the default graph and urn:G1");
        assertMatchesSingleLookups(threads);
    }

    /** The limit counts the statements of every chunk together. */
    @Test
    public void theLimitHoldsAcrossThreads() {
        final List<LookupKey> keys = manySubjects();
        conf.setJoinLookupThreads(3);
        conf.setJoinLookupThreadKeys(1);
        assertNotNull(BatchLookup.fetch(dao, conf, keys, 1000));
        assertEquals(null, BatchLookup.fetch(dao, conf, keys, 10), "21 statements, over 10 in all though under 10 per chunk");
    }

    /** A batch whose ranges may overlap stays one scan, on this thread, whatever the setting. */
    @Test
    public void aBatchWhoseRangesMayOverlapIsReadOnce() {
        conf.setJoinLookupThreads(3);
        conf.setJoinLookupThreadKeys(1);
        final List<LookupKey> keys = List.of(LookupKey.of(S, P, null), LookupKey.of(S, P, VF.createIRI("urn:o")), LookupKey.of(V, P, null),
                LookupKey.of(G, P, null));
        final List<Integer> ranges = new ArrayList<>();
        final Map<LookupKey, List<Statement>> batch = traced(ranges, new ArrayList<>(), () -> BatchLookup.fetch(dao, conf, keys, 1000));
        assertEquals(1, ranges.size(), ranges.toString());
        assertMatchesSingleLookups(batch);
    }

    /** With subject ranges on, a subject asked for by several keys is read as one range; the answers stay those of single lookups. */
    @Test
    public void aSubjectAskedForSeveralTimesIsReadOnce() {
        conf.setJoinSubjectRanges(true);
        dao.add(new RyaStatement(new RyaIRI("urn:s"), new RyaIRI("urn:q"), new RyaType("x")));
        dao.add(new RyaStatement(new RyaIRI("urn:s"), new RyaIRI("urn:r"), new RyaType("y")));
        final List<LookupKey> keys = List.of(LookupKey.of(S, P, null), LookupKey.of(S, VF.createIRI("urn:q"), null),
                LookupKey.of(S, P, VF.createIRI("urn:o")), LookupKey.of(S, VF.createIRI("urn:r"), null), LookupKey.of(V, P, null));
        final List<Integer> ranges = new ArrayList<>();
        final Map<LookupKey, List<Statement>> batch = traced(ranges, new ArrayList<>(), () -> BatchLookup.fetch(dao, conf, keys, 1000));
        assertEquals(2, ranges.stream().mapToInt(Integer::intValue).sum(), "one range for urn:s, one for urn:v: " + ranges);
        assertEquals(2, batch.get(keys.get(0)).size());
        assertEquals(1, batch.get(keys.get(1)).size());
        assertMatchesSingleLookups(batch);

        conf.setJoinSubjectRanges(false);
        final List<Integer> perKey = new ArrayList<>();
        final Map<LookupKey, List<Statement>> plain = traced(perKey, new ArrayList<>(), () -> BatchLookup.fetch(dao, conf, keys, 1000));
        assertEquals(5, perKey.stream().mapToInt(Integer::intValue).sum(), perKey.toString());
        assertEquals(batch, plain);
    }

    /** A subject with many statements makes the subject range too costly: the batch is read one range per key. */
    @Test
    public void aSubjectWithManyStatementsIsReadRangeByRange() {
        conf.setJoinSubjectRanges(true);
        final RyaIRI hub = new RyaIRI("urn:hub");
        final List<RyaStatement> many = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            many.add(new RyaStatement(hub, new RyaIRI("urn:many"), new RyaType("v" + i)));
        }
        many.add(new RyaStatement(hub, new RyaIRI("urn:name"), new RyaType("Hub")));
        many.add(new RyaStatement(hub, new RyaIRI("urn:age"), new RyaType("5")));
        dao.add(many.iterator());
        final IRI h = VF.createIRI("urn:hub");
        final List<LookupKey> keys = List.of(LookupKey.of(h, VF.createIRI("urn:name"), null), LookupKey.of(h, VF.createIRI("urn:age"), null));
        final List<Integer> ranges = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final Map<LookupKey, List<Statement>> batch = traced(ranges, notes, () -> BatchLookup.fetch(dao, conf, keys, 1000));
        assertEquals(List.of(1, 1, 1), ranges, "the subject range, then one range per key");
        assertTrue(notes.stream().anyMatch(n -> n.contains("one range per lookup")), notes.toString());
        assertEquals(1, batch.get(keys.get(0)).size());
        assertEquals(1, batch.get(keys.get(1)).size());
        assertMatchesSingleLookups(batch);
    }

    private static Map<LookupKey, List<Statement>> traced(final List<Integer> ranges, final List<String> notes,
            final java.util.function.Supplier<Map<LookupKey, List<Statement>>> fetch) {
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan) {
                ranges.add(scan.rangeCount());
            } else if (event instanceof TraceEvent.Note note) {
                notes.add(note.text());
            }
        })) {
            final Map<LookupKey, List<Statement>> batch = fetch.get();
            assertNotNull(batch);
            return batch;
        }
    }

    private Map<LookupKey, List<Statement>> fetch(final LookupKey... keys) {
        final Map<LookupKey, List<Statement>> batch = BatchLookup.fetch(dao, conf, List.of(keys), 1000);
        assertNotNull(batch);
        return batch;
    }

    /** Each key's batch result equals, as a multiset, what a lookup without any prefetch returns. */
    private void assertMatchesSingleLookups(final Map<LookupKey, List<Statement>> batch) {
        final StoreTripleSource<StoreRdfConfiguration> source = new StoreTripleSource<>(conf, dao);
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
