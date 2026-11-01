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
package io.github.dgreco.rya.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO.CARDINALITY_OF;
import io.github.dgreco.rya.test.StoreITBase;

/**
 * The statistics counters add up exactly, on every backend and counter mode: concurrent writers,
 * deletes, a counter with thousands of deltas, flushes, compactions and a drop of everything.
 * On HBase run it with {@code -Drya.test.hbaseCounters=increment} too.
 */
class CounterIT extends StoreITBase {
    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();
    private static final RyaIRI P = new RyaIRI("urn:counted");

    private StoreRdfConfiguration conf;
    private RyaStoreEvalStatsDAO stats;
    private final List<RyaStoreDAO> daos = new ArrayList<>();

    @BeforeEach
    void setUp() {
        conf = new StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        newDao();
        stats = new RyaStoreEvalStatsDAO(getStore(), conf);
        stats.init();
    }

    @AfterEach
    void tearDown() {
        daos.forEach(RyaStoreDAO::destroy);
    }

    private RyaStoreDAO newDao() {
        final RyaStoreDAO dao = new RyaStoreDAO(getStore(), conf);
        dao.init();
        synchronized (daos) {
            daos.add(dao);
        }
        return dao;
    }

    private long count(final CARDINALITY_OF card, final Value... values) {
        return (long) stats.getCardinality(conf, card, List.of(values));
    }

    private static RyaStatement st(final int s, final int o) {
        return new RyaStatement(new RyaIRI("urn:s" + s), P, new RyaType("o" + o));
    }

    @Test
    void concurrentWritersAddUp() throws Exception {
        final int threads = 8;
        final int perThread = 200;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                done.add(pool.submit(() -> {
                    final RyaStoreDAO dao = newDao();
                    for (int i = 0; i < perThread; i++) {
                        dao.add(st(i % 10, id * perThread + i));
                    }
                    // Each thread deletes a tenth of its own statements again.
                    for (int i = 0; i < perThread; i += 10) {
                        dao.delete(st(i % 10, id * perThread + i), conf);
                    }
                    return null;
                }));
            }
            for (final Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * (perThread - perThread / 10), count(CARDINALITY_OF.PREDICATE, VF.createIRI(P.getData())));
        // Subjects rotate over s0..s9, and every deleted statement had subject s0.
        assertEquals(threads * perThread / 10, count(CARDINALITY_OF.SUBJECT, VF.createIRI("urn:s3")));
        assertEquals(0, count(CARDINALITY_OF.SUBJECT, VF.createIRI("urn:s0")));
    }

    @Test
    void aHotCounterSurvivesFlushesCompactionsAndDrops() {
        final RyaStoreDAO dao = daos.get(0);
        final int n = 3000;
        for (int i = 0; i < n; i++) {
            dao.add(st(i % 3, i));
        }
        final Value p = VF.createIRI(P.getData());
        assertEquals(n, count(CARDINALITY_OF.PREDICATE, p));
        backend().compact(conf.getTableLayoutStrategy().getEval());
        assertEquals(n, count(CARDINALITY_OF.PREDICATE, p), "after a flush and a major compaction");
        assertEquals(n / 3, count(CARDINALITY_OF.SUBJECT, VF.createIRI("urn:s1")));
        for (int i = 0; i < 100; i++) {
            dao.add(st(i % 3, n + i));
        }
        assertEquals(n + 100, count(CARDINALITY_OF.PREDICATE, p), "deltas after a compaction count on top of the sum");
        dao.dropGraph(conf);
        assertEquals(-1, count(CARDINALITY_OF.PREDICATE, p), "a drop of everything removes the counters");
        dao.add(st(0, 0));
        dao.add(st(1, 1));
        assertEquals(2, count(CARDINALITY_OF.PREDICATE, p), "counting starts again from zero");
    }
}
