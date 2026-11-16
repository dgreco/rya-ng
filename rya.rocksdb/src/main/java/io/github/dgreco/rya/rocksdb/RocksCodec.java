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
package io.github.dgreco.rya.rocksdb;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.KeyRange;

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
        final ByteArrayOutputStream out = new ByteArrayOutputStream(row.length + family.length + qualifier.length
                + visibility.length + 8);
        escape(row, out);
        out.write(0);
        out.write(1);
        out.writeBytes(family);
        out.write(0);
        out.writeBytes(qualifier);
        out.write(0);
        out.writeBytes(visibility);
        return out.toByteArray();
    }

    private static void checkNoZero(final byte[] part, final String what) {
        for (final byte b : part) {
            if (b == 0) {
                throw new IllegalArgumentException("A " + what + " can't hold the byte 00 on RocksDB");
            }
        }
    }

    private static void escape(final byte[] row, final ByteArrayOutputStream out) {
        for (final byte b : row) {
            out.write(b);
            if (b == 0) {
                out.write(0xff);
            }
        }
    }

    private static byte[] escaped(final byte[] row, final int... suffix) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(row.length + 4);
        escape(row, out);
        for (final int b : suffix) {
            out.write(b);
        }
        return out.toByteArray();
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
        final ByteArrayOutputStream row = new ByteArrayOutputStream(key.length);
        int i = 0;
        while (true) {
            final byte b = key[i++];
            if (b != 0) {
                row.write(b);
            } else if (key[i++] == 1) {
                break;
            } else {
                row.write(0);
            }
        }
        final int familyEnd = indexOfZero(key, i);
        final int qualifierEnd = indexOfZero(key, familyEnd + 1);
        final byte[] family = Arrays.copyOfRange(key, i, familyEnd);
        final byte[] qualifier = Arrays.copyOfRange(key, familyEnd + 1, qualifierEnd);
        final byte[] visibility = Arrays.copyOfRange(key, qualifierEnd + 1, key.length);
        if (counter) {
            return new Cell(row.toByteArray(), family, qualifier, visibility, 0, stored);
        }
        return new Cell(row.toByteArray(), family, qualifier, visibility, timestamp(stored),
                stored.length == TIMESTAMP_BYTES ? EMPTY : Arrays.copyOfRange(stored, TIMESTAMP_BYTES, stored.length));
    }

    private static int indexOfZero(final byte[] key, final int from) {
        for (int i = from; i < key.length; i++) {
            if (key[i] == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("Not a rya-ng key: " + key.length + " bytes");
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
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        escape(range.start(), out);
        out.write(0);
        out.write(1);
        out.writeBytes(range.family());
        out.write(0);
        out.writeBytes(range.qualifier());
        out.write(last);
        return out.toByteArray();
    }
}
