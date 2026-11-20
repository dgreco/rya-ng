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
package org.tripatha.loader.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tripatha.store.spi.RowMutation;

/** The bulk build's runs: one cell per key in each, combined across runs when read (SCALE-STUDY.md O3). */
class SpillsTest {
    @TempDir
    Path dir;

    @Test
    void countersAddUpAndPutsKeepTheirNewestVersionAcrossRuns() {
        final Spills spills = new Spills(dir.toString());
        final SpillBuffer one = new SpillBuffer(0);
        one.accept("t_eval", new RowMutation(b("c")).addToCounter(b("s"), b(""), b(""), 2).addToCounter(b("s"), b(""), b("A"), 1));
        one.accept("t_eval", new RowMutation(b("c")).addToCounter(b("s"), b(""), b(""), 3));
        one.accept("t_spo", new RowMutation(b("r")).put(b(""), b(""), b(""), 5, b("old")));
        final Map<String, String> first = one.spill(spills, "one");
        final SpillBuffer two = new SpillBuffer(0);
        two.accept("t_eval", new RowMutation(b("c")).addToCounter(b("s"), b(""), b(""), 4));
        two.accept("t_eval", new RowMutation(b("d")).addToCounter(b("s"), b(""), b(""), -1));
        two.accept("t_spo", new RowMutation(b("r")).put(b(""), b(""), b(""), 9, b("new")));
        two.accept("t_spo", new RowMutation(b("q")).put(b("g"), b(""), b("A"), 9, b("")));
        final Map<String, String> second = two.spill(spills, "two");

        final List<String> counters = new ArrayList<>();
        try (Spills.Reader r = spills.read(List.of(first.get("t_eval"), second.get("t_eval")), null, null, true)) {
            r.forEachRemaining(c -> counters.add(c.key() + " = " + c.delta()));
        }
        assertEquals(List.of("c s: [] = 9", "c s: [A] = 1", "d s: [] = -1"), counters, "one cell per label, deltas added up");

        final List<String> puts = new ArrayList<>();
        try (Spills.Reader r = spills.read(List.of(first.get("t_spo"), second.get("t_spo")), null, null, false)) {
            r.forEachRemaining(c -> puts.add(c.key() + " @" + c.timestamp() + " = " + new String(c.value(), StandardCharsets.UTF_8)));
        }
        assertEquals(List.of("q g: [A] @9 = ", "r : [] @9 = new"), puts, "the newest version of a put");

        // A range reads only its rows: [d, null) of eval, [null, r) of spo.
        try (Spills.Reader r = spills.read(List.of(first.get("t_eval"), second.get("t_eval")), b("d"), null, true)) {
            assertEquals("d", new String(r.next().key().row(), StandardCharsets.UTF_8));
            assertEquals(false, r.hasNext());
        }
        try (Spills.Reader r = spills.read(List.of(first.get("t_spo"), second.get("t_spo")), null, b("r"), false)) {
            assertEquals("q", new String(r.next().key().row(), StandardCharsets.UTF_8));
            assertEquals(false, r.hasNext());
        }
    }

    @Test
    void aBufferRefusesWhatABuildNeverWrites() {
        final SpillBuffer buffer = new SpillBuffer(0);
        buffer.accept("t_x", new RowMutation(b("c")).addToCounter(b("s"), b(""), b(""), 1));
        assertThrows(IllegalStateException.class, () -> buffer.accept("t_x", new RowMutation(b("c")).put(b("s"), b(""), b(""), 1, b(""))));
        assertThrows(IllegalStateException.class, () -> buffer.accept("t_y", new RowMutation(b("c")).delete(b("s"), b(""), b(""), 1)));
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
