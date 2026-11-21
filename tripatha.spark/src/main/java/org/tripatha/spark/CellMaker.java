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

import java.util.List;
import java.util.Set;

import org.tripatha.accumulo.AccumuloRFiles;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.loader.bulk.CapturingStore;
import org.tripatha.store.EvalStatsMutations;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.RowMutation;

/**
 * The cells statements become, made by the DAO itself, so every cell is the one {@code load}
 * writes: a {@link RyaStoreDAO} over a {@link CapturingStore} of an {@link EmptyStore}, which
 * hands the writes to the bulk tables to a {@link Sink} and drops the rest (the {@code ns}
 * records a DAO writes when it opens a store it thinks new: the driver's DAO wrote the real ones).
 * One per Spark task; not thread-safe.
 */
final class CellMaker implements AutoCloseable {
    /** A cell, as the bulk files hold it. */
    interface Sink {
        void put(String table, byte[] row, byte[] family, byte[] qualifier, byte[] visibility, long timestamp, byte[] value);

        void counter(String table, byte[] row, byte[] family, byte[] qualifier, byte[] visibility, long delta);
    }

    private static final byte[] EMPTY = new byte[0];

    private final StoreRdfConfiguration conf;
    private final Set<String> tables;
    private final Set<String> existing;
    private final Sink sink;
    private final long jobTime;
    private RyaStoreDAO dao;

    /**
     * @param tables the tables whose cells go to the sink
     * @param existing every table the real store has
     * @param jobTime the timestamp of a put that leaves it to the server (graph markers)
     */
    CellMaker(final StoreRdfConfiguration conf, final Set<String> tables, final Set<String> existing, final Sink sink,
            final long jobTime) {
        this.conf = conf;
        this.tables = Set.copyOf(tables);
        this.existing = Set.copyOf(existing);
        this.sink = sink;
        this.jobTime = jobTime;
    }

    /**
     * Writes a batch through the DAO. A batch that fails may have handed part of its cells to the
     * sink: the caller drops them, and the DAO is opened again for the next batch, since a failed
     * call can leave counts pending in it.
     */
    void add(final List<RyaStatement> statements, final EvalStatsMutations.CounterPolicy policy) {
        if (dao == null) {
            dao = open();
        }
        try {
            dao.add(statements.iterator(), policy);
        } catch (final RuntimeException e) {
            close();
            throw e;
        }
    }

    private RyaStoreDAO open() {
        final StoreRdfConfiguration c = new StoreRdfConfiguration(conf);
        c.setFlush(false);
        final RyaStoreDAO d = new RyaStoreDAO(new CapturingStore(new EmptyStore(existing), tables, this::accept), c);
        d.init();
        return d;
    }

    private void accept(final String table, final RowMutation mutation) {
        final byte[] row = mutation.row();
        for (final RowMutation.Update u : mutation.updates()) {
            final byte[] family = orEmpty(u.family());
            final byte[] qualifier = orEmpty(u.qualifier());
            final byte[] visibility = orEmpty(u.visibility());
            final long key = (long) row.length + family.length + qualifier.length + visibility.length;
            if (key > AccumuloRFiles.MAX_KEY_BYTES) {
                throw new RyaDAOException("A key of " + key + " bytes in " + table + ": Accumulo's key size constraint allows "
                        + AccumuloRFiles.MAX_KEY_BYTES);
            }
            switch (u.op()) {
                case PUT -> sink.put(table, row, family, qualifier, visibility,
                        u.timestamp() == RowMutation.SERVER_TIME ? jobTime : u.timestamp(), orEmpty(u.value()));
                case COUNTER -> sink.counter(table, row, family, qualifier, visibility, u.delta());
                case DELETE -> throw new IllegalStateException(table + ": a bulk build only adds, but got a delete");
            }
        }
    }

    private static byte[] orEmpty(final byte[] b) {
        return b == null ? EMPTY : b;
    }

    @Override
    public void close() {
        if (dao != null) {
            final RyaStoreDAO d = dao;
            dao = null;
            d.destroy();
        }
    }
}
