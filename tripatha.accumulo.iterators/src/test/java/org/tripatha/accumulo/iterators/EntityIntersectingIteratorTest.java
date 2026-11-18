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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iteratorsImpl.system.SortedMapIterator;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;

/**
 * {@link EntityIntersectingIterator} over postings in memory: the matches of each shard row, with
 * their counts, and a scan resumed after any of them.
 */
public class EntityIntersectingIteratorTest {
    private static final String A = "urn:p\u0001urn:a\u0001\u0002";
    private static final String B = "urn:q\u0001urn:b\u0001\u0002";
    private static final String C = "urn:r\u0001x\u0001\u0003";

    private final TreeMap<Key, Value> postings = new TreeMap<>();

    @Test
    public void eachRowGivesTheSubjectsOfEveryTermWithTheirCounts() throws Exception {
        posting("0", A, "s1", "", "");
        posting("0", A, "s2", "", "");
        posting("0", A, "s2", "urn:g1", "");
        posting("0", A, "s3", "", "");
        posting("0", B, "s2", "", "");
        posting("0", B, "s3", "", "");
        posting("0", B, "s3", "", "X"); // the same statement under a label
        posting("0", B, "s4", "", "");
        posting("1", A, "s5", "", "");
        posting("1", A, "s10", "", "");
        posting("1", B, "s10", "", "");
        posting("1", B, "s5", "", "");
        posting("2", A, "s7", "", "");
        assertEquals(List.of("0 s2 2", "0 s3 2", "1 s10 1", "1 s5 1"), matches(new Range(), A, B));
    }

    @Test
    public void aSubjectIsNotTheStartOfAnother() throws Exception {
        posting("0", A, "s1", "", "");
        posting("0", A, "s10", "", "");
        posting("0", B, "s10", "", "");
        posting("0", B, "s100", "", "");
        assertEquals(List.of("0 s10 1"), matches(new Range(), A, B));
        assertEquals(List.of(), matches(new Range(), A, C), "a term with no postings");
    }

    @Test
    public void aScanResumedAfterAMatchGoesOnFromTheNextSubject() throws Exception {
        for (final String s : List.of("s1", "s2", "s3", "s4")) {
            posting("0", A, s, "", "");
            posting("0", B, s, "", "");
            posting("0", B, s, "urn:g", "");
            posting("1", A, s, "", "");
            posting("1", B, s, "", "");
        }
        final List<String> all = matches(new Range(), A, B);
        assertEquals(8, all.size());
        assertEquals("0 s1 2", all.get(0));
        assertEquals(all.subList(2, 8), matches(new Range(new Key("0", "", "s2"), false, null, false), A, B));
        assertEquals(all.subList(4, 8), matches(new Range(new Key("0", "", "s4"), false, null, false), A, B));
        assertEquals(List.of("1 s1 1", "1 s2 1", "1 s3 1", "1 s4 1"), matches(Range.exact("1"), A, B));
    }

    @Test
    public void threeTermsLeapfrogToTheirCommonSubjects() throws Exception {
        for (int i = 0; i < 300; i++) {
            final String s = String.format("s%03d", i);
            if (i % 2 == 0) {
                posting("7", A, s, "", "");
            }
            if (i % 3 == 0) {
                posting("7", B, s, "", "");
            }
            if (i % 5 == 0) {
                posting("7", C, s, "", "");
            }
        }
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 300; i += 30) {
            expected.add(String.format("7 s%03d 1", i));
        }
        assertEquals(expected, matches(new Range(), A, B, C));
        assertEquals(expected, matches(new Range(), C, A, B), "in any order");
    }

    @Test
    public void countingGivesEachRowsMatchesAndRows() throws Exception {
        posting("0", A, "s1", "", "");
        posting("0", A, "s2", "", "");
        posting("0", A, "s2", "urn:g1", "");
        posting("0", B, "s2", "", "");
        posting("0", B, "s3", "", "");
        posting("1", A, "s5", "", "");
        posting("1", B, "s4", "", ""); // no match in row 1
        posting("2", A, "s7", "", "");
        posting("2", A, "s8", "", "");
        posting("2", B, "s7", "", "");
        posting("2", B, "s7", "", "X");
        posting("2", B, "s8", "", "");
        assertEquals(List.of("0 count  1,2", "2 count  2,3"), counts(new Range(), A, B));
        assertEquals(List.of("2 count  2,3"), counts(new Range(new Key("0", "count", ""), false, null, false), A, B),
                "resumed after row 0's count");
        assertEquals(List.of("0 count  1,2"), counts(Range.exact("0"), A, B));
        assertEquals(List.of(), counts(new Range(), A, C));
    }

    @Test
    public void aCopyCountsToo() throws Exception {
        posting("0", A, "s1", "", "");
        posting("0", B, "s1", "", "");
        final EntityIntersectingIterator it = new EntityIntersectingIterator();
        it.init(new SortedMapIterator(postings), Map.of(EntityIntersectingIterator.TERMS,
                EntityIntersectingIterator.encodeTerms(List.of(A.getBytes(StandardCharsets.UTF_8), B.getBytes(StandardCharsets.UTF_8))),
                EntityIntersectingIterator.COUNT, "true"), null);
        final var copy = it.deepCopy(null);
        copy.seek(new Range(), Set.of(), false);
        assertEquals(new Key("0", "count", ""), copy.getTopKey());
        assertEquals("1,1", copy.getTopValue().toString());
    }

    // ------------------------------------------------------------------ helpers

    private void posting(final String shard, final String term, final String subject, final String graph, final String label) {
        postings.put(new Key(new Text(shard), new Text(term), new Text(subject + "\u0001" + graph + "\u0001"), new Text(label), 1L),
                new Value(new byte[0]));
    }

    private List<String> counts(final Range range, final String... terms) throws Exception {
        return read(range, true, terms);
    }

    private List<String> matches(final Range range, final String... terms) throws Exception {
        return read(range, false, terms);
    }

    private List<String> read(final Range range, final boolean count, final String... terms) throws Exception {
        final List<byte[]> bytes = new ArrayList<>();
        for (final String t : terms) {
            bytes.add(t.getBytes(StandardCharsets.UTF_8));
        }
        final EntityIntersectingIterator it = new EntityIntersectingIterator();
        it.init(new SortedMapIterator(postings), Map.of(EntityIntersectingIterator.TERMS,
                EntityIntersectingIterator.encodeTerms(bytes), EntityIntersectingIterator.COUNT, Boolean.toString(count)), null);
        it.seek(range, Set.of(), false);
        final List<String> out = new ArrayList<>();
        while (it.hasTop()) {
            final Key k = it.getTopKey();
            out.add(count ? k.getRow() + " " + k.getColumnFamily() + " " + k.getColumnQualifier() + " " + it.getTopValue()
                    : k.getRow() + " " + k.getColumnQualifier() + " " + it.getTopValue());
            it.next();
        }
        return out;
    }
}
