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
package io.github.dgreco.rya.store.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Rows to read. Ends carry explicit inclusive flags, because backends disagree on the default
 * (Accumulo's end row is inclusive, HBase's stop row exclusive). Rows compare as unsigned bytes.
 * <p>
 * The {@link Kind} keeps the form a range was made in, so a backend can build its native range
 * the same way (Accumulo's {@code Range.prefix}, {@code Range.exact}, ...).
 */
public final class KeyRange {

    /** How a range was made. */
    public enum Kind {
        /** Every row. */
        ALL,
        /** One row. */
        ROW,
        /** One row and column: every label and version of one cell. */
        CELL,
        /** Every row starting with a prefix. */
        PREFIX,
        /** From a start row to an end row. */
        ROWS
    }

    private static final KeyRange ALL_ROWS = new KeyRange(Kind.ALL, null, true, null, true, null, null, null);

    private final Kind kind;
    private final byte[] start;
    private final boolean startInclusive;
    private final byte[] end;
    private final boolean endInclusive;
    private final byte[] prefix;
    private final byte[] family;
    private final byte[] qualifier;

    private KeyRange(final Kind kind, final byte[] start, final boolean startInclusive, final byte[] end,
            final boolean endInclusive, final byte[] prefix, final byte[] family, final byte[] qualifier) {
        this.kind = kind;
        this.start = start;
        this.startInclusive = startInclusive;
        this.end = end;
        this.endInclusive = endInclusive;
        this.prefix = prefix;
        this.family = family;
        this.qualifier = qualifier;
    }

    /** Every row. */
    public static KeyRange all() {
        return ALL_ROWS;
    }

    /** One row. */
    public static KeyRange row(final byte[] row) {
        return new KeyRange(Kind.ROW, row, true, row, true, null, null, null);
    }

    /** Every label and version of one cell: this row, family and qualifier. */
    public static KeyRange cell(final byte[] row, final byte[] family, final byte[] qualifier) {
        return new KeyRange(Kind.CELL, row, true, row, true, null, family, qualifier);
    }

    /** Every row starting with {@code prefix}. */
    public static KeyRange prefix(final byte[] prefix) {
        return new KeyRange(Kind.PREFIX, prefix, true, followingPrefix(prefix), false, prefix, null, null);
    }

    /** From {@code start} to {@code end}, both included. */
    public static KeyRange closed(final byte[] start, final byte[] end) {
        return rows(start, true, end, true);
    }

    /** From {@code start}, included, to the last row. */
    public static KeyRange atLeast(final byte[] start) {
        return rows(start, true, null, false);
    }

    /** From a start row to an end row; a null row is unbounded. */
    public static KeyRange rows(final byte[] start, final boolean startInclusive, final byte[] end, final boolean endInclusive) {
        return new KeyRange(Kind.ROWS, start, startInclusive, end, endInclusive, null, null, null);
    }

    public Kind kind() {
        return kind;
    }

    /** The first row, or null when unbounded. */
    public byte[] start() {
        return start;
    }

    public boolean startInclusive() {
        return startInclusive;
    }

    /** The last row, or null when unbounded. */
    public byte[] end() {
        return end;
    }

    public boolean endInclusive() {
        return endInclusive;
    }

    /** The prefix of a {@link Kind#PREFIX} range. */
    public byte[] prefix() {
        return prefix;
    }

    /** The family of a {@link Kind#CELL} range. */
    public byte[] family() {
        return family;
    }

    /** The qualifier of a {@link Kind#CELL} range. */
    public byte[] qualifier() {
        return qualifier;
    }

    /** Whether a row is in the range. */
    public boolean containsRow(final byte[] row) {
        if (start != null) {
            final int c = Arrays.compareUnsigned(row, start);
            if (c < 0 || (c == 0 && !startInclusive)) {
                return false;
            }
        }
        if (end != null) {
            final int c = Arrays.compareUnsigned(row, end);
            if (c > 0 || (c == 0 && !endInclusive)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The smallest row greater than every row starting with {@code prefix}: the prefix without its
     * trailing FF bytes, with its last byte incremented. Null when there is none (only FF bytes).
     */
    public static byte[] followingPrefix(final byte[] prefix) {
        int n = prefix.length;
        while (n > 0 && prefix[n - 1] == (byte) 0xff) {
            n--;
        }
        if (n == 0) {
            return null;
        }
        final byte[] next = Arrays.copyOf(prefix, n);
        next[n - 1]++;
        return next;
    }

    /**
     * Merges overlapping and touching row ranges, like Accumulo's {@code Range.mergeOverlapping}.
     * Ranges that don't merge with another are returned as they are. Cell ranges aren't merged.
     */
    public static List<KeyRange> mergeOverlapping(final Collection<KeyRange> ranges) {
        if (ranges.size() <= 1) {
            return new ArrayList<>(ranges);
        }
        final List<KeyRange> out = new ArrayList<>(ranges.size());
        final List<KeyRange> sorted = new ArrayList<>(ranges.size());
        for (final KeyRange r : ranges) {
            if (r.kind == Kind.CELL) {
                out.add(r);
            } else {
                sorted.add(r);
            }
        }
        sorted.sort(Comparator.comparing((KeyRange r) -> r.start, KeyRange::compareStarts)
                .thenComparing(r -> !r.startInclusive));
        KeyRange current = null;
        for (final KeyRange r : sorted) {
            if (current == null) {
                current = r;
                continue;
            }
            if (current.end == null) {
                continue; // runs to the last row: contains the rest
            }
            final boolean joins;
            if (r.start == null) {
                joins = true;
            } else {
                final int c = Arrays.compareUnsigned(r.start, current.end);
                joins = c < 0 || (c == 0 && (current.endInclusive || r.startInclusive));
            }
            if (!joins) {
                out.add(current);
                current = r;
                continue;
            }
            final boolean extends_;
            if (r.end == null) {
                extends_ = true;
            } else {
                final int c = Arrays.compareUnsigned(r.end, current.end);
                extends_ = c > 0 || (c == 0 && r.endInclusive && !current.endInclusive);
            }
            if (extends_) {
                current = rows(current.start, current.startInclusive, r.end, r.endInclusive);
            }
        }
        out.add(current);
        return out;
    }

    private static int compareStarts(final byte[] a, final byte[] b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return Arrays.compareUnsigned(a, b);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof KeyRange r && r.kind == kind && r.startInclusive == startInclusive
                && r.endInclusive == endInclusive && Arrays.equals(r.start, start) && Arrays.equals(r.end, end)
                && Arrays.equals(r.family, family) && Arrays.equals(r.qualifier, qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, Arrays.hashCode(start), startInclusive, Arrays.hashCode(end), endInclusive,
                Arrays.hashCode(family), Arrays.hashCode(qualifier));
    }

    @Override
    public String toString() {
        return kind + (startInclusive ? "[" : "(") + (start == null ? "-inf" : Arrays.toString(start)) + ", "
                + (end == null ? "+inf" : Arrays.toString(end)) + (endInclusive ? "]" : ")");
    }
}
