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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import org.tripatha.api.persist.RyaDAOException;
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
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.store.trace.TracedScans;

/**
 * The storage SPI on an embedded RocksDB database: a store in a local directory, in this process,
 * without a cluster.
 *
 * <ul>
 *   <li><b>Tables</b> are column families, and a cell is one key ({@link RocksCodec}). There is one
 *       version of a cell: the last one written.</li>
 *   <li><b>Labels</b> are enforced here, below the SPI: every read goes through {@link RocksScan},
 *       which drops the cells its labels don't satisfy. There are no accounts, so every label
 *       counts as held, and {@code Auths.ALL_HELD} reads everything. Whoever can read the
 *       directory can read every cell.</li>
 *   <li><b>Counters</b> are added up by RocksDB's 64-bit add operator, on reads and compactions,
 *       so a delta is a blind write.</li>
 *   <li><b>Writes</b> of a {@link CellWriter} are stored together at each flush: all or none.</li>
 *   <li><b>One process</b> at a time can open a directory. Stores of the same directory in one
 *       JVM share the open database.</li>
 * </ul>
 */
public final class RocksStore implements Store {
    /** The backend's id. */
    public static final String ID = "rocksdb";

    /** A lookup costs about 8 cells read in sequence: a seek, with no round trip (SCALE-STUDY.md O22). */
    private static final Capabilities CAPABILITIES = new Capabilities(true, true, true, false, Integer.MAX_VALUE, 8);
    private static final int DELETE_BATCH = 1000;

    private final RocksDatabase database;
    private final TableAdmin tables = new Tables();
    private final StoreAccess access = new Access();
    private boolean closed;

    private RocksStore(final RocksDatabase database) {
        this.database = database;
    }

    /** Opens the store of a directory, creating it if needed. */
    public static RocksStore open(final Path directory) {
        return new RocksStore(RocksDatabase.acquire(directory));
    }

    /** The directory the store is in. */
    public Path directory() {
        return database.directory();
    }

    /** Writes a table's memory to disk and fully compacts it. */
    public void compact(final String table) {
        database.compact(table);
    }

    @Override
    public String backend() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public TableAdmin tables() {
        return tables;
    }

    @Override
    public StoreAccess access() {
        return access;
    }

    @Override
    public CellWriter newWriter(final WriterOptions options) {
        return new RocksCellWriter(database, options);
    }

    @Override
    public CellScanner scan(final String table, final KeyRange range, final ScanSpec spec) {
        checkSpec(spec);
        final List<KeyRange> ranges = List.of(range);
        return TracedScans.open("Scan", table, ranges, spec, describe(spec), () -> new RocksScan(database, table, ranges, spec));
    }

    @Override
    public CellScanner batchScan(final String table, final Collection<KeyRange> given, final ScanSpec spec) {
        checkSpec(spec);
        final List<KeyRange> ranges = List.copyOf(given);
        return TracedScans.open("BatchScan", table, ranges, spec, describe(spec),
                () -> new RocksScan(database, table, ranges, spec));
    }

    @Override
    public boolean canIntersect(final String table) {
        return database.exists(table);
    }

    /** Native seeks, one row after the other ({@link RocksIntersection}). */
    @Override
    public CellScanner intersect(final String table, final List<byte[]> rows, final List<byte[]> families,
            final ScanSpec spec) {
        checkSpec(spec);
        final List<KeyRange> ranges = new java.util.ArrayList<>(rows.size());
        for (final byte[] row : rows) {
            ranges.add(KeyRange.row(row));
        }
        return TracedScans.open("Intersect", table, ranges, spec, describe(spec),
                () -> new RocksIntersection(database, table, rows, families, spec));
    }

    @Override
    public void deleteVisible(final String table, final Collection<KeyRange> given, final ScanSpec spec,
            final WriterOptions options) {
        checkSpec(spec);
        final List<KeyRange> ranges = List.copyOf(given);
        if (ranges.isEmpty()) {
            return;
        }
        final Runnable done = TracedScans.started("BatchDeleter", table, ranges, spec, describe(spec));
        try (CellScanner cells = new RocksScan(database, table, ranges, spec);
                WriteBatch batch = new WriteBatch();
                WriteOptions write = new WriteOptions()) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                batch.delete(database.family(table), RocksCodec.key(c.row(), c.family(), c.qualifier(), c.visibility()));
                if (batch.count() >= DELETE_BATCH) {
                    database.db().write(write, batch);
                    batch.clear();
                }
            }
            database.db().write(write, batch);
        } catch (final RocksDBException e) {
            throw new RyaDAOException(e);
        } finally {
            done.run();
        }
    }

    @Override
    public long counterValue(final byte[] value) {
        return RocksCodec.counter(value);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            database.release();
        }
    }

    private static void checkSpec(final ScanSpec spec) {
        if (!spec.iterators().isEmpty()) {
            throw new UnsupportedOperationException("RocksDB has no Accumulo scan iterators: " + spec.iterators());
        }
    }

    /** The filters of a spec, readable, for traces. */
    private static List<String> describe(final ScanSpec spec) {
        final List<String> out = new ArrayList<>();
        if (spec.qualifierRegex() != null) {
            out.add("qualifier regex=" + spec.qualifierRegex());
        }
        if (spec.minTimestamp() != null || spec.maxTimestamp() != null) {
            out.add("timestamps [" + (spec.minTimestamp() == null ? "-∞" : spec.minTimestamp()) + ", "
                    + (spec.maxTimestamp() == null ? "+∞" : spec.maxTimestamp()) + "]");
        }
        if (spec.rowRegex() != null) {
            out.add("row regex=" + spec.rowRegex());
        }
        if (spec.rowSuffix() != null) {
            out.add("row suffix=" + java.util.HexFormat.of().formatHex(spec.rowSuffix()));
        }
        return out;
    }

    /** Tables are column families. A local database has no servers to split a table between. */
    private final class Tables implements TableAdmin {
        @Override
        public boolean exists(final String table) {
            return database.exists(table);
        }

        @Override
        public boolean create(final String table, final TableKind kind) {
            return database.create(table, kind);
        }

        @Override
        public void ensureSettings(final String table, final TableKind kind) {
            // Every table has the add operator, and a table's kind is stored when it is created.
        }

        @Override
        public void delete(final String table) {
            database.delete(table);
        }

        @Override
        public void deleteRows(final String table) {
            database.deleteRows(table);
        }

        @Override
        public List<String> list() {
            return database.tables();
        }

        @Override
        public void addSplits(final String table, final SortedSet<byte[]> splits) {
            // Nothing to split: remembered as hints for splitRows.
            database.addSplitHints(table, splits);
        }

        @Override
        public List<byte[]> splitRows(final String table, final int max) {
            return TableAdmin.spread(database.splitRows(table), max);
        }

        @Override
        public int splitCount(final String table) {
            return 0;
        }

        @Override
        public long diskUsage(final String table) {
            return database.exists(table) ? database.diskUsage(table) : -1;
        }

        @Override
        public int serverCount() {
            return 1;
        }
    }

    /** There are no accounts: the process that opens the directory holds every label. */
    private static final class Access implements StoreAccess {
        @Override
        public String whoami() {
            return System.getProperty("user.name", "tripatha");
        }

        @Override
        public Optional<List<String>> heldLabels() {
            return Optional.empty();
        }

        @Override
        public boolean mayCreateTables() {
            return true;
        }

        @Override
        public void registerLabels(final Collection<String> labels) {
            // Any label can be written.
        }

        @Override
        public void grantLabels(final String user, final Collection<String> labels) {
            // Every label is held.
        }
    }
}
