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
package org.tripatha.accumulo.iterators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iteratorsImpl.system.SortedMapIterator;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;

/** {@link RangeCountingIterator}: one entry per range sought, at its last key, and nothing counted twice on resuming. */
class RangeCountingIteratorTest {
    private final TreeMap<Key, Value> cells = new TreeMap<>();

    RangeCountingIteratorTest() {
        // a: 3 cells in two families; b: 1; c: 4; d: none.
        put("a", "", "q1");
        put("a", "", "q2");
        put("a", "g", "q1");
        put("b", "", "q1");
        for (int i = 0; i < 4; i++) {
            put("c", "", "q" + i);
        }
    }

    @Test
    void eachRangeGivesOneEntryAtItsLastKey() throws Exception {
        final RangeCountingIterator it = open();
        assertEquals(List.of("a g q1 = 3"), read(it, Range.exact("a")));
        assertEquals(List.of("c  q3 = 4"), read(it, Range.exact("c")));
        assertEquals(List.of(), read(it, Range.exact("d")), "nothing in the range, nothing back");
        assertEquals(List.of("c  q3 = 8"), read(it, new Range("a", "c")));
    }

    @Test
    void resumingAfterTheEntryCountsNothingAgain() throws Exception {
        final RangeCountingIterator it = open();
        final List<String> first = read(it, new Range("a", "b"));
        assertEquals(List.of("b  q1 = 4"), first);
        // A tablet server resumes just after the last key it returned.
        final Key returned = new Key(new Text("b"), new Text(""), new Text("q1"));
        assertEquals(List.of(), read(it, new Range(returned, false, new Key(new Text("b")).followingKey(org.apache.accumulo.core.data.PartialKey.ROW), false)));
    }

    @Test
    void theColumnFamiliesOfTheSeekAreTheSourcesToApply() throws Exception {
        // In a tablet server, a system iterator below applies them, as this one does here.
        final RangeCountingIterator it = new RangeCountingIterator();
        it.init(new org.apache.accumulo.core.iteratorsImpl.system.ColumnFamilySkippingIterator(new SortedMapIterator(cells)), Map.of(),
                null);
        it.seek(new Range("a", "c"), Set.of(new ArrayByteSequence("g".getBytes(StandardCharsets.UTF_8))), true);
        assertEquals("1", it.getTopValue().toString());
        it.next();
        assertFalse(it.hasTop());
    }

    @Test
    void aCopyCountsOnItsOwn() throws Exception {
        final RangeCountingIterator it = open();
        final RangeCountingIterator copy = (RangeCountingIterator) it.deepCopy(null);
        assertEquals(List.of("a g q1 = 3"), read(copy, Range.exact("a")));
        assertEquals(List.of("c  q3 = 4"), read(it, Range.exact("c")));
    }

    private RangeCountingIterator open() throws Exception {
        final RangeCountingIterator it = new RangeCountingIterator();
        it.init(new SortedMapIterator(cells), Map.of(), null);
        return it;
    }

    private static List<String> read(final RangeCountingIterator it, final Range range) throws Exception {
        it.seek(range, List.of(), false);
        final List<String> out = new ArrayList<>();
        while (it.hasTop()) {
            final Key k = it.getTopKey();
            out.add(k.getRow() + " " + k.getColumnFamily() + " " + k.getColumnQualifier() + " = " + it.getTopValue());
            it.next();
        }
        return out;
    }

    private void put(final String row, final String family, final String qualifier) {
        cells.put(new Key(new Text(row), new Text(family), new Text(qualifier)), new Value(new byte[0]));
    }
}
