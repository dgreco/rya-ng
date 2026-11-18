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
package org.tripatha.rocksdb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.KeyRange;

/**
 * How a cell becomes a RocksDB key and value, so that RocksDB's own byte order is Accumulo's key
 * order (row, family, qualifier, visibility).
 *
 * <pre>
 * key   = esc(row) 00 01 family 00 qualifier 00 visibility
 * value = timestamp (8 bytes, big-endian) value          statement, index and metadata tables
 *       = sum (8 bytes, little-endian)                   counter tables
 * </pre>
 *
 * <p>A row may hold any byte and may be empty, so it is escaped: {@code esc} writes a {@code 00}
 * byte as {@code 00 FF}, and {@code 00 01} ends the row. A row then sorts before every row it is
 * a prefix of ({@code 00 01} is below {@code 00 FF} and below any other byte), and the cells of
 * one row are together. The family and the qualifier may not hold a {@code 00} byte (as on HBase);
 * a visibility expression never does.
 *
 * <p>There is one version per cell: the timestamp is in the value, not in the key.
 */
final class RocksCodec {
    private static final byte[] EMPTY = new byte[0];
    private static final int TIMESTAMP_BYTES = Long.BYTES;

    private RocksCodec() {
    }

    /** The key of a cell. */
    static byte[] key(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility) {
        checkNoZero(family, "column family");
        checkNoZero(qualifier, "column qualifier");
        checkNoZero(visibility, "visibility");
        final byte[] out = new byte[escapedLength(row) + 2 + family.length + 1 + qualifier.length + 1 + visibility.length];
        int pos = escape(row, out, 0);
        out[pos++] = 0;
        out[pos++] = 1;
        pos = append(family, out, pos);
        out[pos++] = 0;
        pos = append(qualifier, out, pos);
        out[pos++] = 0;
        append(visibility, out, pos);
        return out;
    }

    /** The start of every key of one row and family: {@code esc(row) 00 01 family 00}, the qualifier next. */
    static byte[] familyPrefix(final byte[] row, final byte[] family) {
        checkNoZero(family, "column family");
        final byte[] out = new byte[escapedLength(row) + 2 + family.length + 1];
        int pos = escape(row, out, 0);
        out[pos++] = 0;
        out[pos++] = 1;
        pos = append(family, out, pos);
        out[pos] = 0;
        return out;
    }

    private static void checkNoZero(final byte[] part, final String what) {
        for (final byte b : part) {
            if (b == 0) {
                throw new IllegalArgumentException("A " + what + " can't hold the byte 00 on RocksDB");
            }
        }
    }

    /** The length of a row once escaped: each {@code 00} becomes {@code 00 FF}. */
    private static int escapedLength(final byte[] row) {
        int length = row.length;
        for (final byte b : row) {
            if (b == 0) {
                length++;
            }
        }
        return length;
    }

    /** Writes the escaped row at {@code pos}, copying the runs between {@code 00} bytes whole; returns the next position. */
    private static int escape(final byte[] row, final byte[] out, int pos) {
        int from = 0;
        for (int i = 0; i < row.length; i++) {
            if (row[i] == 0) {
                pos = copy(row, from, i + 1, out, pos);
                out[pos++] = (byte) 0xff;
                from = i + 1;
            }
        }
        return copy(row, from, row.length, out, pos);
    }

    private static int append(final byte[] part, final byte[] out, final int pos) {
        System.arraycopy(part, 0, out, pos, part.length);
        return pos + part.length;
    }

    private static int copy(final byte[] from, final int start, final int end, final byte[] out, final int pos) {
        System.arraycopy(from, start, out, pos, end - start);
        return pos + end - start;
    }

    private static byte[] escaped(final byte[] row, final int... suffix) {
        final byte[] out = new byte[escapedLength(row) + suffix.length];
        int pos = escape(row, out, 0);
        for (final int b : suffix) {
            out[pos++] = (byte) b;
        }
        return out;
    }

    /** The value of a statement, index or metadata cell. */
    static byte[] value(final long timestamp, final byte[] value) {
        return ByteBuffer.allocate(TIMESTAMP_BYTES + value.length).putLong(timestamp).put(value).array();
    }

    /** The timestamp in a stored value. */
    static long timestamp(final byte[] stored) {
        return ByteBuffer.wrap(stored).getLong();
    }

    /** A counter delta, and a counter's sum, as RocksDB's 64-bit add operator reads and writes them. */
    static byte[] counter(final long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    static long counter(final byte[] stored) {
        if (stored.length != Long.BYTES) {
            throw new IllegalArgumentException("Not a RocksDB counter value: " + stored.length + " bytes");
        }
        return ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /**
     * The cell of a stored key and value.
     *
     * @param counter whether the value is a counter's sum: such a cell has no timestamp (0), and
     *        its value is the sum as stored
     */
    static Cell cell(final byte[] key, final byte[] stored, final boolean counter) {
        // The row ends at 00 01; before that, a 00 and the byte after it (FF) stand for one 00.
        int end = 0;
        int zeros = 0;
        while (key[end] != 0 || key[end + 1] != 1) {
            if (key[end] == 0) {
                zeros++;
                end += 2;
            } else {
                end++;
            }
        }
        final byte[] row = zeros == 0 ? Arrays.copyOf(key, end) : unescape(key, end, zeros);
        final int i = end + 2;
        final int familyEnd = indexOfZero(key, i);
        final int qualifierEnd = indexOfZero(key, familyEnd + 1);
        final byte[] family = Arrays.copyOfRange(key, i, familyEnd);
        final byte[] qualifier = Arrays.copyOfRange(key, familyEnd + 1, qualifierEnd);
        final byte[] visibility = Arrays.copyOfRange(key, qualifierEnd + 1, key.length);
        if (counter) {
            return new Cell(row, family, qualifier, visibility, 0, stored);
        }
        return new Cell(row, family, qualifier, visibility, timestamp(stored),
                stored.length == TIMESTAMP_BYTES ? EMPTY : Arrays.copyOfRange(stored, TIMESTAMP_BYTES, stored.length));
    }

    /** The row of a stored key: up to {@code 00 01}, with escaped {@code 00} bytes restored. */
    static byte[] row(final byte[] key) {
        int end = 0;
        int zeros = 0;
        while (end + 1 < key.length && (key[end] != 0 || key[end + 1] != 1)) {
            if (key[end] == 0) {
                zeros++;
                end += 2;
            } else {
                end++;
            }
        }
        end = Math.min(end, key.length);
        return zeros == 0 ? Arrays.copyOf(key, end) : unescape(key, end, zeros);
    }

    /** The row in {@code key[0, end)}, which holds {@code zeros} escaped {@code 00} bytes, copied run by run. */
    private static byte[] unescape(final byte[] key, final int end, final int zeros) {
        final byte[] row = new byte[end - zeros];
        int pos = 0;
        int from = 0;
        for (int i = 0; i < end; i++) {
            if (key[i] == 0) {
                pos = copy(key, from, i + 1, row, pos);
                i++; // the escape byte
                from = i + 1;
            }
        }
        copy(key, from, end, row, pos);
        return row;
    }

    private static int indexOfZero(final byte[] key, final int from) {
        for (int i = from; i < key.length; i++) {
            if (key[i] == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("Not a Tripatha key: " + key.length + " bytes");
    }

    /** The first key of a range, included; empty for the first key of the table. */
    static byte[] lowerBound(final KeyRange range) {
        if (range.kind() == KeyRange.Kind.CELL) {
            return cellPrefix(range, 0);
        }
        if (range.start() == null) {
            return EMPTY;
        }
        // 00 02 is above every cell of the row (00 01 …) and below every longer row.
        return range.startInclusive() ? escaped(range.start()) : escaped(range.start(), 0, 2);
    }

    /** The key a range ends before; null when it runs to the end of the table. */
    static byte[] upperBound(final KeyRange range) {
        if (range.kind() == KeyRange.Kind.CELL) {
            return cellPrefix(range, 1);
        }
        if (range.end() == null) {
            return null;
        }
        return range.endInclusive() ? escaped(range.end(), 0, 2) : escaped(range.end());
    }

    /** The keys of one row, family and qualifier start with this and {@code 00}; they end before it and {@code 01}. */
    private static byte[] cellPrefix(final KeyRange range, final int last) {
        checkNoZero(range.family(), "column family");
        checkNoZero(range.qualifier(), "column qualifier");
        final byte[] out = new byte[escapedLength(range.start()) + 2 + range.family().length + 1 + range.qualifier().length + 1];
        int pos = escape(range.start(), out, 0);
        out[pos++] = 0;
        out[pos++] = 1;
        pos = append(range.family(), out, pos);
        out[pos++] = 0;
        pos = append(range.qualifier(), out, pos);
        out[pos] = (byte) last;
        return out;
    }
}
