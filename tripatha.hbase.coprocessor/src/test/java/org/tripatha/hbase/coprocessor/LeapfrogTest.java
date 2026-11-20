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
package org.tripatha.hbase.coprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * {@link Leapfrog}'s pages against a plain intersection: every match once, with its rows, whatever
 * the page sizes and budgets, hidden cells included, and every page making progress.
 */
class LeapfrogTest {
    /** Subjects that are prefixes of each other, and one with a 02 byte, which bounds use. */
    private static final String[] SUBJECTS = {"a", "ab", "ab\u0002", "ab\u0002c", "abc", "b", "ba", "c", "cc", "d"};

    @Test
    void pagesGiveEveryMatchOnceWhateverTheirSize() throws Exception {
        final Random random = new Random(42);
        for (int round = 0; round < 3_000; round++) {
            final int k = 1 + random.nextInt(4);
            final List<List<Entry>> lists = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                lists.add(randomList(random));
            }
            final Map<String, Long> expected = expected(lists);
            final int maxMatches = 1 + random.nextInt(4);
            final long cells = 1 + random.nextInt(6);
            assertEquals(expected, run(lists, maxMatches, cells), "round " + round + ", " + maxMatches + " matches, " + cells
                    + " cells: " + lists);
        }
    }

    @Test
    void aLargeBudgetIsOnePage() throws Exception {
        final List<List<Entry>> lists = List.of(
                List.of(e("a", true), e("b", true), e("b", true), e("c", false), e("d", true)),
                List.of(e("b", true), e("c", true), e("d", true), e("d", true)));
        final Leapfrog.Budget budget = new Leapfrog.Budget(1_000, 60_000);
        final Map<String, Long> got = new TreeMap<>();
        final byte[] resume = Leapfrog.page(open(lists, new byte[0], budget), new byte[0], 100, budget,
                (s, rows) -> got.put(text(s), rows));
        assertNull(resume);
        assertEquals(Map.of("b", 2L, "d", 2L), got);
    }

    @Test
    void aSubjectWithMoreCellsThanTheBudgetIsCountedWhole() throws Exception {
        final List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(e("s", i % 3 != 0));
        }
        final List<List<Entry>> lists = List.of(many, List.of(e("s", true)));
        assertEquals(Map.of("s", 33L), run(lists, 1, 1));
    }

    @Test
    void noListsNoMatches() throws Exception {
        assertNull(Leapfrog.page(List.of(), new byte[0], 10, new Leapfrog.Budget(10, 1_000), (s, r) -> {
            throw new AssertionError();
        }));
    }

    /** All pages of a row, checking that each starts past the one before. */
    private static Map<String, Long> run(final List<List<Entry>> lists, final int maxMatches, final long cells) throws Exception {
        final Map<String, Long> got = new TreeMap<>();
        byte[] from = new byte[0];
        for (int pages = 0; from != null; pages++) {
            assertTrue(pages < 10_000, "pages make progress");
            final Leapfrog.Budget budget = new Leapfrog.Budget(cells, 60_000);
            final List<Memory> open = open(lists, from, budget);
            final byte[] start = from;
            final byte[] next = Leapfrog.page(open, from, maxMatches, budget, (s, rows) -> {
                assertTrue(Arrays.compareUnsigned(s, start) >= 0, "a match at or after the page's start");
                assertNull(got.put(text(s), rows), "each match once: " + text(s));
            });
            if (next != null) {
                assertTrue(Arrays.compareUnsigned(next, from) > 0, "the next page starts later");
            }
            from = next;
        }
        return got;
    }

    private static List<Memory> open(final List<List<Entry>> lists, final byte[] from, final Leapfrog.Budget budget) {
        final List<Memory> open = new ArrayList<>();
        for (final List<Entry> list : lists) {
            final Memory m = new Memory(list, budget);
            m.seek(from);
            open.add(m);
        }
        return open;
    }

    private static Map<String, Long> expected(final List<List<Entry>> lists) {
        final Map<String, long[]> counts = new HashMap<>();
        for (int i = 0; i < lists.size(); i++) {
            for (final Entry e : lists.get(i)) {
                if (e.shown) {
                    counts.computeIfAbsent(e.subject, x -> new long[lists.size()])[i]++;
                }
            }
        }
        final Map<String, Long> out = new TreeMap<>();
        counts.forEach((s, perList) -> {
            long product = 1;
            for (final long n : perList) {
                product *= n;
            }
            if (product > 0) {
                out.put(s, product);
            }
        });
        return out;
    }

    private static List<Entry> randomList(final Random random) {
        final List<Entry> list = new ArrayList<>();
        for (final String s : SUBJECTS) {
            if (random.nextInt(3) > 0) {
                final int n = 1 + random.nextInt(3);
                for (int i = 0; i < n; i++) {
                    list.add(e(s, random.nextInt(4) > 0));
                }
            }
        }
        list.sort(Comparator.comparing(x -> x.subject.getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned));
        return list;
    }

    private static Entry e(final String subject, final boolean shown) {
        return new Entry(subject, shown);
    }

    private static String text(final byte[] subject) {
        return new String(subject, StandardCharsets.UTF_8);
    }

    private record Entry(String subject, boolean shown) {
        byte[] bytes() {
            return subject.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return subject.replace("\u0002", "\\2") + (shown ? "" : "(hidden)");
        }
    }

    /**
     * A list in memory that reads as {@link RegionPostings} does: each cell it examines, shown or
     * hidden, spends the budget, and it stops at the cell the budget refuses.
     */
    private static final class Memory implements Postings {
        private final List<Entry> entries;
        private final Leapfrog.Budget budget;
        /** The next cell to examine. */
        private int pos;
        /** The current shown cell, or -1. */
        private int head = -1;
        private byte[] bound = new byte[0];
        private boolean stopped;

        Memory(final List<Entry> entries, final Leapfrog.Budget budget) {
            this.entries = entries;
            this.budget = budget;
        }

        @Override
        public byte[] head() {
            while (head < 0) {
                if (pos >= entries.size() || stopped) {
                    return null;
                }
                if (!budget.spend()) {
                    stopped = true;
                    return null;
                }
                if (entries.get(pos).shown) {
                    head = pos;
                } else {
                    pos++;
                }
            }
            return entries.get(head).bytes();
        }

        @Override
        public boolean paused() {
            return head < 0 && stopped;
        }

        @Override
        public byte[] frontier() {
            final byte[] at = entries.get(pos).bytes();
            return Arrays.compareUnsigned(at, bound) > 0 ? at : bound;
        }

        @Override
        public void seek(final byte[] subject) {
            if (Arrays.compareUnsigned(subject, bound) > 0) {
                bound = subject;
            }
            if (head >= 0 && Arrays.compareUnsigned(entries.get(head).bytes(), subject) < 0) {
                pos = head;
                head = -1;
            }
            if (head < 0) {
                while (pos < entries.size() && Arrays.compareUnsigned(entries.get(pos).bytes(), subject) < 0) {
                    pos++;
                }
            }
        }

        @Override
        public void next() {
            pos = head + 1;
            head = -1;
        }

        @Override
        public void resume() {
            stopped = false;
        }

        @Override
        public void close() {
        }
    }
}
