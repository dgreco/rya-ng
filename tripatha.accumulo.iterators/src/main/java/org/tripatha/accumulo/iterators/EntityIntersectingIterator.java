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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.hadoop.io.Text;

/**
 * Intersects the posting lists of Tripatha's entity index inside each shard row (SCALE-STUDY.md
 * O23), on the tablet server, so that only the matching subjects leave it.
 *
 * <p>A posting is a key {@code (shard, term, subject 01 graph 01 qualifier)}: within a shard row and
 * a term, postings are sorted by subject. For the terms given, this iterator finds the subjects
 * that have a posting under every one of them: it seeks each term's list to the largest subject
 * the lists are at, until they all agree (a leapfrog), then counts each list's postings of that
 * subject (graphs and labels give several). It returns one key per match:
 *
 * <pre>
 * row        the shard row
 * family     empty
 * qualifier  the subject
 * value      the product of the counts, in decimal: the rows the match gives in a join
 * </pre>
 *
 * <p>With option {@code count=true} it counts instead: one key per row that has matches, with
 * family {@code count}, an empty qualifier, and the value {@code subjects,rows} in decimal (the
 * matches, and the sum of their products).
 *
 * <p>Labels are applied below user iterators, so only visible postings are counted. Keys come in
 * row and subject order; when the scan is resumed after a key, the intersection resumes after its
 * subject, or at the next row after a count. Option {@code terms}: the terms' bytes, each in
 * Base64, separated by commas.
 *
 * <p>Depends only on Accumulo, so that its jar is all an operator puts on the tablet servers.
 */
public final class EntityIntersectingIterator implements SortedKeyValueIterator<Key, Value> {
    /** The option naming the terms. */
    public static final String TERMS = "terms";
    /** The option to count each row's matches instead of returning them. */
    public static final String COUNT = "count";
    /** The family of a row's count. */
    public static final Text COUNT_FAMILY = new Text("count");
    private static final byte SEP = 1;
    private static final Text EMPTY = new Text();

    private SortedKeyValueIterator<Key, Value> source;
    private IteratorEnvironment env;
    private Map<String, String> options;
    private boolean count;
    private byte[][] terms;
    private Text[] termTexts;
    private SortedKeyValueIterator<Key, Value>[] lists;
    private Range range;
    private Key topKey;
    private Value topValue;
    /** The row being intersected, or null before the first and after the last. */
    private Text row;

    /** Encodes terms for the {@link #TERMS} option. */
    public static String encodeTerms(final List<byte[]> terms) {
        final StringBuilder b = new StringBuilder();
        for (final byte[] term : terms) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(Base64.getEncoder().encodeToString(term));
        }
        return b.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(final SortedKeyValueIterator<Key, Value> source, final Map<String, String> options,
            final IteratorEnvironment env) throws IOException {
        this.source = source;
        this.env = env;
        this.options = Map.copyOf(options);
        this.count = Boolean.parseBoolean(options.get(COUNT));
        final String encoded = options.get(TERMS);
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Option " + TERMS + " is required");
        }
        final String[] parts = encoded.split(",");
        terms = new byte[parts.length][];
        termTexts = new Text[parts.length];
        lists = new SortedKeyValueIterator[parts.length];
        for (int i = 0; i < parts.length; i++) {
            terms[i] = Base64.getDecoder().decode(parts[i]);
            termTexts[i] = new Text(terms[i]);
            lists[i] = i == 0 ? source : source.deepCopy(env);
        }
    }

    @Override
    public SortedKeyValueIterator<Key, Value> deepCopy(final IteratorEnvironment environment) {
        final EntityIntersectingIterator copy = new EntityIntersectingIterator();
        try {
            copy.init(source.deepCopy(environment), options, environment);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return copy;
    }

    @Override
    public void seek(final Range range, final Collection<ByteSequence> columnFamilies, final boolean inclusive)
            throws IOException {
        this.range = range;
        topKey = null;
        topValue = null;
        final Key start = range.getStartKey();
        byte[] after = null;
        if (start == null) {
            row = firstRow(null);
        } else {
            row = new Text(start.getRow());
            if (!range.isStartKeyInclusive() && start.getColumnFamilyData().length() == 0
                    && start.getColumnQualifierData().length() > 0) {
                // Resumed after a match this iterator returned: (row, empty family, subject).
                after = start.getColumnQualifierData().toArray();
            } else if (!range.isStartKeyInclusive() && count && start.compareColumnFamily(COUNT_FAMILY) == 0) {
                // Resumed after a row's count: the row is done.
                row = firstRow(new Key(row).followingKey(PartialKey.ROW));
            }
        }
        if (row != null && !openRow(after)) {
            nextRow();
        }
        findNext();
    }

    @Override
    public boolean hasTop() {
        return topKey != null;
    }

    @Override
    public void next() throws IOException {
        topKey = null;
        topValue = null;
        if (count && row != null) {
            nextRow(); // a count is the whole row's
        }
        findNext();
    }

    @Override
    public Key getTopKey() {
        return topKey;
    }

    @Override
    public Value getTopValue() {
        return topValue;
    }

    /** Finds the next match, or the next row's count, row after row, and makes it the top. */
    private void findNext() throws IOException {
        while (row != null) {
            if (count) {
                long subjects = 0;
                long rows = 0;
                for (byte[] match = intersect(); match != null; match = intersect()) {
                    subjects++;
                    rows = Math.addExact(rows, consume(match));
                }
                if (subjects > 0) {
                    topKey = new Key(row, COUNT_FAMILY, EMPTY);
                    topValue = new Value((subjects + "," + rows).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            } else {
                final byte[] match = intersect();
                if (match != null) {
                    final long rows = consume(match);
                    topKey = new Key(row, EMPTY, new Text(match));
                    topValue = new Value(Long.toString(rows).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            nextRow();
        }
    }

    /** Moves every list past a match's postings; returns the product of their counts, the rows it gives. */
    private long consume(final byte[] match) throws IOException {
        long rows = 1;
        for (int i = 0; i < lists.length; i++) {
            long postings = 0;
            while (match.length > 0 && same(i, match)) {
                postings++;
                lists[i].next();
            }
            rows = Math.multiplyExact(rows, postings);
        }
        return rows;
    }

    /**
     * Advances the lists of the current row to the next subject they all have.
     *
     * @return that subject, its postings at the tops of the lists; null when a list has run out
     */
    private byte[] intersect() throws IOException {
        byte[] target = null;
        for (int i = 0; i < lists.length; i++) {
            final byte[] head = head(i);
            if (head == null) {
                return null;
            }
            if (target == null || Arrays.compareUnsigned(head, target) > 0) {
                target = head;
            }
        }
        boolean agreed;
        do {
            agreed = true;
            for (int i = 0; i < lists.length; i++) {
                byte[] head = head(i);
                if (head != null && Arrays.compareUnsigned(head, target) < 0) {
                    seekList(i, target);
                    head = head(i);
                }
                if (head == null) {
                    return null;
                }
                if (!Arrays.equals(head, target)) {
                    target = head;
                    agreed = false;
                }
            }
        } while (!agreed);
        return target;
    }

    /** The subject at the top of a list, or null when it has none left in the current row and term. */
    private byte[] head(final int list) {
        final SortedKeyValueIterator<Key, Value> it = lists[list];
        if (!it.hasTop()) {
            return null;
        }
        final Key key = it.getTopKey();
        if (key.compareRow(row) != 0 || key.compareColumnFamily(termTexts[list]) != 0) {
            return null;
        }
        return subject(key.getColumnQualifierData().toArray());
    }

    private boolean same(final int list, final byte[] subject) {
        final byte[] head = head(list);
        return head != null && Arrays.equals(head, subject);
    }

    /** Opens every list of the current row, past {@code after} when it is given; false if one is empty. */
    private boolean openRow(final byte[] after) throws IOException {
        for (int i = 0; i < lists.length; i++) {
            // Past a subject's postings: they are subject 01 ..., and every later subject is at least subject 02.
            seekList(i, after == null ? new byte[0] : append(after, (byte) 2));
            if (head(i) == null) {
                return false;
            }
        }
        return true;
    }

    /** Seeks a list to the first posting of the current row and term whose qualifier is at least {@code from}. */
    private void seekList(final int list, final byte[] from) throws IOException {
        final Text term = termTexts[list];
        final Key start = new Key(row, term, new Text(from));
        final Key end = new Key(row, term).followingKey(PartialKey.ROW_COLFAM);
        final Range clipped = range.clip(new Range(start, true, end, false), true);
        if (clipped == null) {
            lists[list].seek(new Range(end, true, end, false), Set.of(), false);
        } else {
            lists[list].seek(clipped, Set.of(), false);
        }
    }

    /** Moves to the next row of the range that has postings under every term, or to none. */
    private void nextRow() throws IOException {
        while (row != null) {
            row = firstRow(new Key(row).followingKey(PartialKey.ROW));
            if (row != null && openRow(null)) {
                return;
            }
        }
    }

    /** The first row at or after {@code from} (null: the range's start) with a posting under the first term. */
    private Text firstRow(final Key from) throws IOException {
        final Range rest = from == null ? range : range.clip(new Range(from, true, null, false), true);
        if (rest == null) {
            return null;
        }
        lists[0].seek(rest, Set.of(new org.apache.accumulo.core.data.ArrayByteSequence(terms[0])), true);
        return lists[0].hasTop() ? new Text(lists[0].getTopKey().getRow()) : null;
    }

    /** A posting's subject: its qualifier up to the first 01. */
    static byte[] subject(final byte[] qualifier) {
        int end = 0;
        while (end < qualifier.length && qualifier[end] != SEP) {
            end++;
        }
        return Arrays.copyOf(qualifier, end);
    }

    private static byte[] append(final byte[] bytes, final byte last) {
        final byte[] out = Arrays.copyOf(bytes, bytes.length + 1);
        out[bytes.length] = last;
        return out;
    }
}
