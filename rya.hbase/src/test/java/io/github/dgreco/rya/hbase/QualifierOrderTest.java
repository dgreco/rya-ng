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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.hbase.KeyValue;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Cell;

/** The HBase qualifier (family 00 qualifier 00 visibility) sorts and decodes like Accumulo's three key parts. */
class QualifierOrderTest {

    private static byte[] random(final Random r) {
        final byte[] b = new byte[r.nextInt(4)];
        for (int i = 0; i < b.length; i++) {
            // never 00; favour 01 and FF, the bytes next to the separator and at the top
            final int pick = r.nextInt(6);
            b[i] = (byte) (pick == 0 ? 1 : pick == 1 ? 0xff : 2 + r.nextInt(250));
        }
        return b;
    }

    @Test
    void encodedQualifiersSortLikeAccumuloKeys() {
        final Random r = new Random(7);
        final List<byte[][]> triples = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            triples.add(new byte[][] {random(r), random(r), random(r)});
        }
        final Comparator<byte[][]> accumulo = (x, y) -> {
            for (int i = 0; i < 3; i++) {
                final int c = Arrays.compareUnsigned(x[i], y[i]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
        final List<byte[][]> sorted = new ArrayList<>(triples);
        sorted.sort(accumulo);
        final List<byte[]> encoded = new ArrayList<>();
        for (final byte[][] t : triples) {
            encoded.add(HBaseCodec.qualifier(t[0], t[1], t[2]));
        }
        encoded.sort(Arrays::compareUnsigned);
        for (int i = 0; i < sorted.size(); i++) {
            final byte[][] t = sorted.get(i);
            assertArrayEquals(HBaseCodec.qualifier(t[0], t[1], t[2]), encoded.get(i), "position " + i);
        }
    }

    @Test
    void cellsDecodeBack() {
        final Random r = new Random(11);
        for (int i = 0; i < 1000; i++) {
            final byte[] row = random(r);
            final byte[] f = random(r);
            final byte[] q = random(r);
            final byte[] v = random(r);
            final Cell c = HBaseCodec.decode(new KeyValue(HBaseCodec.row(row), HBaseCodec.FAMILY, HBaseCodec.qualifier(f, q, v),
                    42L, new byte[] {9}));
            assertArrayEquals(row, c.row());
            assertArrayEquals(f, c.family());
            assertArrayEquals(q, c.qualifier());
            assertArrayEquals(v, c.visibility());
            assertEquals(42L, c.timestamp());
        }
    }

    @Test
    void refusesWhatCannotBeStored() {
        assertThrows(RyaDAOException.class, () -> HBaseCodec.qualifier(new byte[] {'a', 0}, new byte[0], new byte[0]));
        assertThrows(RyaDAOException.class, () -> HBaseCodec.row(new byte[HBaseCodec.MAX_ROW_BYTES + 1]));
        assertEquals(HBaseCodec.MAX_ROW_BYTES, HBaseCodec.row(new byte[HBaseCodec.MAX_ROW_BYTES]).length);
    }

    @Test
    void theEmptyRowIsStoredAsOneZeroByte() {
        assertArrayEquals(new byte[] {0}, HBaseCodec.row(new byte[0]));
        assertArrayEquals(new byte[0], HBaseCodec.ryaRow(new byte[] {0}));
    }
}
