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

import java.util.Collection;
import java.util.List;

/**
 * A storage backend, as Tripatha uses it: sorted tables of labelled cells. Each cell has a row,
 * a family, a qualifier, a visibility label, a timestamp and a value, and cells sort by row,
 * family, qualifier and label, newest first (Accumulo's key order).
 * <p>
 * Every operation maps to one native operation of the backend, so this layer adds no round
 * trips. A store may wrap a client its caller owns; {@link #close()} then leaves the client open.
 */
public interface Store extends AutoCloseable {

    /** The backend's id: {@code accumulo}, {@code hbase}, {@code rocksdb}. */
    String backend();

    /** What the backend does natively. */
    Capabilities capabilities();

    /** Creating, listing and deleting tables. */
    TableAdmin tables();

    /**
     * The backend's import of sorted files (the bulk build, SCALE-STUDY.md O3), or empty when it
     * has none.
     */
    default java.util.Optional<BulkImport> bulk() {
        return java.util.Optional.empty();
    }

    /** The account and its labels. */
    StoreAccess access();

    /**
     * A writer for every table. Writes are buffered until {@link CellWriter#flush()}, which sends
     * those of every table. Not thread-safe: use one per writing thread.
     */
    CellWriter newWriter(WriterOptions options);

    /** The cells of one range, in key order. Close the scanner when done. */
    CellScanner scan(String table, KeyRange range, ScanSpec spec);

    /**
     * The cells of several ranges, read in parallel, in no particular order. A cell in two
     * overlapping ranges may be returned twice.
     */
    CellScanner batchScan(String table, Collection<KeyRange> ranges, ScanSpec spec);

    /**
     * Deletes every cell of the ranges that the spec's labels can see (and that its columns
     * select), each with its own label and timestamp.
     */
    void deleteVisible(String table, Collection<KeyRange> ranges, ScanSpec spec, WriterOptions options);

    /**
     * The value of a counter cell as read back: the backend has added up its deltas (see
     * {@link RowMutation#addToCounter}).
     */
    long counterValue(byte[] value);

    /**
     * Whether this store does {@link #intersect} on this table: in its servers (Accumulo, with
     * Tripatha's iterator jar on them) or with native seeks (RocksDB).
     */
    default boolean canIntersect(final String table) {
        return false;
    }

    /**
     * The entity index's intersection, done by the store (SCALE-STUDY.md O23): in each row, the
     * subjects (a cell's qualifier up to its first {@code 01} byte) found under every one of the
     * families. Each comes as one cell: the row, an empty family, the subject as qualifier, an
     * empty visibility, and as value the product of its cells under each family, in decimal. Only
     * the cells the spec's labels and time range show count. Subjects come in order within a row;
     * rows in any order. Only where {@link #canIntersect} says so.
     */
    default CellScanner intersect(final String table, final List<byte[]> rows, final List<byte[]> families,
            final ScanSpec spec) {
        throw new UnsupportedOperationException(backend() + " does not intersect");
    }

    /**
     * How many subjects {@link #intersect} would return, and the sum of their values, without
     * returning them. This default adds them up as they come; a store whose servers intersect
     * counts there instead, so only a count per row comes back.
     */
    default IntersectionCount countIntersection(final String table, final List<byte[]> rows, final List<byte[]> families,
            final ScanSpec spec) {
        long subjects = 0;
        long matches = 0;
        try (CellScanner cells = intersect(table, rows, families, spec)) {
            while (cells.hasNext()) {
                subjects++;
                matches = Math.addExact(matches, IntersectionCount.parse(cells.next().value()));
            }
        }
        return new IntersectionCount(subjects, matches);
    }

    /** Releases what the store opened itself. A client supplied by the caller stays open. */
    @Override
    void close();
}
