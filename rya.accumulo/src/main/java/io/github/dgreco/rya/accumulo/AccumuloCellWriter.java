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
package io.github.dgreco.rya.accumulo;

import java.util.HashMap;
import java.util.Map;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.security.ColumnVisibility;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.TableWriter;

/**
 * A {@link CellWriter} on a {@link MultiTableBatchWriter}: one {@link Mutation} per row mutation,
 * and a counter delta is a put of its decimal text, which the table's combiner adds up.
 */
final class AccumuloCellWriter implements CellWriter {
    private static final ColumnVisibility EMPTY_CV = new ColumnVisibility(new byte[0]);

    private final MultiTableBatchWriter writer;
    private final Map<String, TableWriter> tables = new HashMap<>();

    AccumuloCellWriter(final MultiTableBatchWriter writer) {
        this.writer = writer;
    }

    @Override
    public TableWriter table(final String table) {
        return tables.computeIfAbsent(table, this::open);
    }

    private TableWriter open(final String table) {
        final BatchWriter bw;
        try {
            bw = writer.getBatchWriter(table);
        } catch (final AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
            throw new RyaDAOException(e);
        }
        return m -> {
            try {
                bw.addMutation(toMutation(m));
            } catch (final MutationsRejectedException e) {
                throw new RyaDAOException(e);
            }
        };
    }

    static Mutation toMutation(final RowMutation rm) {
        final Mutation m = new Mutation(rm.row());
        for (final RowMutation.Update u : rm.updates()) {
            final ColumnVisibility cv = u.visibility().length == 0 ? EMPTY_CV : new ColumnVisibility(u.visibility());
            switch (u.op()) {
                case PUT -> {
                    if (u.timestamp() == RowMutation.SERVER_TIME) {
                        m.put(u.family(), u.qualifier(), cv, u.value());
                    } else {
                        m.put(u.family(), u.qualifier(), cv, u.timestamp(), u.value());
                    }
                }
                case DELETE -> {
                    if (u.timestamp() == RowMutation.SERVER_TIME) {
                        m.putDelete(u.family(), u.qualifier(), cv);
                    } else {
                        m.putDelete(u.family(), u.qualifier(), cv, u.timestamp());
                    }
                }
                case COUNTER -> m.put(u.family(), u.qualifier(), cv,
                        Long.toString(u.delta()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    @Override
    public void flush() {
        try {
            writer.flush();
        } catch (final MutationsRejectedException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (final MutationsRejectedException e) {
            throw new RyaDAOException(e);
        }
    }
}
