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

import java.util.HashMap;
import java.util.Map;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.Durability;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.TableWriter;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.Trace;
import org.tripatha.store.visibility.VisibilityExpression;

/**
 * Buffers the writes of every table in one RocksDB write batch. A flush writes the batch as a
 * whole: all of it is stored, or none of it.
 *
 * <p>Of the {@link WriterOptions}, {@code maxMemoryBytes} is the size at which the batch is written
 * without waiting for a flush, and the durability chooses the write-ahead log: none for
 * {@link Durability#NONE}, synced to disk for {@link Durability#SYNC}, written but not synced
 * otherwise. The latency, timeout and thread settings mean nothing for a local database.
 */
final class RocksCellWriter implements CellWriter {
    private final RocksDatabase database;
    private final long maxBytes;
    private final WriteOptions options;
    private final WriteBatch batch = new WriteBatch();
    private final Map<String, TableWriter> tables = new HashMap<>();
    private boolean closed;

    RocksCellWriter(final RocksDatabase database, final WriterOptions options) {
        this.database = database;
        this.maxBytes = options.maxMemoryBytes();
        this.options = new WriteOptions().setDisableWAL(options.durability() == Durability.NONE)
                .setSync(options.durability() == Durability.SYNC);
        database.retain();
    }

    @Override
    public TableWriter table(final String table) {
        return tables.computeIfAbsent(table, Writer::new);
    }

    @Override
    public void flush() {
        final Trace trace = StoreTrace.current();
        final long t0 = System.nanoTime();
        try {
            write();
        } finally {
            if (trace != null) {
                trace.flushed(System.nanoTime() - t0);
            }
        }
    }

    private void write() {
        if (batch.count() == 0) {
            return;
        }
        try {
            database.db().write(options, batch);
            batch.clear();
        } catch (final RocksDBException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        } finally {
            batch.close();
            options.close();
            database.release();
        }
    }

    /** One table's writes. */
    private final class Writer implements TableWriter {
        private final String table;

        Writer(final String table) {
            this.table = table;
        }

        @Override
        public void write(final RowMutation m) {
            final boolean counters = database.isCounters(table);
            final long before = batch.getDataSize();
            int deletes = 0;
            try {
                for (final RowMutation.Update u : m.updates()) {
                    // Refuses a malformed label here, rather than storing a cell no read could judge.
                    VisibilityExpression.parse(u.visibility());
                    final byte[] key = RocksCodec.key(m.row(), u.family(), u.qualifier(), u.visibility());
                    switch (u.op()) {
                        case PUT -> {
                            if (counters) {
                                // The add operator would count on from a value that isn't a sum.
                                throw new RyaDAOException("The counter table " + table + " only takes counter deltas");
                            }
                            final long timestamp = u.timestamp() == RowMutation.SERVER_TIME ? System.currentTimeMillis() : u.timestamp();
                            batch.put(database.family(table), key, RocksCodec.value(timestamp, u.value()));
                        }
                        case DELETE -> {
                            if (counters || u.timestamp() == RowMutation.SERVER_TIME || atOrBefore(key, u.timestamp())) {
                                batch.delete(database.family(table), key);
                                deletes++;
                            }
                        }
                        case COUNTER -> {
                            if (!counters) {
                                throw new RyaDAOException("The table " + table + " isn't a counter table");
                            }
                            batch.merge(database.family(table), key, RocksCodec.counter(u.delta()));
                        }
                    }
                }
            } catch (final RocksDBException e) {
                throw new RyaDAOException(e);
            }
            final Trace trace = StoreTrace.current();
            if (trace != null) {
                trace.wrote(table, m.updates().size(), deletes, batch.getDataSize() - before);
            }
            if (batch.getDataSize() >= maxBytes) {
                RocksCellWriter.this.write();
            }
        }

        /**
         * Whether the stored cell is at or before a timestamp, which is what a timestamped delete
         * removes. The buffered writes are stored first, so the cell read is the latest one.
         */
        private boolean atOrBefore(final byte[] key, final long timestamp) throws RocksDBException {
            RocksCellWriter.this.write();
            final byte[] stored = database.db().get(database.family(table), key);
            return stored != null && RocksCodec.timestamp(stored) <= timestamp;
        }
    }
}
