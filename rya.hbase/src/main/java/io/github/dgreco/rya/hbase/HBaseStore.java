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
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Query;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.client.security.SecurityCapability;
import org.apache.hadoop.hbase.filter.ColumnPrefixFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.MultipleColumnPrefixFilter;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.UserProvider;
import org.apache.hadoop.hbase.security.visibility.Authorizations;
import org.apache.hadoop.hbase.security.visibility.CellVisibility;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.Capabilities;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.spi.StoreAccess;
import io.github.dgreco.rya.store.spi.TableAdmin;
import io.github.dgreco.rya.store.spi.TableKind;
import io.github.dgreco.rya.store.spi.WriterOptions;
import io.github.dgreco.rya.store.trace.TracedScans;

/**
 * The HBase backend (BACKENDS-STUDY.md, sections 3 and 4).
 * <ul>
 *   <li>Cells map onto HBase as {@link HBaseCodec} describes: one family, the visibility inside the
 *       qualifier and, for enforcement, in HBase's cell visibility tags.</li>
 *   <li>Reads translate rya-ng's labels into HBase's: "no labels" is sent as the reserved label
 *       {@value #NONE_LABEL} (HBase reads an empty set as "every label I hold"), "every label I
 *       hold" as no labels at all.</li>
 *   <li>A single range is a {@link Scan}, an exact row a {@link Get}; several ranges are read in
 *       parallel, one bounded scan per region ({@link HBaseBatchScan}).</li>
 *   <li>Counters are HBase increments of 8-byte longs.</li>
 * </ul>
 * When the store opens it checks that labels are enforced for its account: a superuser, or a
 * cluster without {@code hbase.security.authorization}, sees every cell whatever it asks for, so
 * rya-ng refuses to run there with labels (see {@link HBaseLabels}).
 */
public final class HBaseStore implements Store {
    private static final Logger log = LoggerFactory.getLogger(HBaseStore.class);

    /** The reserved label every reading account holds and no cell carries. */
    public static final String NONE_LABEL = "rya.none";

    /** The region observer that sums counter deltas (module rya.hbase.coprocessor). */
    public static final String COUNTER_COPROCESSOR = "io.github.dgreco.rya.hbase.coprocessor.CounterObserver";

    /** How counter deltas are written. */
    public enum CounterMode {
        /**
         * Plain puts, summed by {@link #COUNTER_COPROCESSOR} on the region servers: blind writes that
         * join the write batches. The coprocessor's jar must be on the region servers' classpath (or
         * given as {@link Options#coprocessorJar()}).
         */
        COPROCESSOR,
        /** HBase increments: no coprocessor, but each one locks its row, reads, and syncs the WAL alone. */
        INCREMENT
    }

    /**
     * Store settings.
     *
     * @param labelsTable the label canary table
     * @param counters how new counter tables store deltas (an existing table keeps its own way)
     * @param coprocessorJar where region servers load the coprocessor from (an hdfs: path); null when
     *        it is on their classpath
     * @param checkLabels whether opening the store checks that labels are enforced for its account;
     *        off only for label administration, which needs a superuser
     */
    public record Options(String labelsTable, CounterMode counters, String coprocessorJar, boolean checkLabels) {
        public static final Options DEFAULT = new Options(HBaseLabels.DEFAULT_TABLE, CounterMode.COPROCESSOR, null, true);

        public Options withCounters(final CounterMode mode) {
            return new Options(labelsTable, mode, coprocessorJar, checkLabels);
        }

        /** For label administration as a superuser: no check that labels are enforced. */
        public Options forAdministration() {
            return new Options(labelsTable, counters, coprocessorJar, false);
        }
    }

    /** Shared by every store: batched reads and concurrent flushes. Threads are daemons. */
    static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "rya-hbase");
        t.setDaemon(true);
        return t;
    });

    private final Connection connection;
    private final boolean ownsConnection;
    private final String user;
    private final boolean labels;
    private final Options options;
    private final Capabilities capabilities;
    /** The counter mode of each counter table, as its descriptor says. */
    private final java.util.Map<String, CounterMode> counterModes = new java.util.concurrent.ConcurrentHashMap<>();
    /** The tables created or checked as counter tables. */
    private final java.util.Set<String> counterTables = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final TableAdmin tables = new Admin_();
    private final StoreAccess access = new Access();

    private HBaseStore(final Connection connection, final boolean ownsConnection, final User user, final Options options) {
        this.connection = connection;
        this.ownsConnection = ownsConnection;
        this.options = options;
        try {
            this.user = (user != null ? user : UserProvider.instantiate(connection.getConfiguration()).getCurrent()).getShortName();
            try (Admin admin = connection.getAdmin()) {
                this.labels = admin.getSecurityCapabilities().contains(SecurityCapability.CELL_VISIBILITY);
            }
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
        this.capabilities = new Capabilities(labels, options.counters() == CounterMode.COPROCESSOR, true, false,
                HBaseCodec.MAX_ROW_BYTES);
        if (labels && options.checkLabels()) {
            HBaseLabels.probe(this, options.labelsTable());
        } else {
            log.warn("HBase doesn't enforce cell visibility (VisibilityController with hbase.security.authorization=true): "
                    + "rya-ng will refuse to write labelled statements");
        }
    }

    /**
     * A store on a connection the caller owns (and closes), checked with the canaries of
     * {@link HBaseLabels#DEFAULT_TABLE}.
     *
     * @param user the connection's user; null for the current user
     */
    public static HBaseStore wrap(final Connection connection, final User user) {
        return new HBaseStore(connection, false, user, Options.DEFAULT);
    }

    /** A store on a connection the caller owns, with these settings. */
    public static HBaseStore wrap(final Connection connection, final User user, final Options options) {
        return new HBaseStore(connection, false, user, options);
    }

    /** A store that closes its connection when it is closed. */
    public static HBaseStore owning(final Connection connection, final User user, final Options options) {
        return new HBaseStore(connection, true, user, options);
    }

    /**
     * How a counter table stores deltas: what its descriptor says (a coprocessor table must get
     * puts, an increment table increments), else the configured mode when the account may not read
     * descriptors.
     */
    CounterMode counterMode(final String table) {
        return counterModes.computeIfAbsent(table, t -> {
            try (Admin admin = connection.getAdmin()) {
                return admin.getDescriptor(name(t)).hasCoprocessor(COUNTER_COPROCESSOR) ? CounterMode.COPROCESSOR
                        : CounterMode.INCREMENT;
            } catch (final IOException e) {
                log.debug("Can't read the descriptor of {} ({}); assuming {} counters", t, e.toString(), options.counters());
                return options.counters();
            }
        });
    }

    /** The connection, for HBase-only code. */
    public Connection connection() {
        return connection;
    }

    /** Whether the cluster enforces cell visibility for this store. */
    public boolean enforcesLabels() {
        return labels;
    }

    @Override
    public String backend() {
        return HBaseStoreProvider.ID;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
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
        return new HBaseCellWriter(this, options);
    }

    @Override
    public CellScanner scan(final String table, final KeyRange range, final ScanSpec spec) {
        checkSpec(spec);
        if (HBaseCodec.beyondRowLimit(range)) {
            return EMPTY;
        }
        final boolean get = range.kind() == KeyRange.Kind.ROW || range.kind() == KeyRange.Kind.CELL;
        return TracedScans.open(get ? "Get" : "Scan", table, List.of(range), spec, describe(spec),
                () -> get ? get(table, range, spec) : scanner(table, range, spec));
    }

    @Override
    public CellScanner batchScan(final String table, final Collection<KeyRange> given, final ScanSpec spec) {
        checkSpec(spec);
        final List<KeyRange> ranges = storable(given);
        if (ranges.isEmpty()) {
            return EMPTY;
        }
        return TracedScans.open("BatchScan", table, ranges, spec, describe(spec),
                () -> new HBaseBatchScan(this, table, ranges, spec));
    }

    @Override
    public void deleteVisible(final String table, final Collection<KeyRange> given, final ScanSpec spec,
            final WriterOptions options) {
        checkSpec(spec);
        final List<KeyRange> ranges = storable(given);
        if (ranges.isEmpty()) {
            return;
        }
        // An increment is stamped max(now, previous + 1), so a busy counter's timestamp runs ahead of
        // the clock, and a delete there would hide the next increments until the clock catches up.
        // Increment counters are set back to zero instead.
        final boolean zero = counterTables.contains(table) && counterMode(table) == CounterMode.INCREMENT;
        final Runnable done = TracedScans.started(zero ? "BatchZeroer" : "BatchDeleter", table, ranges, spec, describe(spec));
        try (CellScanner cells = new HBaseBatchScan(this, table, ranges, spec);
                BufferedMutator mutator = connection.getBufferedMutator(HBaseCellWriter.params(name(table), options))) {
            final List<org.apache.hadoop.hbase.client.Mutation> batch = new ArrayList<>();
            while (cells.hasNext()) {
                final Cell c = cells.next();
                final byte[] qualifier = HBaseCodec.qualifier(c.family(), c.qualifier(), c.visibility());
                final org.apache.hadoop.hbase.client.Mutation d;
                if (zero) {
                    final long value = counterValue(c.value());
                    if (value == 0) {
                        continue;
                    }
                    d = new org.apache.hadoop.hbase.client.Increment(HBaseCodec.row(c.row()))
                            .addColumn(HBaseCodec.FAMILY, qualifier, -value);
                } else {
                    d = new Delete(HBaseCodec.row(c.row())).addColumns(HBaseCodec.FAMILY, qualifier, c.timestamp());
                }
                final CellVisibility v = HBaseCodec.visibility(c.visibility());
                if (v != null) {
                    d.setCellVisibility(v);
                }
                batch.add(d);
                if (batch.size() >= 1000) {
                    mutator.mutate(batch);
                    batch.clear();
                }
            }
            mutator.mutate(batch);
            mutator.flush();
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        } finally {
            done.run();
        }
    }

    @Override
    public long counterValue(final byte[] value) {
        if (value.length != Bytes.SIZEOF_LONG) {
            throw new RyaDAOException("Not an HBase counter value: " + value.length + " bytes");
        }
        return Bytes.toLong(value);
    }

    @Override
    public void close() {
        if (ownsConnection) {
            try {
                connection.close();
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }
    }

    // ------------------------------------------------------------------ reads

    TableName name(final String table) {
        return TableName.valueOf(table);
    }

    Table table(final String table) {
        try {
            return connection.getTable(name(table));
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
    }

    /** The ranges that may hold stored rows. */
    private static List<KeyRange> storable(final Collection<KeyRange> ranges) {
        final List<KeyRange> out = new ArrayList<>(ranges.size());
        for (final KeyRange r : ranges) {
            if (!HBaseCodec.beyondRowLimit(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private static void checkSpec(final ScanSpec spec) {
        if (!spec.iterators().isEmpty()) {
            throw new UnsupportedOperationException("HBase has no Accumulo scan iterators: " + spec.iterators());
        }
    }

    private CellScanner get(final String table, final KeyRange range, final ScanSpec spec) {
        final Get get = get(range, spec);
        try (Table t = table(table)) {
            return cells(t.get(get));
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
    }

    /** The {@link Get} of an exact row (or cell) range. */
    Get get(final KeyRange range, final ScanSpec spec) {
        final Get get = new Get(HBaseCodec.row(range.start())).addFamily(HBaseCodec.FAMILY);
        configure(get, spec, range.kind() == KeyRange.Kind.CELL
                ? new ColumnPrefixFilter(HBaseCodec.columnPrefix(range.family(), range.qualifier())) : null);
        try {
            get.setTimeRange(minTs(spec), maxTs(spec));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return get;
    }

    private CellScanner scanner(final String table, final KeyRange range, final ScanSpec spec) {
        final Scan scan = new Scan().addFamily(HBaseCodec.FAMILY);
        bounds(scan, range.start(), range.startInclusive(), range.end(), range.endInclusive());
        configure(scan, spec, null);
        try {
            scan.setTimeRange(minTs(spec), maxTs(spec));
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
        if (spec.batchSize() != null) {
            scan.setCaching(spec.batchSize());
            if (spec.batchSize() <= 1) {
                scan.setReadType(Scan.ReadType.PREAD);
            }
        }
        final Table t = table(table);
        try {
            return new Scanned(t, t.getScanner(scan));
        } catch (final IOException | RuntimeException e) {
            try {
                t.close();
            } catch (final IOException closing) {
                e.addSuppressed(closing);
            }
            throw e instanceof IOException io ? new RyaDAOException(io) : (RuntimeException) e;
        }
    }

    /** Start and stop rows of a scan; null is unbounded. */
    static void bounds(final Scan scan, final byte[] start, final boolean startInclusive, final byte[] end,
            final boolean endInclusive) {
        if (start != null) {
            scan.withStartRow(HBaseCodec.bound(start), startInclusive);
        }
        if (end != null) {
            scan.withStopRow(HBaseCodec.bound(end), endInclusive);
        }
    }

    static long minTs(final ScanSpec spec) {
        return spec.minTimestamp() == null ? 0 : Math.max(0, spec.minTimestamp());
    }

    static long maxTs(final ScanSpec spec) {
        final Long max = spec.maxTimestamp();
        return max == null || max >= Long.MAX_VALUE - 1 ? Long.MAX_VALUE : max + 1;
    }

    /** Labels and server-side filters of a read, plus an extra filter (a cell range's column). */
    void configure(final Query query, final ScanSpec spec, final Filter extra) {
        final Authorizations auths = authorizations(spec.auths());
        if (auths != null) {
            query.setAuthorizations(auths);
        }
        final List<Filter> filters = filters(spec);
        if (extra != null) {
            filters.add(0, extra);
        }
        if (filters.size() == 1) {
            query.setFilter(filters.get(0));
        } else if (!filters.isEmpty()) {
            query.setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL, filters));
        }
    }

    /** rya-ng's labels as HBase's: none becomes the reserved label, "all held" no labels. */
    Authorizations authorizations(final Auths auths) {
        if (!labels || auths.isAllHeld()) {
            return null;
        }
        return auths.isNone() ? new Authorizations(NONE_LABEL) : new Authorizations(auths.labels());
    }

    /** The column restrictions and regex filters of a spec. */
    static List<Filter> filters(final ScanSpec spec) {
        final List<Filter> filters = new ArrayList<>();
        final List<byte[]> prefixes = new ArrayList<>();
        for (final ScanSpec.Column c : spec.columns()) {
            prefixes.add(HBaseCodec.columnPrefix(c.family(), c.qualifier()));
        }
        for (final byte[] f : spec.families()) {
            prefixes.add(HBaseCodec.familyPrefix(f));
        }
        if (prefixes.size() == 1) {
            filters.add(new ColumnPrefixFilter(prefixes.get(0)));
        } else if (!prefixes.isEmpty()) {
            filters.add(new MultipleColumnPrefixFilter(prefixes.toArray(new byte[0][])));
        }
        if (spec.qualifierRegex() != null) {
            // The qualifier is family 00 qualifier 00 visibility; match the middle part as a whole.
            filters.add(new QualifierFilter(CompareOperator.EQUAL,
                    new RegexStringComparator("\\A[^\\x00]*\\x00(?:" + spec.qualifierRegex() + ")\\x00[^\\x00]*\\z", 0)));
        }
        if (spec.rowRegex() != null) {
            filters.add(new RowFilter(CompareOperator.EQUAL, new RegexStringComparator("\\A(?:" + spec.rowRegex() + ")\\z", 0)));
        }
        return filters;
    }

    /** The server-side filters of a spec, readable, for traces. */
    private static List<String> describe(final ScanSpec spec) {
        final List<String> out = new ArrayList<>();
        if (spec.qualifierRegex() != null) {
            out.add("QualifierFilter regex=" + spec.qualifierRegex());
        }
        if (spec.minTimestamp() != null || spec.maxTimestamp() != null) {
            out.add("TimeRange [" + (spec.minTimestamp() == null ? "-∞" : spec.minTimestamp()) + ", "
                    + (spec.maxTimestamp() == null ? "+∞" : spec.maxTimestamp()) + "]");
        }
        if (spec.rowRegex() != null) {
            out.add("RowFilter regex=" + spec.rowRegex());
        }
        return out;
    }

    static CellScanner cells(final Result result) {
        final org.apache.hadoop.hbase.Cell[] raw = result.rawCells();
        return new CellScanner() {
            private int i;

            @Override
            public boolean hasNext() {
                return raw != null && i < raw.length;
            }

            @Override
            public Cell next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return HBaseCodec.decode(raw[i++]);
            }

            @Override
            public void close() {
            }
        };
    }

    static final CellScanner EMPTY = cells(Result.EMPTY_RESULT);

    /** The cells of a {@link ResultScanner}, read lazily. */
    private static final class Scanned implements CellScanner {
        private final Table table;
        private final ResultScanner scanner;
        private org.apache.hadoop.hbase.Cell[] row;
        private int i;
        private boolean done;

        Scanned(final Table table, final ResultScanner scanner) {
            this.table = table;
            this.scanner = scanner;
        }

        @Override
        public boolean hasNext() {
            while (!done && (row == null || i >= row.length)) {
                final Result r;
                try {
                    r = scanner.next();
                } catch (final IOException e) {
                    throw new RyaDAOException(e);
                }
                if (r == null) {
                    done = true;
                    return false;
                }
                row = r.rawCells();
                i = 0;
            }
            return !done;
        }

        @Override
        public Cell next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return HBaseCodec.decode(row[i++]);
        }

        @Override
        public void close() {
            scanner.close();
            try {
                table.close();
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }
    }

    /** Region boundaries of a table, for batched reads. */
    List<HRegionLocation> regions(final String table) {
        try (var locator = connection.getRegionLocator(name(table))) {
            return locator.getAllRegionLocations();
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
    }

    // ------------------------------------------------------------------ tables

    private final class Admin_ implements TableAdmin {
        @Override
        public boolean exists(final String table) {
            try (Admin admin = connection.getAdmin()) {
                return admin.tableExists(name(table));
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public boolean create(final String table, final TableKind kind) {
            if (kind == TableKind.COUNTERS) {
                counterTables.add(table);
            }
            try (Admin admin = connection.getAdmin()) {
                if (admin.tableExists(name(table))) {
                    return false;
                }
                log.debug("Creating HBase table {} ({})", table, kind);
                final boolean summed = kind == TableKind.COUNTERS && options.counters() == CounterMode.COPROCESSOR;
                final TableDescriptorBuilder descriptor = TableDescriptorBuilder.newBuilder(name(table))
                        .setColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(HBaseCodec.FAMILY)
                                // Coprocessor counters keep every delta until a flush or major compaction adds them up.
                                .setMaxVersions(summed ? Integer.MAX_VALUE : 1)
                                .setBloomFilterType(BloomType.ROW)
                                .setDataBlockEncoding(DataBlockEncoding.FAST_DIFF)
                                .build());
                if (summed) {
                    final var cp = org.apache.hadoop.hbase.client.CoprocessorDescriptorBuilder.newBuilder(COUNTER_COPROCESSOR);
                    if (options.coprocessorJar() != null) {
                        cp.setJarPath(options.coprocessorJar());
                    }
                    descriptor.setCoprocessor(cp.build());
                }
                try {
                    admin.createTable(descriptor.build());
                } catch (final org.apache.hadoop.hbase.DoNotRetryIOException e) {
                    if (summed && String.valueOf(e.getMessage()).contains(COUNTER_COPROCESSOR)) {
                        throw new RyaDAOException("HBase can't load rya-ng's counter coprocessor " + COUNTER_COPROCESSOR
                                + ": put the rya.hbase.coprocessor jar on the region servers' classpath, give its hdfs path "
                                + "(hbase.counters.jar), or use increment counters (hbase.counters=increment)", e);
                    }
                    throw e;
                }
                if (kind == TableKind.COUNTERS) {
                    counterModes.put(table, summed ? CounterMode.COPROCESSOR : CounterMode.INCREMENT);
                    counterTables.add(table);
                }
                return true;
            } catch (final TableExistsException e) {
                return false;
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public void ensureSettings(final String table, final TableKind kind) {
            if (kind == TableKind.COUNTERS) {
                counterTables.add(table);
                final CounterMode mode = counterMode(table);
                if (mode != options.counters()) {
                    log.info("Counter table {} uses {} counters (configured: {}); writing it that way", table, mode,
                            options.counters());
                }
            }
        }

        @Override
        public void delete(final String table) {
            try (Admin admin = connection.getAdmin()) {
                final TableName name = name(table);
                if (!admin.tableExists(name)) {
                    return;
                }
                if (admin.isTableEnabled(name)) {
                    admin.disableTable(name);
                }
                admin.deleteTable(name);
            } catch (final TableNotFoundException e) {
                log.debug("Table {} already gone", table);
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public void deleteRows(final String table) {
            try (Admin admin = connection.getAdmin()) {
                final TableName name = name(table);
                if (admin.isTableEnabled(name)) {
                    admin.disableTable(name);
                }
                admin.truncateTable(name, true);
                counterModes.remove(table);
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public List<String> list() {
            try (Admin admin = connection.getAdmin()) {
                final List<String> out = new ArrayList<>();
                for (final TableName n : admin.listTableNames()) {
                    out.add(n.getNameAsString());
                }
                return out;
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public void addSplits(final String table, final SortedSet<byte[]> splits) {
            try (Admin admin = connection.getAdmin()) {
                final TableName name = name(table);
                for (final byte[] point : splits) {
                    final int before = admin.getRegions(name).size();
                    admin.split(name, HBaseCodec.row(point));
                    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                    while (admin.getRegions(name).size() <= before && System.nanoTime() < deadline) {
                        Thread.sleep(100);
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RyaDAOException(e);
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public int splitCount(final String table) {
            try (Admin admin = connection.getAdmin()) {
                return admin.getRegions(name(table)).size() - 1;
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public int serverCount() {
            try (Admin admin = connection.getAdmin()) {
                return admin.getRegionServers().size();
            } catch (final IOException e) {
                throw new RyaDAOException(e);
            }
        }
    }

    // ------------------------------------------------------------------ security

    private final class Access implements StoreAccess {
        @Override
        public String whoami() {
            return user;
        }

        @Override
        public Optional<List<String>> heldLabels() {
            // HBase doesn't tell a normal account its labels (getAuths returns nothing).
            return Optional.empty();
        }

        @Override
        public boolean mayCreateTables() {
            return true;
        }

        @Override
        public void registerLabels(final Collection<String> names) {
            HBaseLabels.register(connection, names);
        }

        @Override
        public void grantLabels(final String account, final Collection<String> names) {
            HBaseLabels.register(connection, names);
            HBaseLabels.grant(connection, account, names);
        }
    }
}
