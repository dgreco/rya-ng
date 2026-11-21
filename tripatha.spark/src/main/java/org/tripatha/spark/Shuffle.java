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
package org.tripatha.spark;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.spark.Partitioner;

/**
 * What Spark's shuffle moves and how it orders it: one record per cell, keyed by the partition
 * that writes it (a part of a table's tablet) and the cell's key, so each partition receives the
 * cells of one file, combined and sorted as the RFile needs them. Plain classes with no-argument
 * constructors, for Kryo: no RDF4J object crosses the shuffle.
 */
final class Shuffle {
    private Shuffle() {
    }

    /** A cell's key without its timestamp: its partition, row, family, qualifier and label. */
    static final class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        int part;
        byte[] row;
        byte[] family;
        byte[] qualifier;
        byte[] visibility;

        Key() {
        }

        Key(final int part, final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility) {
            this.part = part;
            this.row = row;
            this.family = family;
            this.qualifier = qualifier;
            this.visibility = visibility;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Key k && part == k.part && Arrays.equals(row, k.row) && Arrays.equals(family, k.family)
                    && Arrays.equals(qualifier, k.qualifier) && Arrays.equals(visibility, k.visibility);
        }

        @Override
        public int hashCode() {
            int h = part;
            h = 31 * h + Arrays.hashCode(row);
            h = 31 * h + Arrays.hashCode(family);
            h = 31 * h + Arrays.hashCode(qualifier);
            return 31 * h + Arrays.hashCode(visibility);
        }
    }

    /**
     * A cell's content: a put's timestamp and value, or a counter's delta. Two values of one key
     * combine as the store would: deltas add up, and the newest put wins (on a tie, the greater
     * value, so a task run twice combines the same way).
     */
    static final class Value implements Serializable {
        private static final long serialVersionUID = 1L;
        boolean counter;
        long delta;
        long timestamp;
        byte[] value;

        Value() {
        }

        static Value put(final long timestamp, final byte[] value) {
            final Value v = new Value();
            v.timestamp = timestamp;
            v.value = value;
            return v;
        }

        static Value counter(final long delta) {
            final Value v = new Value();
            v.counter = true;
            v.delta = delta;
            return v;
        }

        /** {@code a} and {@code b} combined; may return (and change) {@code a}. */
        static Value combine(final Value a, final Value b) {
            if (a.counter != b.counter) {
                throw new IllegalStateException("a counter delta and a put for one cell");
            }
            if (a.counter) {
                a.delta = Math.addExact(a.delta, b.delta);
                return a;
            }
            if (b.timestamp > a.timestamp || b.timestamp == a.timestamp && Arrays.compareUnsigned(b.value, a.value) > 0) {
                return b;
            }
            return a;
        }
    }

    /** Accumulo's key order within a partition: bytes unsigned. */
    static final class Order implements Comparator<Key>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public int compare(final Key a, final Key b) {
            int c = Integer.compare(a.part, b.part);
            if (c == 0) {
                c = Arrays.compareUnsigned(a.row, b.row);
            }
            if (c == 0) {
                c = Arrays.compareUnsigned(a.family, b.family);
            }
            if (c == 0) {
                c = Arrays.compareUnsigned(a.qualifier, b.qualifier);
            }
            if (c == 0) {
                c = Arrays.compareUnsigned(a.visibility, b.visibility);
            }
            return c;
        }
    }

    /** The partition {@link Layout} gave each key. */
    static final class FilePartitioner extends Partitioner {
        private static final long serialVersionUID = 1L;
        private final int partitions;

        FilePartitioner(final int partitions) {
            this.partitions = partitions;
        }

        @Override
        public int numPartitions() {
            return partitions;
        }

        @Override
        public int getPartition(final Object key) {
            return ((Key) key).part;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof FilePartitioner p && p.partitions == partitions;
        }

        @Override
        public int hashCode() {
            return partitions;
        }
    }

    /**
     * Where each table's cells go, fixed by the driver before anything is written: the tables in
     * order, which hold counters, and each table's points. The points are its tablets' split rows
     * plus rows between them, so each file falls in one tablet; a point ends its range, as a split
     * row ends its tablet on Accumulo, so range {@code i} holds the rows in
     * {@code (point[i-1], point[i]]}. A table whose rows are few and long (the entity index: one
     * row per shard) also spreads each range over {@code buckets} files by the hash of the column.
     */
    static final class Layout implements Serializable {
        private static final long serialVersionUID = 1L;
        final List<String> tables;
        final boolean[] counters;
        final byte[][][] points;
        final int[] buckets;
        final int[] offsets;
        final int partitions;

        Layout(final List<String> tables, final boolean[] counters, final byte[][][] points, final int[] buckets) {
            this.tables = List.copyOf(tables);
            this.counters = counters.clone();
            this.points = points;
            this.buckets = buckets.clone();
            this.offsets = new int[tables.size()];
            int total = 0;
            for (int t = 0; t < tables.size(); t++) {
                offsets[t] = total;
                total = Math.addExact(total, Math.multiplyExact(points[t].length + 1, buckets[t]));
            }
            this.partitions = total;
        }

        int table(final String name) {
            return tables.indexOf(name);
        }

        /** The files of table {@code t}. */
        int files(final int t) {
            return (points[t].length + 1) * buckets[t];
        }

        /** The partition of a cell of table {@code t}. */
        int partition(final int t, final byte[] row, final byte[] family, final byte[] qualifier) {
            final byte[][] s = points[t];
            int lo = 0;
            int hi = s.length;
            while (lo < hi) {
                final int mid = (lo + hi) >>> 1;
                if (Arrays.compareUnsigned(s[mid], row) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            final int b = buckets[t];
            if (b == 1) {
                return offsets[t] + lo;
            }
            final int h = 31 * Arrays.hashCode(family) + Arrays.hashCode(qualifier);
            return offsets[t] + lo * b + Math.floorMod(h, b);
        }

        /** The table a partition belongs to. */
        int tableOf(final int partition) {
            int t = 0;
            while (t + 1 < offsets.length && offsets[t + 1] <= partition) {
                t++;
            }
            return t;
        }

        /** The partition's file, within its table. */
        int fileOf(final int partition) {
            return partition - offsets[tableOf(partition)];
        }
    }
}
