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
package io.github.dgreco.rya.accumulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Mutation;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;

class EvalStatsMutationsTest {

    /** Cells as "row|family|qualifier|visibility" → summed value. */
    private static Map<String, Long> cells(final List<Mutation> mutations) {
        final Map<String, Long> out = new TreeMap<>();
        for (final Mutation m : mutations) {
            final String row = new String(m.getRow(), StandardCharsets.UTF_8);
            for (final ColumnUpdate u : m.getUpdates()) {
                final String key = row.replace('\u0000', '~') + "|" + new String(u.getColumnFamily(), StandardCharsets.UTF_8)
                        + "|" + new String(u.getColumnQualifier(), StandardCharsets.UTF_8)
                        + "|" + new String(u.getColumnVisibility(), StandardCharsets.UTF_8);
                out.merge(key, Long.parseLong(new String(u.getValue(), StandardCharsets.UTF_8)), Long::sum);
            }
        }
        return out;
    }

    private static RyaStatement st(final String s, final String p, final String o, final String graph, final String label) {
        return new RyaStatement(new RyaIRI(s), new RyaIRI(p), new RyaType(o), graph == null ? null : new RyaIRI(graph),
                null, label == null ? null : label.getBytes(StandardCharsets.UTF_8), null, 1L);
    }

    @Test
    void oneStatementAddsSixCountersOrTwelveInANamedGraph() {
        final Map<String, Long> plain = cells(EvalStatsMutations.counters(st("urn:s", "urn:p", "x", null, null), true));
        assertEquals(Map.of(
                "urn:s|s||", 1L, "urn:p|p||", 1L, "x|o||", 1L,
                "urn:s~urn:p|sp||", 1L, "urn:p~x|po||", 1L, "urn:s~x|so||", 1L), plain);

        final Map<String, Long> named = cells(EvalStatsMutations.counters(st("urn:s", "urn:p", "x", "urn:g", "A"), false));
        assertEquals(12, named.size());
        assertEquals(-1L, named.get("urn:p|p||A"));
        assertEquals(-1L, named.get("urn:p|p|urn:g|A"));
        assertTrue(named.values().stream().allMatch(v -> v == -1L));
    }

    @Test
    void aSubjectThatIsAlsoTheObjectKeepsBothCounters() {
        final Map<String, Long> same = cells(EvalStatsMutations.counters(
                new RyaStatement(new RyaIRI("urn:a"), new RyaIRI("urn:p"), new RyaIRI("urn:a")), true));
        assertEquals(1L, same.get("urn:a|s||"));
        assertEquals(1L, same.get("urn:a|o||"));
        assertEquals(6, same.size());
    }

    /** Summing many statements in a batch gives exactly the totals of their individual counters. */
    @Test
    void batchSumsEqualIndividualCounters() {
        final Random random = new Random(11);
        final EvalStatsMutations.CounterBatch batch = new EvalStatsMutations.CounterBatch();
        final Map<String, Long> expected = new HashMap<>();
        final Map<String, Long> actual = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            final RyaStatement s = st("urn:s" + random.nextInt(200), "urn:p" + random.nextInt(5), "o" + random.nextInt(50),
                    random.nextInt(3) == 0 ? "urn:g" + random.nextInt(2) : null,
                    random.nextInt(4) == 0 ? (random.nextBoolean() ? "A" : "A&B") : null);
            final boolean add = random.nextInt(10) != 0;
            cells(EvalStatsMutations.counters(s, add)).forEach((k, v) -> expected.merge(k, v, Long::sum));
            batch.add(s, add ? 1 : -1);
            if (batch.size() > 3000) {
                cells(batch.drain()).forEach((k, v) -> actual.merge(k, v, Long::sum));
            }
        }
        cells(batch.drain()).forEach((k, v) -> actual.merge(k, v, Long::sum));
        expected.values().removeIf(v -> v == 0);
        actual.values().removeIf(v -> v == 0);
        assertEquals(expected, actual);
        assertEquals(0, batch.size());
        assertTrue(batch.drain().isEmpty());
    }
}
