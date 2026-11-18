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
package org.tripatha.store.spi;

import java.nio.charset.StandardCharsets;

/**
 * What {@link Store#countIntersection} counts.
 *
 * @param subjects the distinct subjects found under every family
 * @param rows the sum of their values: the rows they give in a join, counting each family's cells
 */
public record IntersectionCount(long subjects, long rows) {
    /** None. */
    public static final IntersectionCount NONE = new IntersectionCount(0, 0);

    /** Both sums. */
    public IntersectionCount plus(final IntersectionCount other) {
        return new IntersectionCount(Math.addExact(subjects, other.subjects), Math.addExact(rows, other.rows));
    }

    /** A decimal value, as {@link Store#intersect} writes them. */
    public static long parse(final byte[] value) {
        return Long.parseLong(new String(value, StandardCharsets.US_ASCII));
    }
}
