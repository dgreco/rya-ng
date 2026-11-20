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

import java.util.Arrays;

/**
 * A cell's key without its timestamp: row, family, qualifier and visibility, compared in that
 * order as unsigned bytes, the order every backend stores. The bulk build keeps one cell per
 * such key, as a scan of a store returns one.
 */
public record CellKey(byte[] row, byte[] family, byte[] qualifier, byte[] visibility) implements Comparable<CellKey> {

    /** The bytes it holds, for the build's memory accounting. */
    long bytes() {
        return (long) row.length + family.length + qualifier.length + visibility.length;
    }

    @Override
    public int compareTo(final CellKey o) {
        int c = Arrays.compareUnsigned(row, o.row);
        if (c == 0) {
            c = Arrays.compareUnsigned(family, o.family);
        }
        if (c == 0) {
            c = Arrays.compareUnsigned(qualifier, o.qualifier);
        }
        if (c == 0) {
            c = Arrays.compareUnsigned(visibility, o.visibility);
        }
        return c;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof CellKey k && Arrays.equals(row, k.row) && Arrays.equals(family, k.family)
                && Arrays.equals(qualifier, k.qualifier) && Arrays.equals(visibility, k.visibility);
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(row);
        h = 31 * h + Arrays.hashCode(family);
        h = 31 * h + Arrays.hashCode(qualifier);
        return 31 * h + Arrays.hashCode(visibility);
    }

    @Override
    public String toString() {
        return new String(row, java.nio.charset.StandardCharsets.UTF_8) + " " + new String(family, java.nio.charset.StandardCharsets.UTF_8)
                + ":" + new String(qualifier, java.nio.charset.StandardCharsets.UTF_8) + " [" + new String(visibility,
                        java.nio.charset.StandardCharsets.UTF_8) + "]";
    }
}
