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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.UInt64AddOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.TableKind;

/**
 * One open RocksDB database, shared by every store of this JVM that uses its directory. RocksDB
 * lets one process, and one handle in it, open a directory for writing, so stores and their
 * scanners count their uses of the handle, and the last one to finish closes it.
 *
 * <p>A Tripatha table is a column family. Its {@link TableKind} is recorded in the default column
 * family, which holds nothing else.
 */
final class RocksDatabase {
    private static final Logger log = LoggerFactory.getLogger(RocksDatabase.class);
    private static final Map<Path, RocksDatabase> OPEN = new HashMap<>();
    private static final String KIND_PREFIX = "kind\u0000";

    static {
        RocksDB.loadLibrary();
    }

    private final Path directory;
    private final RocksDB db;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions familyOptions;
    private final UInt64AddOperator add;
    private final ColumnFamilyHandle metadata;
    private final Map<String, ColumnFamilyHandle> families = new ConcurrentHashMap<>();
    /** Split points given to tables in this process (a RocksDB table has none of its own). */
    private final Map<String, java.util.SortedSet<byte[]>> splitHints = new ConcurrentHashMap<>();
    private final Map<String, TableKind> kinds = new ConcurrentHashMap<>();
    /** Handles of dropped tables: a scanner may still be reading one, so they are closed with the database. */
    private final List<ColumnFamilyHandle> retired = new ArrayList<>();
    private int uses;

    /** Opens the database of a directory, or shares the one this JVM already has open. Pair with {@link #release()}. */
    static RocksDatabase acquire(final Path directory) {
        final Path path;
        try {
            Files.createDirectories(directory);
            path = directory.toRealPath();
        } catch (final IOException e) {
            throw new UncheckedIOException("Can't use " + directory + " as a RocksDB directory", e);
        }
        synchronized (OPEN) {
            RocksDatabase database = OPEN.get(path);
            if (database == null) {
                database = new RocksDatabase(path);
                OPEN.put(path, database);
            }
            database.uses++;
            return database;
        }
    }

    private RocksDatabase(final Path directory) {
        this.directory = directory;
        this.add = new UInt64AddOperator();
        // Every table has the add operator: it only acts on counter tables, the only ones written
        // with merges, and one set of options lets the database reopen without knowing each kind.
        this.familyOptions = new ColumnFamilyOptions().setMergeOperator(add)
                .setCompressionType(CompressionType.LZ4_COMPRESSION);
        this.dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        try {
            final List<byte[]> names;
            try (Options options = new Options()) {
                names = RocksDB.listColumnFamilies(options, directory.toString());
            }
            final List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
            descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions));
            for (final byte[] name : names) {
                if (!java.util.Arrays.equals(name, RocksDB.DEFAULT_COLUMN_FAMILY)) {
                    descriptors.add(new ColumnFamilyDescriptor(name, familyOptions));
                }
            }
            final List<ColumnFamilyHandle> handles = new ArrayList<>();
            this.db = RocksDB.open(dbOptions, directory.toString(), descriptors, handles);
            this.metadata = handles.get(0);
            for (int i = 1; i < handles.size(); i++) {
                families.put(new String(descriptors.get(i).getName(), StandardCharsets.UTF_8), handles.get(i));
            }
            loadKinds();
        } catch (final RocksDBException e) {
            familyOptions.close();
            dbOptions.close();
            add.close();
            throw new RyaDAOException("Can't open the RocksDB database in " + directory + ": " + e.getMessage()
                    + ". One process at a time can open a directory.", e);
        }
    }

    private void loadKinds() throws RocksDBException {
        try (RocksIterator it = db.newIterator(metadata)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                final String key = new String(it.key(), StandardCharsets.UTF_8);
                if (key.startsWith(KIND_PREFIX)) {
                    kinds.put(key.substring(KIND_PREFIX.length()), TableKind.valueOf(new String(it.value(), StandardCharsets.UTF_8)));
                }
            }
        }
        // A table whose rows were being deleted when the process died: recreate it, empty.
        for (final String table : kinds.keySet()) {
            if (!families.containsKey(table)) {
                log.warn("Recreating the table {} of {}: it was dropped but not removed", table, directory);
                families.put(table, db.createColumnFamily(new ColumnFamilyDescriptor(name(table), familyOptions)));
            }
        }
    }

    private static byte[] name(final String table) {
        return table.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] kindKey(final String table) {
        return (KIND_PREFIX + table).getBytes(StandardCharsets.UTF_8);
    }

    Path directory() {
        return directory;
    }

    RocksDB db() {
        return db;
    }

    /** Another use of the open database (a scanner's, for example). Pair with {@link #release()}. */
    void retain() {
        synchronized (OPEN) {
            uses++;
        }
    }

    /** Ends one use; the last one closes the database. */
    void release() {
        synchronized (OPEN) {
            if (--uses > 0) {
                return;
            }
            OPEN.remove(directory);
            families.values().forEach(ColumnFamilyHandle::close);
            retired.forEach(ColumnFamilyHandle::close);
            metadata.close();
            db.close();
            dbOptions.close();
            familyOptions.close();
            add.close();
        }
    }

    // ------------------------------------------------------------------ tables

    /** The column family of a table. */
    ColumnFamilyHandle family(final String table) {
        final ColumnFamilyHandle family = families.get(table);
        if (family == null) {
            throw new RyaDAOException("No table " + table + " in the RocksDB database " + directory);
        }
        return family;
    }

    boolean exists(final String table) {
        return families.containsKey(table);
    }

    /** Options for writing an SST file that a table of this database ingests: its own family options. Close them. */
    org.rocksdb.Options sstOptions() {
        return new org.rocksdb.Options(dbOptions, familyOptions);
    }

    /** Whether the table holds counters; false for a table that doesn't exist. */
    boolean isCounters(final String table) {
        return kinds.get(table) == TableKind.COUNTERS;
    }

    List<String> tables() {
        return new ArrayList<>(new TreeSet<>(families.keySet()));
    }

    synchronized boolean create(final String table, final TableKind kind) {
        if (families.containsKey(table)) {
            return false;
        }
        try {
            // The kind first: a kind without its family is repaired when the database opens.
            db.put(metadata, kindKey(table), kind.name().getBytes(StandardCharsets.UTF_8));
            families.put(table, db.createColumnFamily(new ColumnFamilyDescriptor(name(table), familyOptions)));
            kinds.put(table, kind);
            return true;
        } catch (final RocksDBException e) {
            throw new RyaDAOException(e);
        }
    }

    synchronized void delete(final String table) {
        final ColumnFamilyHandle family = families.remove(table);
        if (family == null) {
            return;
        }
        try {
            db.dropColumnFamily(family);
            db.delete(metadata, kindKey(table));
            kinds.remove(table);
            retired.add(family);
        } catch (final RocksDBException e) {
            throw new RyaDAOException(e);
        }
    }

    /** Empties a table by dropping and recreating its column family. */
    synchronized void deleteRows(final String table) {
        final ColumnFamilyHandle family = family(table);
        try {
            db.dropColumnFamily(family);
            retired.add(family);
            families.put(table, db.createColumnFamily(new ColumnFamilyDescriptor(name(table), familyOptions)));
        } catch (final RocksDBException e) {
            throw new RyaDAOException(e);
        }
    }

    /** Remembers split points given to a table, for {@link #splitRows}: a RocksDB table has no splits of its own. */
    void addSplitHints(final String table, final java.util.Collection<byte[]> rows) {
        splitHints.computeIfAbsent(table, t -> java.util.Collections.synchronizedSortedSet(new TreeSet<>(java.util.Arrays::compareUnsigned)))
                .addAll(rows);
    }

    /**
     * Rows that divide a table into parts of similar size, sorted: the split points given to it
     * in this process, or else the first rows of its files on the level that holds most of its
     * data (RocksDB keeps each level's files in disjoint key ranges). None for a table still in
     * memory.
     */
    /** The bytes of a table's SST files. */
    long diskUsage(final String table) {
        final byte[] familyName = name(table);
        long bytes = 0;
        for (final org.rocksdb.LiveFileMetaData file : db.getLiveFilesMetaData()) {
            if (java.util.Arrays.equals(file.columnFamilyName(), familyName)) {
                bytes += file.size();
            }
        }
        return bytes;
    }

    List<byte[]> splitRows(final String table) {
        final java.util.SortedSet<byte[]> hints = splitHints.get(table);
        if (hints != null && !hints.isEmpty()) {
            synchronized (hints) {
                return new ArrayList<>(hints);
            }
        }
        final byte[] familyName = name(table);
        final Map<Integer, List<org.rocksdb.LiveFileMetaData>> byLevel = new HashMap<>();
        for (final org.rocksdb.LiveFileMetaData file : db.getLiveFilesMetaData()) {
            if (java.util.Arrays.equals(file.columnFamilyName(), familyName)) {
                byLevel.computeIfAbsent(file.level(), l -> new ArrayList<>()).add(file);
            }
        }
        List<org.rocksdb.LiveFileMetaData> fullest = List.of();
        long most = -1;
        for (final List<org.rocksdb.LiveFileMetaData> files : byLevel.values()) {
            final long size = files.stream().mapToLong(org.rocksdb.LiveFileMetaData::size).sum();
            if (size > most) {
                most = size;
                fullest = files;
            }
        }
        final TreeSet<byte[]> rows = new TreeSet<>(java.util.Arrays::compareUnsigned);
        for (final org.rocksdb.LiveFileMetaData file : fullest) {
            final byte[] row = RocksCodec.row(file.smallestKey());
            if (row.length > 0) {
                rows.add(row);
            }
        }
        // The first file starts the table: its first row splits nothing.
        if (!rows.isEmpty()) {
            rows.pollFirst();
        }
        return new ArrayList<>(rows);
    }

    /** Writes a table's memory to disk and fully compacts it. */
    void compact(final String table) {
        final ColumnFamilyHandle family = family(table);
        try (FlushOptions flush = new FlushOptions().setWaitForFlush(true)) {
            db.flush(flush, family);
            db.compactRange(family);
        } catch (final RocksDBException e) {
            throw new RyaDAOException(e);
        }
    }
}
