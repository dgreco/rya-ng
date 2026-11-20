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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.EnvOptions;
import org.rocksdb.IngestExternalFileOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDBException;
import org.rocksdb.SstFileWriter;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.BulkImport;

/**
 * SST files written with the store's own key and value encoding ({@link RocksCodec}), ingested
 * into a table's column family (SCALE-STUDY.md 4.17). Counter deltas are {@code merge} entries,
 * which the add operator applies on reads and compactions like online deltas. Ingestion moves the
 * files into the database, so a file that is gone was ingested.
 */
final class RocksBulkImport implements BulkImport {
    private final RocksDatabase database;

    RocksBulkImport(final RocksDatabase database) {
        this.database = database;
    }

    @Override
    public BulkFile newFile(final String table, final String dir, final String name, final long counterTimestamp) {
        if (!database.exists(table)) {
            throw new RyaDAOException("No table " + table + " to write a bulk file for");
        }
        final Path file = Path.of(dir.startsWith("file:") ? java.net.URI.create(dir).getPath() : dir).resolve(name + ".sst");
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(file);
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
        return new SstFile(database.isCounters(table), file);
    }

    @Override
    public void importFiles(final String table, final List<Written> files) {
        if (files.isEmpty()) {
            return;
        }
        final List<String> paths = new ArrayList<>(files.size());
        for (final Written w : files) {
            paths.add(w.path());
        }
        try (IngestExternalFileOptions options = new IngestExternalFileOptions().setMoveFiles(true)) {
            database.db().ingestExternalFile(database.family(table), paths, options);
        } catch (final RocksDBException e) {
            throw new RyaDAOException("Could not ingest " + paths + " into " + table, e);
        }
    }

    private final class SstFile implements BulkFile {
        private final boolean counters;
        private final Path file;
        private final EnvOptions env = new EnvOptions();
        private final Options options = database.sstOptions();
        private final SstFileWriter writer = new SstFileWriter(env, options);
        private byte[] first;
        private byte[] last;
        private long cells;
        private boolean done;

        SstFile(final boolean counters, final Path file) {
            this.counters = counters;
            this.file = file;
            try {
                writer.open(file.toString());
            } catch (final RocksDBException e) {
                release();
                throw new RyaDAOException("Could not open " + file, e);
            }
        }

        @Override
        public void put(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility, final long timestamp,
                final byte[] value) {
            if (counters) {
                throw new IllegalArgumentException("a counter table takes counter deltas only");
            }
            add(row, RocksCodec.key(row, family, qualifier, visibility), RocksCodec.value(timestamp, value), false);
        }

        @Override
        public void counter(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility,
                final long delta) {
            if (!counters) {
                throw new IllegalArgumentException("only a counter table takes counter deltas");
            }
            add(row, RocksCodec.key(row, family, qualifier, visibility), RocksCodec.counter(delta), true);
        }

        private void add(final byte[] row, final byte[] key, final byte[] value, final boolean merge) {
            try {
                if (merge) {
                    writer.merge(key, value);
                } else {
                    writer.put(key, value);
                }
            } catch (final RocksDBException e) {
                throw new RyaDAOException("Could not write to " + file + " (keys must be in order, one per key)", e);
            }
            if (first == null) {
                first = row;
            }
            last = row;
            cells++;
        }

        @Override
        public Written finish() {
            try {
                if (cells == 0) {
                    return null;
                }
                writer.finish();
                done = true;
                return new Written(file.toString(), first, last, cells);
            } catch (final RocksDBException e) {
                throw new RyaDAOException("Could not finish " + file, e);
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            release();
            if (!done) {
                try {
                    Files.deleteIfExists(file);
                } catch (final IOException e) {
                    // left for the build's clean-up
                }
            }
        }

        private void release() {
            writer.close();
            options.close();
            env.close();
        }
    }
}
