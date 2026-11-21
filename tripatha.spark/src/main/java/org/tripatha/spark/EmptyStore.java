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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;

import org.tripatha.store.spi.Capabilities;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.StoreAccess;
import org.tripatha.store.spi.TableAdmin;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.TableWriter;
import org.tripatha.store.spi.WriterOptions;

/**
 * A store with nothing in it, for the DAO a Spark task writes through: the tables the real store
 * has exist and are empty, writes go nowhere, and nothing connects anywhere (a DAO checks which
 * index tables exist against its settings, so they must be the real ones). The task wraps it in a
 * {@code CapturingStore}, which takes the writes worth keeping; the rest (the {@code ns}
 * records a DAO writes when it opens) are the driver's to write, to the real store.
 */
final class EmptyStore implements Store {
    private final java.util.Set<String> tables;
    private static final CellScanner NOTHING = new CellScanner() {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Cell next() {
            throw new java.util.NoSuchElementException();
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    /** @param tables the tables the real store has */
    EmptyStore(final java.util.Set<String> tables) {
        this.tables = new java.util.HashSet<>(tables);
    }

    @Override
    public String backend() {
        return "spark-task";
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, true, false, Integer.MAX_VALUE, 1, 1);
    }

    @Override
    public TableAdmin tables() {
        return new TableAdmin() {
            @Override
            public boolean exists(final String table) {
                return tables.contains(table);
            }

            @Override
            public boolean create(final String table, final TableKind kind) {
                return tables.add(table);
            }

            @Override
            public void ensureSettings(final String table, final TableKind kind) {
                // the driver's store has them
            }

            @Override
            public void delete(final String table) {
                throw new UnsupportedOperationException("a Spark task deletes no table");
            }

            @Override
            public void deleteRows(final String table) {
                throw new UnsupportedOperationException("a Spark task deletes no rows");
            }

            @Override
            public List<String> list() {
                return List.copyOf(tables);
            }

            @Override
            public void addSplits(final String table, final SortedSet<byte[]> splits) {
                // the driver splits the real tables
            }

            @Override
            public int splitCount(final String table) {
                return 0;
            }

            @Override
            public int serverCount() {
                return 1;
            }
        };
    }

    @Override
    public StoreAccess access() {
        return new StoreAccess() {
            @Override
            public String whoami() {
                return "spark-task";
            }

            @Override
            public Optional<List<String>> heldLabels() {
                return Optional.empty();
            }

            @Override
            public boolean mayCreateTables() {
                return false;
            }

            @Override
            public void registerLabels(final Collection<String> labels) {
                // labels are the driver's account's
            }

            @Override
            public void grantLabels(final String user, final Collection<String> labels) {
                // labels are the driver's account's
            }
        };
    }

    @Override
    public CellWriter newWriter(final WriterOptions options) {
        return new CellWriter() {
            @Override
            public TableWriter table(final String table) {
                return mutation -> { };
            }

            @Override
            public void flush() {
                // nothing was written
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
    }

    @Override
    public CellScanner scan(final String table, final KeyRange range, final ScanSpec spec) {
        return NOTHING;
    }

    @Override
    public CellScanner batchScan(final String table, final Collection<KeyRange> ranges, final ScanSpec spec) {
        return NOTHING;
    }

    @Override
    public void deleteVisible(final String table, final Collection<KeyRange> ranges, final ScanSpec spec, final WriterOptions options) {
        throw new UnsupportedOperationException("a Spark task deletes nothing");
    }

    @Override
    public long counterValue(final byte[] value) {
        return 0;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
