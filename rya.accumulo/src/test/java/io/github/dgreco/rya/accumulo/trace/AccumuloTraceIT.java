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
package io.github.dgreco.rya.accumulo.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.security.Authorizations;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.test.accumulo.AccumuloITBase;

public class AccumuloTraceIT extends AccumuloITBase {
    private static final String NS = "urn:test#";
    private static final RyaIRI ALICE = new RyaIRI(NS + "alice");
    private static final RyaIRI BOB = new RyaIRI(NS + "bob");
    private static final RyaIRI CAROL = new RyaIRI(NS + "carol");
    private static final RyaIRI KNOWS = new RyaIRI(NS + "knows");
    private static final RyaIRI G1 = new RyaIRI(NS + "g1");

    private AccumuloClient client;
    private AccumuloRdfConfiguration conf;
    private AccumuloRyaDAO dao;
    private final List<TraceEvent> events = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        client = AccumuloTrace.wrap(getClient());
        conf = new AccumuloRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        dao = new AccumuloRyaDAO(client, conf);
        dao.init();
        dao.add(new RyaStatement(ALICE, KNOWS, BOB));
        dao.add(new RyaStatement(ALICE, KNOWS, CAROL));
        dao.add(new RyaStatement(BOB, KNOWS, ALICE));
    }

    @AfterEach
    public void tearDown() {
        dao.destroy();
    }

    @Test
    public void aLookupReportsItsTableRangeReasonAndEntries() {
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add)) {
            assertEquals(2, count(new RyaStatement(ALICE, KNOWS, null)));
        }
        final TraceEvent.ScanStart start = only(TraceEvent.ScanStart.class);
        assertEquals("Scanner", start.op());
        assertEquals(getTablePrefix() + "spo", start.table());
        assertEquals("lookup <urn:test#alice> <urn:test#knows> ? on SPO", start.reason());
        assertEquals(List.of("[urn:test#alice⟨00⟩urn:test#knows⟨00⟩, urn:test#alice⟨00⟩urn:test#knows⟨00⟩⟨ff⟩]"), start.ranges());
        final TraceEvent.ScanEnd end = only(TraceEvent.ScanEnd.class);
        assertEquals(start.id(), end.id());
        assertEquals(2, end.entries());
        assertEquals("exhausted", end.how());
        final TraceEvent.Summary summary = only(TraceEvent.Summary.class);
        assertEquals(1, summary.scans());
        assertEquals(2, summary.entries());
    }

    @Test
    public void reasonsNestAndAnObjectLookupShowsItsTypeCheck() {
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add);
                AccumuloTrace.Reason outer = AccumuloTrace.reason(() -> "outer step")) {
            assertEquals(1, count(new RyaStatement(null, KNOWS, BOB)));
        }
        final TraceEvent.ScanStart start = only(TraceEvent.ScanStart.class);
        assertEquals(getTablePrefix() + "po", start.table());
        assertTrue(start.reason().startsWith("outer step › lookup ? <urn:test#knows> <urn:test#bob> on PO"), start.reason());
        assertTrue(start.iterators().stream().anyMatch(i -> i.startsWith("11 ri (RegExFilter)")), start.iterators().toString());
    }

    @Test
    public void aLongScanReportsWhereItIsWhileItRuns() throws Exception {
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add);
                CloseableIteration<RyaStatement> it = dao.getQueryEngine().query(new RyaStatement(null, KNOWS, null), conf)) {
            while (it.hasNext()) {
                it.next();
                Thread.sleep(900);
            }
        }
        final List<TraceEvent.Progress> progress = events.stream().filter(e -> e instanceof TraceEvent.Progress)
                .map(e -> (TraceEvent.Progress) e).toList();
        assertTrue(!progress.isEmpty(), events.toString());
        final TraceEvent.ScanProgress last = progress.get(progress.size() - 1).scans().get(0);
        assertEquals(only(TraceEvent.ScanStart.class).id(), last.id());
        assertTrue(last.entries() >= 1, last.toString());
        assertTrue(last.position().startsWith("urn:test#knows⟨00⟩"), last.position());
        // Progress stops with the trace.
        final int seen = events.size();
        Thread.sleep(2 * Trace.PROGRESS_INTERVAL_MS);
        assertEquals(seen, events.size());
    }

    @Test
    public void aBatchedLookupListsTheFirstRangesAndCountsThemAll() {
        final List<RyaStatement> patterns = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            patterns.add(new RyaStatement(new RyaIRI(NS + "s" + i), KNOWS, null));
        }
        patterns.add(new RyaStatement(ALICE, KNOWS, null));
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add);
                CloseableIteration<RyaStatement> it = dao.getQueryEngine().batchQuery(patterns, conf)) {
            int n = 0;
            while (it.hasNext()) {
                it.next();
                n++;
            }
            assertEquals(2, n);
        }
        final TraceEvent.ScanStart start = only(TraceEvent.ScanStart.class);
        assertEquals("BatchScanner", start.op());
        assertEquals(16, start.rangeCount());
        assertEquals(Trace.MAX_RANGES, start.ranges().size());
        assertEquals("batched lookup of 16 patterns on SPO", start.reason());
    }

    @Test
    public void writesAreReportedPerTableAtEachFlush() {
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add)) {
            dao.add(new RyaStatement(CAROL, KNOWS, ALICE, G1));
        }
        final TraceEvent.Flush flush = only(TraceEvent.Flush.class);
        final Map<String, TraceEvent.TableWrites> byTable = flush.tables().stream()
                .collect(Collectors.toMap(TraceEvent.TableWrites::table, Function.identity()));
        for (final String t : List.of("spo", "po", "osp", "graphs")) {
            assertEquals(1, byTable.get(getTablePrefix() + t).mutations(), t);
        }
        // Six counters for all graphs and six for g1, in one mutation per counter row.
        assertEquals(12, byTable.get(getTablePrefix() + "eval").cells());
        assertEquals(flush.tables().stream().mapToLong(TraceEvent.TableWrites::mutations).sum(),
                only(TraceEvent.Summary.class).mutations());
    }

    @Test
    public void deletesShowTheirLookupAndTheirDeleteCells() {
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add)) {
            dao.delete(new RyaStatement(BOB, KNOWS, ALICE), conf);
        }
        assertTrue(only(TraceEvent.ScanStart.class).reason().startsWith("delete: find the stored matches of <urn:test#bob>"));
        final TraceEvent.TableWrites spo = only(TraceEvent.Flush.class).tables().stream()
                .filter(w -> w.table().equals(getTablePrefix() + "spo")).findFirst().orElseThrow();
        assertEquals(1, spo.deletes());
    }

    @Test
    public void withoutATraceNothingIsReportedAndErrorsPassThrough() throws Exception {
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add)) {
            assertThrows(TableNotFoundException.class, () -> client.createScanner("no_such_table", Authorizations.EMPTY));
        }
        events.clear();
        assertEquals(2, count(new RyaStatement(ALICE, KNOWS, null)));
        dao.add(new RyaStatement(CAROL, KNOWS, BOB));
        assertTrue(events.isEmpty(), events.toString());
        assertTrue(!AccumuloTrace.isActive());
        assertThrows(TableNotFoundException.class, () -> client.createScanner("no_such_table", Authorizations.EMPTY));
        // Default methods go through the proxy too.
        try (var scanner = client.createScanner(getTablePrefix() + "spo", Authorizations.EMPTY)) {
            assertEquals(4, scanner.stream().count());
        }
    }

    private long count(final RyaStatement pattern) {
        long n = 0;
        try (CloseableIteration<RyaStatement> it = dao.getQueryEngine().query(pattern, conf)) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }

    private <T extends TraceEvent> T only(final Class<T> type) {
        final List<T> found = events.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), type.getSimpleName() + " in " + events);
        return found.get(0);
    }
}
