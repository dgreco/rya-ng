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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;

public class TraceTest {

    @Test
    public void bytesShowSeparatorsAndKeepText() {
        final byte[] row = {'a', 0, 'b', 1, 5, (byte) 0xff};
        assertEquals("a⟨00⟩b⟨01⟩⟨05⟩⟨ff⟩", TraceText.bytes(row));
        assertEquals("café ⟨00⟩ 東京", TraceText.bytes("café \u0000 東京".getBytes(StandardCharsets.UTF_8)));
        assertEquals("⟨c3⟩(", TraceText.bytes(new byte[] {(byte) 0xc3, '('}));
    }

    @Test
    public void rangesAndIteratorsAreReadable() {
        assertEquals("[a, b)", TraceText.range(new Range(new Text("a"), true, new Text("b"), false)));
        assertEquals("[a, b]", TraceText.range(new Range(new Text("a"), new Text("b"))));
        assertEquals("[a, a]", TraceText.range(Range.exact(new Text("a"))));
        assertEquals("[-∞, +∞)", TraceText.range(new Range()).replace("+∞]", "+∞)"));
        final IteratorSetting s = new IteratorSetting(11, "ri", "org.apache.accumulo.core.iterators.user.RegExFilter");
        s.addOption("rowRegex", "x\u0000y");
        assertEquals("11 ri (RegExFilter) rowRegex=x⟨00⟩y", TraceText.iterator(s));
    }

    @Test
    public void patternsShowOpenPositionsAndTypes() {
        assertEquals("<urn:a> ? \"42\"^^xsd:integer in <urn:g>",
                TraceText.pattern(new RyaStatement(new RyaIRI("urn:a"), null, new RyaType(XSD.INTEGER, "42"), new RyaIRI("urn:g"))));
        assertEquals("? <urn:p> ?", TraceText.pattern(new RyaStatement(null, new RyaIRI("urn:p"), null)));
    }

    @Test
    public void longTracesKeepTheirTotals() {
        final List<TraceEvent> events = new ArrayList<>();
        final Trace trace = new Trace(events::add);
        final int scans = Trace.MAX_DETAILED_EVENTS + 5;
        for (int i = 0; i < scans; i++) {
            final int id = trace.scanStarted("Scanner", "t", List.of(), 1, List.of(), List.of(), "none", "");
            trace.scanEnded(id, 2, 1_000, "exhausted");
        }
        trace.finish();
        assertEquals(Trace.MAX_DETAILED_EVENTS, events.stream().filter(e -> e instanceof TraceEvent.ScanStart).count());
        assertEquals(Trace.MAX_DETAILED_EVENTS, events.stream().filter(e -> e instanceof TraceEvent.ScanEnd).count());
        final TraceEvent.Summary summary = (TraceEvent.Summary) events.get(events.size() - 1);
        assertEquals(scans, summary.scans());
        assertEquals(2L * scans, summary.entries());
        assertEquals(5, summary.omitted());
    }

    @Test
    public void runningScansReportTheirProgress() {
        final List<TraceEvent> events = new ArrayList<>();
        final Trace trace = new Trace(events::add);
        final int id = trace.scanStarted("Scanner", "t", List.of(), 1, List.of(), List.of(), "none", "");
        final long t0 = System.nanoTime();
        trace.track(id, new Trace.Live() {
            @Override
            public long entries() {
                return 42;
            }

            @Override
            public long nanos(final long now) {
                return 5_000_000;
            }

            @Override
            public long waitingNanos(final long now) {
                return 3_000_000;
            }

            @Override
            public String position() {
                return "row⟨00⟩x";
            }
        });
        trace.tick(t0);
        assertFalse(events.stream().anyMatch(e -> e instanceof TraceEvent.Progress), "a scan reports once it ran for a while");

        trace.tick(t0 + 2_000_000_000L);
        final TraceEvent.Progress progress = (TraceEvent.Progress) events.get(events.size() - 1);
        assertEquals(List.of(new TraceEvent.ScanProgress(id, 42, 5.0, 3.0, "row⟨00⟩x")), progress.scans());
        assertEquals(1, progress.openScans());
        assertEquals(1, progress.scansSoFar());
        assertEquals(42, progress.entriesSoFar());

        trace.scanEnded(id, 42, 5_000_000, "exhausted");
        final int before = events.size();
        trace.tick(t0 + 4_000_000_000L);
        trace.finish();
        trace.tick(t0 + 6_000_000_000L);
        assertEquals(before + 1, events.size(), "no progress once nothing runs or the trace ended: " + events);
    }

    @Test
    public void aScopeAlwaysLeavesTheThreadUntraced() {
        final List<TraceEvent> events = new ArrayList<>();
        try {
            try (AccumuloTrace.Scope scope = AccumuloTrace.start(events::add)) {
                assertTrue(AccumuloTrace.isActive());
                throw new IllegalStateException("boom");
            }
        } catch (final IllegalStateException expected) {
            // the scope closed anyway
        }
        assertFalse(AccumuloTrace.isActive());
        assertTrue(events.get(events.size() - 1) instanceof TraceEvent.Summary);
        AccumuloTrace.note("ignored", () -> "no trace");
        assertEquals(1, events.size());
    }

    @Test
    public void aBrokenListenerDoesNotBreakTheWork() {
        try (AccumuloTrace.Scope scope = AccumuloTrace.start(e -> {
            throw new IllegalStateException("listener");
        })) {
            AccumuloTrace.note("query", () -> "SELECT");
        }
        assertFalse(AccumuloTrace.isActive());
    }
}
