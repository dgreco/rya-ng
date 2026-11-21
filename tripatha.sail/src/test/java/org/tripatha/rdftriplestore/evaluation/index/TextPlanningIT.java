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
package org.tripatha.rdftriplestore.evaluation.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.index.IndexVerifier;
import org.tripatha.store.index.TextCounts;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

/**
 * An {@code AND} of two words with more postings than a probe reads (SCALE-STUDY.md O6): the
 * posting counts make the rarer one drive, whichever comes first in the query. Without complete
 * counts, every large word looks the same and the first one drives, as before.
 */
public class TextPlanningIT extends IndexITBase {
    /** Both words have more postings than the probe's limit (1,000). */
    private static final int COMMON = 3000;
    private static final int RARE = 1200;
    private static final String QUERY = "SELECT ?d WHERE { ?d rdfs:label ?l FILTER(text:matches(?l, \"common rare\")) }";

    @BeforeEach
    public void load() {
        final List<RyaStatement> statements = new ArrayList<>();
        for (int i = 0; i < COMMON; i++) {
            final String label = (i < RARE ? "rare " : "") + "common item" + i;
            statements.add(new RyaStatement(new RyaIRI("urn:doc" + i), new RyaIRI(RDFS.LABEL.stringValue()), new RyaType(label)));
        }
        final RyaStoreDAO dao = newDao(indexedConf());
        try {
            dao.add(statements.iterator());
        } finally {
            dao.destroy();
        }
    }

    @Test
    public void theRarerOfTwoCommonWordsDrives() throws Exception {
        final SailRepository repo = open(indexedConf(), false);
        final List<String> streamed = new ArrayList<>();
        final Map<Map<String, Value>, Integer> rows = traced(repo, streamed);
        assertEquals(RARE, rows.size());
        assertTrue(streamed.stream().anyMatch(r -> r.contains("the word 'rare'")), streamed.toString());
        assertFalse(streamed.stream().anyMatch(r -> r.contains("the word 'common'")), streamed.toString());
        assertEquals(rows, assertAgrees(repo, QUERY, true, null), "the same rows without the index");

        final IndexVerifier.Report counts = IndexVerifier.verifyTextCounts(getStore(), indexedConf());
        assertNotNull(counts, "the counts are complete for a store created with the index");
        assertTrue(counts.isClean(), counts.toString());
    }

    @Test
    public void countsFollowDeletesAndADropOfEverything() throws Exception {
        final SailRepository repo = open(indexedConf(), false);
        update(repo, "DELETE { ?d rdfs:label ?l } WHERE { ?d rdfs:label ?l FILTER(STRSTARTS(STR(?d), \"urn:doc1\")) }");
        IndexVerifier.Report counts = IndexVerifier.verifyTextCounts(getStore(), indexedConf());
        assertTrue(counts.isClean(), "after deletes: " + counts);
        update(repo, "CLEAR ALL");
        counts = IndexVerifier.verifyTextCounts(getStore(), indexedConf());
        assertTrue(counts.isClean(), "after dropping everything: " + counts);
    }

    /** A store whose counts aren't known complete (one from before them) plans as before: the first large word drives. */
    @Test
    public void withoutCompleteCountsTheFirstLargeWordDrives() throws Exception {
        final StoreRdfConfiguration conf = indexedConf();
        try (CellWriter writer = getStore().newWriter(WriterOptions.DEFAULT)) {
            writer.table(conf.getTableLayoutStrategy().getNs()).write(new RowMutation("tripatha".getBytes(StandardCharsets.UTF_8))
                    .delete("index".getBytes(StandardCharsets.UTF_8), TextCounts.FLAG.getBytes(StandardCharsets.UTF_8), new byte[0]));
            writer.flush();
        }
        final SailRepository repo = open(conf, false);
        final List<String> streamed = new ArrayList<>();
        assertEquals(RARE, traced(repo, streamed).size());
        assertTrue(streamed.stream().anyMatch(r -> r.contains("the word 'common'")), streamed.toString());
    }

    /** {@code query.textcounts=false} plans as without counts: the first large word drives. */
    @Test
    public void theCountsCanBeTurnedOff() {
        final StoreRdfConfiguration conf = indexedConf();
        conf.setTextCounts(false);
        final List<String> streamed = new ArrayList<>();
        assertEquals(RARE, traced(open(conf, false), streamed).size());
        assertTrue(streamed.stream().anyMatch(r -> r.contains("the word 'common'")), streamed.toString());
    }

    /**
     * Accumulo grants each table on its own: an endpoint account given every table but the counts
     * (as before them) still searches, and plans as without them.
     */
    @Test
    public void anAccountThatMayNotReadTheCountsPlansWithoutThem() throws Exception {
        assumeBackend("accumulo");
        final String user = "reader" + getTablePrefix().replace("_", "");
        final String password = "text-planning-it";
        try (org.apache.accumulo.core.client.AccumuloClient root = org.tripatha.test.accumulo.AccumuloTestBackend.newClient()) {
            root.securityOperations().createLocalUser(user, new org.apache.accumulo.core.client.security.tokens.PasswordToken(password));
            try {
                final StoreRdfConfiguration conf = indexedConf();
                for (final String table : root.tableOperations().list()) {
                    if (table.startsWith(getTablePrefix()) && !table.equals(conf.getTableLayoutStrategy().getTextCount())) {
                        root.securityOperations().grantTablePermission(user, table,
                                org.apache.accumulo.core.security.TablePermission.READ);
                    }
                }
                final java.util.Properties settings = new java.util.Properties();
                final var cluster = org.tripatha.test.accumulo.MiniAccumuloSingleton.getCluster();
                settings.setProperty(org.tripatha.accumulo.AccumuloStoreProvider.INSTANCE, cluster.getInstanceName());
                settings.setProperty(org.tripatha.accumulo.AccumuloStoreProvider.ZOOKEEPERS, cluster.getZooKeepers());
                settings.setProperty(org.tripatha.accumulo.AccumuloStoreProvider.USER, user);
                settings.setProperty(org.tripatha.accumulo.AccumuloStoreProvider.PASSWORD, password);
                try (org.tripatha.store.spi.Store restricted = org.tripatha.store.spi.Stores.open("accumulo", settings)) {
                    final SailRepository repo = org.tripatha.rdftriplestore.RyaSailFactory.createRepository(restricted, conf);
                    repo.init();
                    try {
                        final List<String> streamed = new ArrayList<>();
                        assertEquals(RARE, traced(repo, streamed).size());
                        assertTrue(streamed.stream().anyMatch(r -> r.contains("the word 'common'")), streamed.toString());
                    } finally {
                        repo.shutDown();
                    }
                }
            } finally {
                root.securityOperations().dropLocalUser(user);
            }
        }
    }

    /** The rows of the query, and the reasons of the scans that streamed a word. */
    private static Map<Map<String, Value>, Integer> traced(final SailRepository repo, final List<String> streamed) {
        final List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        final Map<Map<String, Value>, Integer> rows;
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart scan && scan.reason() != null) {
                reasons.add(scan.reason());
            }
        })) {
            rows = run(repo, QUERY, true, null);
        }
        synchronized (reasons) {
            reasons.stream().filter(r -> r.contains("text index: stream ")).forEach(streamed::add);
        }
        return rows;
    }
}
