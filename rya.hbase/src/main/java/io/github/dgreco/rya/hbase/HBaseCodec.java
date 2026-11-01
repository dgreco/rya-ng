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
package io.github.dgreco.rya.hbase;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.security.visibility.CellVisibility;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.visibility.VisibilityExpression;

/**
 * How rya-ng's cells are stored in HBase (BACKENDS-STUDY.md, section 3.2):
 * <pre>
 * HBase row        the rya-ng row, byte for byte (the empty row is stored as the single byte 00)
 * HBase family     c
 * HBase qualifier  family 00 qualifier 00 visibility
 * HBase timestamp  the cell's timestamp
 * HBase value      the cell's value
 * HBase tags       the visibility, as HBase's CellVisibility (enforced by VisibilityController)
 * </pre>
 * The visibility is part of the qualifier so that a statement stored under two labels stays two
 * cells (HBase's key has no visibility), and so that the label can be read back: HBase never
 * returns tags to clients. Neither the family nor the qualifier may contain the byte 00, so the
 * encoded qualifiers sort exactly like Accumulo's (family, qualifier, visibility).
 */
final class HBaseCodec {
    /** The only column family of every table. */
    static final byte[] FAMILY = {'c'};
    /** HBase's row length limit. */
    static final int MAX_ROW_BYTES = Short.MAX_VALUE;

    private static final byte SEP = 0;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] EMPTY_ROW = {0};
    private static final int MAX_CACHED_EXPRESSIONS = 10_000;

    /** Accumulo-syntax expression to HBase syntax. Expressions are few; the cache stops growing at a bound. */
    private static final Map<String, String> EXPRESSIONS = new ConcurrentHashMap<>();

    private HBaseCodec() {
    }

    /** The HBase row of a rya-ng row. */
    static byte[] row(final byte[] row) {
        if (row.length == 0) {
            return EMPTY_ROW;
        }
        if (row.length > MAX_ROW_BYTES) {
            throw new RyaDAOException("A row of " + row.length + " bytes is longer than HBase's limit of " + MAX_ROW_BYTES
                    + " bytes; the statement (usually its object literal) is too long to store on HBase");
        }
        return row;
    }

    /**
     * Whether no stored row can be in a range because its rows would be longer than HBase allows
     * (a lookup of a literal too long to have been stored). Such a range reads nothing, as on
     * Accumulo, instead of failing.
     */
    static boolean beyondRowLimit(final io.github.dgreco.rya.store.spi.KeyRange r) {
        return switch (r.kind()) {
            case ALL -> false;
            case ROW, CELL -> r.start().length > MAX_ROW_BYTES;
            case PREFIX -> r.prefix().length > MAX_ROW_BYTES;
            case ROWS -> r.start() != null && r.end() != null && commonPrefix(r.start(), r.end()) > MAX_ROW_BYTES;
        };
    }

    private static int commonPrefix(final byte[] a, final byte[] b) {
        final int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i] == b[i]) {
            i++;
        }
        return i;
    }

    /** A range bound: the empty row maps like any other row; null (unbounded) stays null. */
    static byte[] bound(final byte[] row) {
        return row == null ? null : row.length == 0 ? EMPTY_ROW : row;
    }

    /** The rya-ng row of an HBase row. */
    static byte[] ryaRow(final byte[] row) {
        return row.length == 1 && row[0] == 0 ? EMPTY : row;
    }

    /** family 00 qualifier 00 visibility. */
    static byte[] qualifier(final byte[] family, final byte[] qualifier, final byte[] visibility) {
        check(family, "column family (graph)");
        check(qualifier, "column qualifier");
        check(visibility, "visibility");
        final byte[] out = new byte[family.length + qualifier.length + visibility.length + 2];
        System.arraycopy(family, 0, out, 0, family.length);
        out[family.length] = SEP;
        System.arraycopy(qualifier, 0, out, family.length + 1, qualifier.length);
        out[family.length + 1 + qualifier.length] = SEP;
        System.arraycopy(visibility, 0, out, family.length + qualifier.length + 2, visibility.length);
        return out;
    }

    /** The qualifier prefix of every cell of a family: family 00. */
    static byte[] familyPrefix(final byte[] family) {
        final byte[] out = Arrays.copyOf(family, family.length + 1);
        out[family.length] = SEP;
        return out;
    }

    /** The qualifier prefix of every label of one column: family 00 qualifier 00. */
    static byte[] columnPrefix(final byte[] family, final byte[] qualifier) {
        final byte[] out = new byte[family.length + qualifier.length + 2];
        System.arraycopy(family, 0, out, 0, family.length);
        out[family.length] = SEP;
        System.arraycopy(qualifier, 0, out, family.length + 1, qualifier.length);
        out[out.length - 1] = SEP;
        return out;
    }

    /** A stored cell as rya-ng sees it. */
    static Cell decode(final org.apache.hadoop.hbase.Cell c) {
        final byte[] q = CellUtil.cloneQualifier(c);
        int first = -1;
        int last = -1;
        for (int i = 0; i < q.length; i++) {
            if (q[i] == SEP) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0 || first == last) {
            throw new RyaDAOException("Not a rya-ng cell: qualifier without its two separators");
        }
        return new Cell(ryaRow(CellUtil.cloneRow(c)), Arrays.copyOfRange(q, 0, first), Arrays.copyOfRange(q, first + 1, last),
                Arrays.copyOfRange(q, last + 1, q.length), c.getTimestamp(), CellUtil.cloneValue(c));
    }

    /** The HBase visibility of an Accumulo-syntax expression; null when unlabelled. */
    static CellVisibility visibility(final byte[] expression) {
        if (expression == null || expression.length == 0) {
            return null;
        }
        final String key = new String(expression, StandardCharsets.ISO_8859_1);
        String hbase = EXPRESSIONS.get(key);
        if (hbase == null) {
            hbase = VisibilityExpression.parse(expression).toHBase();
            if (EXPRESSIONS.size() < MAX_CACHED_EXPRESSIONS) {
                EXPRESSIONS.put(key, hbase);
            }
        }
        return new CellVisibility(hbase);
    }

    private static void check(final byte[] part, final String what) {
        for (final byte b : part) {
            if (b == SEP) {
                throw new RyaDAOException("A " + what + " containing the byte 00 can't be stored: "
                        + new String(part, StandardCharsets.UTF_8).replace('\u0000', '?'));
            }
        }
    }
}
