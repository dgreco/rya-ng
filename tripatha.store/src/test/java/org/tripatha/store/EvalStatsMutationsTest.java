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
package org.tripatha.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.store.spi.RowMutation;

class EvalStatsMutationsTest {

    /** Cells as "row|family|qualifier|visibility" → summed value. */
    private static Map<String, Long> cells(final List<RowMutation> mutations) {
        final Map<String, Long> out = new TreeMap<>();
        for (final RowMutation m : mutations) {
            final String row = new String(m.row(), StandardCharsets.UTF_8);
            for (final RowMutation.Update u : m.updates()) {
                final String key = row.replace('\u0000', '~') + "|" + new String(u.family(), StandardCharsets.UTF_8)
                        + "|" + new String(u.qualifier(), StandardCharsets.UTF_8)
                        + "|" + new String(u.visibility(), StandardCharsets.UTF_8);
                out.merge(key, u.delta(), Long::sum);
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

    /**
     * Skipping a subject's ones leaves out exactly the s, sp and so cells whose sum is 1, label by
     * label, and keeps every other counter, ones included.
     */
    @Test
    void skipSubjectOnesLeavesOutOnlyTheSubjectCountersOfOne() {
        final EvalStatsMutations.CounterBatch all = new EvalStatsMutations.CounterBatch();
        final EvalStatsMutations.CounterBatch skip = new EvalStatsMutations.CounterBatch();
        final List<RyaStatement> statements = List.of(
                st("urn:a", "urn:name", "Alice", "urn:g", null),
                st("urn:a", "urn:knows", "urn:b", "urn:g", null),
                st("urn:a", "urn:knows", "urn:c", "urn:g", null),
                st("urn:a", "urn:knows", "urn:c", "urn:g", "A"),
                st("urn:b", "urn:name", "Bob", null, null));
        for (final RyaStatement s : statements) {
            all.add(s, 1);
            skip.add(s, 1);
        }
        final Map<String, Long> every = cells(all.drain());
        final Map<String, Long> kept = cells(skip.drain(EvalStatsMutations.CounterPolicy.SKIP_SUBJECT_ONES));

        final Map<String, Long> expected = new TreeMap<>(every);
        expected.entrySet().removeIf(e -> e.getValue() == 1 && isSubjectCounter(e.getKey()));
        assertEquals(expected, kept);
        // Kept: urn:a's totals above 1, and every non-subject counter (urn:b's object "Bob" included).
        assertEquals(3L, kept.get("urn:a|s||"));
        assertEquals(3L, kept.get("urn:a|s|urn:g|"));
        assertEquals(2L, kept.get("urn:a~urn:knows|sp||"));
        assertEquals(1L, kept.get("Bob|o||"));
        assertEquals(1L, kept.get("urn:name~Alice|po||"));
        // Left out: the label A cells of urn:a (one statement), urn:b's subject cells, and so cells of one.
        assertTrue(!kept.containsKey("urn:a|s||A") && every.containsKey("urn:a|s||A"));
        assertTrue(!kept.containsKey("urn:b|s||") && every.containsKey("urn:b|s||"));
        assertTrue(!kept.containsKey("urn:a~Alice|so||"));
        assertEquals(1L, every.get("urn:a~Alice|so||"));
        // The default drain, and the ALL policy, keep everything.
        assertTrue(every.values().stream().anyMatch(v -> v == 1));
        assertTrue(!EvalStatsMutations.CounterPolicy.ALL.skips(new EvalStatsMutations.CounterKey("urn:b", "s", "", ""), 1));
    }

    private static boolean isSubjectCounter(final String cell) {
        final String family = cell.split("\\|")[1];
        return family.equals("s") || family.equals("sp") || family.equals("so");
    }
}
