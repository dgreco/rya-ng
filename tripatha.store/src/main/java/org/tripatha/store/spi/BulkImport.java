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

import java.util.List;
import java.util.Optional;

/**
 * Sorted files of a table in the backend's own format, and their import: the bulk build's way
 * into a store, bypassing the write path (SCALE-STUDY.md 6.2, O3). A {@link Store} offers it
 * through {@link Store#bulk()} when its backend can import files.
 * <p>
 * The caller hands a file its cells in key order: row, family, qualifier and visibility, each
 * compared as unsigned bytes, one cell per such key (the order every backend stores, see
 * {@code RocksCodecTest} and {@code QualifierOrderTest}). Statement, index and metadata tables
 * take {@link BulkFile#put}s; counter tables take {@link BulkFile#counter} deltas, which the
 * backend adds to what the table holds on import, as it adds online deltas.
 * <p>
 * Importing a file again counts its counters again on some backends and not at all on others
 * (SCALE-STUDY.md 4.17), so a caller imports each file once and records it.
 */
public interface BulkImport {

    /**
     * A new file of {@code table}'s cells.
     *
     * @param dir where to write it: a local directory, or a Hadoop file system URI that the
     *        backend's servers can read
     * @param name the file's name, without extension
     * @param counterTimestamp the timestamp of its counter cells, for a backend whose counters
     *        are cell versions (HBase): give each import one of its own
     */
    BulkFile newFile(String table, String dir, String name, long counterTimestamp);

    /** Imports files of {@code table} written by {@link #newFile}. Each file once. */
    void importFiles(String table, List<Written> files);

    /**
     * Why counters can't be imported into {@code table} now, or empty when they can. A backend
     * that keeps one version per counter (HBase's increment mode) can only take counters into a
     * table that doesn't hold them.
     */
    default Optional<String> refusesCounters(final String table) {
        return Optional.empty();
    }

    /**
     * Whether a split point is the last row of the tablet before it, as in Accumulo, rather than
     * the first row of the region after it (HBase; RocksDB's hints). A file the bulk build writes
     * per tablet covers {@code (previous, point]} in the first case and {@code [previous, point)}
     * in the second, so each falls in one tablet.
     */
    default boolean splitPointEndsTablet() {
        return false;
    }

    /** One file being written, in key order. */
    interface BulkFile extends AutoCloseable {
        /** A cell of a statement, index or metadata table. */
        void put(byte[] row, byte[] family, byte[] qualifier, byte[] visibility, long timestamp, byte[] value);

        /** A counter delta, in a counter table. */
        void counter(byte[] row, byte[] family, byte[] qualifier, byte[] visibility, long delta);

        /** Ends the file: what to import, or null when it got no cell (there is nothing to import). */
        Written finish();

        /** Abandons a file that wasn't finished, deleting what was written. Does nothing after {@link #finish()}. */
        @Override
        void close();
    }

    /** A finished file: where it is, its first and last rows, and how many cells it holds. */
    record Written(String path, byte[] firstRow, byte[] lastRow, long cells) {
    }
}
