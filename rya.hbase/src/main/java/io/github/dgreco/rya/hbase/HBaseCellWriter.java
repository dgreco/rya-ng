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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.BufferedMutatorParams;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.security.visibility.CellVisibility;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.TableWriter;
import io.github.dgreco.rya.store.spi.WriterOptions;
import io.github.dgreco.rya.store.trace.StoreTrace;
import io.github.dgreco.rya.store.trace.Trace;

/**
 * A {@link CellWriter} with one {@link BufferedMutator} per table. A flush sends every table's
 * buffer at the same time and waits for all of them, so writing to seven tables costs one round
 * trip, not seven.
 * <p>
 * HBase carries one visibility per mutation, not per cell, so a row's changes become one
 * {@link Put}, {@link Delete} or {@link Increment} per operation and label.
 */
final class HBaseCellWriter implements CellWriter {
    private final HBaseStore store;
    private final WriterOptions options;
    private final Map<String, Writer> tables = new HashMap<>();

    HBaseCellWriter(final HBaseStore store, final WriterOptions options) {
        this.store = store;
        this.options = options;
    }

    static BufferedMutatorParams params(final TableName name, final WriterOptions options) {
        return new BufferedMutatorParams(name)
                .writeBufferSize(options.maxMemoryBytes())
                .setWriteBufferPeriodicFlushTimeoutMs(options.maxLatencyMs())
                .operationTimeout((int) Math.min(Integer.MAX_VALUE, options.timeoutMs()));
    }

    @Override
    public TableWriter table(final String table) {
        return tables.computeIfAbsent(table, this::open);
    }

    private Writer open(final String table) {
        try {
            return new Writer(table, store.connection().getBufferedMutator(params(store.name(table), options)));
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public void flush() {
        final Trace trace = StoreTrace.current();
        final long t0 = System.nanoTime();
        try {
            final List<Writer> writers = new ArrayList<>(tables.values());
            if (writers.size() == 1) {
                writers.get(0).mutator.flush();
            } else if (!writers.isEmpty()) {
                final List<Future<?>> flushes = new ArrayList<>(writers.size());
                for (final Writer w : writers) {
                    flushes.add(HBaseStore.POOL.submit(() -> {
                        w.mutator.flush();
                        return null;
                    }));
                }
                Throwable failure = null;
                for (final Future<?> f : flushes) {
                    try {
                        f.get();
                    } catch (final ExecutionException e) {
                        failure = failure == null ? e.getCause() : failure;
                    }
                }
                if (failure != null) {
                    throw failure instanceof RyaDAOException r ? r : new RyaDAOException(failure);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RyaDAOException(e);
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        } finally {
            if (trace != null) {
                trace.flushed(System.nanoTime() - t0);
            }
        }
    }

    @Override
    public void close() {
        try {
            flush();
        } finally {
            RyaDAOException failure = null;
            for (final Writer w : tables.values()) {
                try {
                    w.mutator.close();
                } catch (final IOException e) {
                    failure = failure == null ? new RyaDAOException(e) : failure;
                }
            }
            tables.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static org.apache.hadoop.hbase.client.Durability durability(final WriterOptions options) {
        return switch (options.durability()) {
            case DEFAULT -> org.apache.hadoop.hbase.client.Durability.USE_DEFAULT;
            case NONE -> org.apache.hadoop.hbase.client.Durability.SKIP_WAL;
            case LOG -> org.apache.hadoop.hbase.client.Durability.ASYNC_WAL;
            case FLUSH -> org.apache.hadoop.hbase.client.Durability.SYNC_WAL;
            case SYNC -> org.apache.hadoop.hbase.client.Durability.FSYNC_WAL;
        };
    }

    /** One table's buffer. */
    private final class Writer implements TableWriter {
        private final String table;
        private final BufferedMutator mutator;
        /** Looked up on the first counter delta, so tables without counters never read their descriptor. */
        private HBaseStore.CounterMode counterMode;

        private HBaseStore.CounterMode counterMode() {
            if (counterMode == null) {
                counterMode = store.counterMode(table);
            }
            return counterMode;
        }

        Writer(final String table, final BufferedMutator mutator) {
            this.table = table;
            this.mutator = mutator;
        }

        @Override
        public void write(final RowMutation m) {
            final List<Mutation> out = toMutations(m);
            final Trace trace = StoreTrace.current();
            if (trace != null) {
                int deletes = 0;
                long bytes = 0;
                for (final Mutation x : out) {
                    if (x instanceof Delete) {
                        deletes += x.size();
                    }
                    bytes += x.heapSize();
                }
                trace.wrote(table, m.updates().size(), deletes, bytes);
            }
            try {
                mutator.mutate(out);
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        private List<Mutation> toMutations(final RowMutation m) {
            final byte[] row = HBaseCodec.row(m.row());
            final Map<String, Mutation> byOpAndLabel = new LinkedHashMap<>();
            for (final RowMutation.Update u : m.updates()) {
                final byte[] qualifier = HBaseCodec.qualifier(u.family(), u.qualifier(), u.visibility());
                final String key = u.op().name() + '\u0000' + new String(u.visibility(), java.nio.charset.StandardCharsets.ISO_8859_1);
                final Mutation target = byOpAndLabel.computeIfAbsent(key, k -> newMutation(u, row));
                switch (u.op()) {
                    case PUT -> ((Put) target).addColumn(HBaseCodec.FAMILY, qualifier,
                            u.timestamp() == RowMutation.SERVER_TIME ? HConstants.LATEST_TIMESTAMP : u.timestamp(), u.value());
                    case DELETE -> {
                        if (u.timestamp() == RowMutation.SERVER_TIME) {
                            ((Delete) target).addColumns(HBaseCodec.FAMILY, qualifier);
                        } else {
                            ((Delete) target).addColumns(HBaseCodec.FAMILY, qualifier, u.timestamp());
                        }
                    }
                    case COUNTER -> {
                        if (target instanceof Put p) {
                            // Coprocessor counters: a blind put of the delta; the server gives it a unique timestamp.
                            p.addColumn(HBaseCodec.FAMILY, qualifier, HConstants.LATEST_TIMESTAMP,
                                    org.apache.hadoop.hbase.util.Bytes.toBytes(u.delta()));
                        } else {
                            ((Increment) target).addColumn(HBaseCodec.FAMILY, qualifier, u.delta());
                        }
                    }
                }
            }
            return new ArrayList<>(byOpAndLabel.values());
        }

        private Mutation newMutation(final RowMutation.Update u, final byte[] row) {
            final Mutation m = switch (u.op()) {
                case PUT -> new Put(row);
                case DELETE -> new Delete(row);
                case COUNTER -> counterMode() == HBaseStore.CounterMode.COPROCESSOR ? new Put(row) : new Increment(row);
            };
            final CellVisibility v = HBaseCodec.visibility(u.visibility());
            if (v != null) {
                if (!store.enforcesLabels()) {
                    throw new RyaDAOException("HBase doesn't enforce cell visibility here (VisibilityController with "
                            + "hbase.security.authorization=true), so rya-ng refuses to write the label " + v.getExpression());
                }
                m.setCellVisibility(v);
            }
            m.setDurability(durability(options));
            return m;
        }
    }
}
