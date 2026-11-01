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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.Combiner;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.iterators.LongCombiner;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.iterators.user.SummingCombiner;
import org.apache.accumulo.core.iterators.user.TimestampFilter;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dgreco.rya.accumulo.trace.AccumuloTrace;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.Capabilities;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.Durability;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.ServerIterator;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.spi.StoreAccess;
import io.github.dgreco.rya.store.spi.TableAdmin;
import io.github.dgreco.rya.store.spi.TableKind;
import io.github.dgreco.rya.store.spi.WriterOptions;

/**
 * The Accumulo backend. Every SPI call is one Accumulo client call: a {@link Scanner}, a
 * {@link BatchScanner}, a {@link BatchDeleter}, a {@code MultiTableBatchWriter}. Cells map one to
 * one onto Accumulo keys (row, family, qualifier, visibility, timestamp), and counters are summed
 * by a {@link SummingCombiner} attached to the counter table.
 */
public final class AccumuloStore implements Store {
    private static final Logger log = LoggerFactory.getLogger(AccumuloStore.class);

    /** Name of the combiner attached to the counter table. */
    public static final String COMBINER_NAME = "ryaStatsSum";
    /** Combiners must run before the default VersioningIterator (priority 20). */
    public static final int COMBINER_PRIORITY = 10;

    /** Accumulo's default key size constraint. */
    private static final int MAX_KEY_BYTES = 1_048_576;
    private static final Capabilities CAPABILITIES = new Capabilities(true, true, true, true, MAX_KEY_BYTES);

    private final AccumuloClient client;
    private final boolean ownsClient;
    private final TableAdmin tables = new Admin();
    private final StoreAccess access = new Access();

    private AccumuloStore(final AccumuloClient client, final boolean ownsClient) {
        this.client = AccumuloTrace.wrap(client);
        this.ownsClient = ownsClient;
    }

    /**
     * A store on a client the caller owns (and closes). Its operations report to the thread's
     * trace, if any.
     */
    public static AccumuloStore wrap(final AccumuloClient client) {
        return new AccumuloStore(client, false);
    }

    /** A store that closes its client when it is closed. */
    public static AccumuloStore owning(final AccumuloClient client) {
        return new AccumuloStore(client, true);
    }

    /** The (traced) client, for Accumulo-only code. */
    public AccumuloClient client() {
        return client;
    }

    @Override
    public String backend() {
        return AccumuloStoreProvider.ID;
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
        return new AccumuloCellWriter(client.createMultiTableBatchWriter(batchWriterConfig(options)));
    }

    @Override
    public CellScanner scan(final String table, final KeyRange range, final ScanSpec spec) {
        if (!selects(range, spec)) {
            return Cells.EMPTY;
        }
        try {
            final Scanner scanner = client.createScanner(table, authorizations(spec.auths()));
            try {
                scanner.setRange(toRange(range));
                if (spec.batchSize() != null) {
                    scanner.setBatchSize(spec.batchSize());
                }
                configure(scanner, spec);
                return new Cells(scanner, scanner.iterator());
            } catch (final RuntimeException e) {
                scanner.close();
                throw e;
            }
        } catch (final TableNotFoundException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public CellScanner batchScan(final String table, final Collection<KeyRange> given, final ScanSpec spec) {
        final List<KeyRange> ranges = new ArrayList<>(given.size());
        for (final KeyRange r : given) {
            if (selects(r, spec)) {
                ranges.add(r);
            }
        }
        if (ranges.isEmpty()) {
            return Cells.EMPTY;
        }
        try {
            final BatchScanner scanner = client.createBatchScanner(table, authorizations(spec.auths()), spec.threads());
            try {
                scanner.setRanges(toRanges(ranges));
                configure(scanner, spec);
                return new Cells(scanner, scanner.iterator());
            } catch (final RuntimeException e) {
                scanner.close();
                throw e;
            }
        } catch (final TableNotFoundException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public void deleteVisible(final String table, final Collection<KeyRange> ranges, final ScanSpec spec,
            final WriterOptions options) {
        if (ranges.isEmpty()) {
            return;
        }
        try (BatchDeleter deleter = client.createBatchDeleter(table, authorizations(spec.auths()), spec.threads(),
                batchWriterConfig(options))) {
            deleter.setRanges(toRanges(ranges));
            configure(deleter, spec);
            deleter.delete();
        } catch (final TableNotFoundException | MutationsRejectedException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public long counterValue(final byte[] value) {
        return Long.parseLong(new String(value, StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    // ------------------------------------------------------------------ mapping

    /**
     * Whether a cell range's column is among the spec's columns. Accumulo rejects a cell range
     * outside the fetched columns ("start key must be less than end key") instead of reading
     * nothing, so such a range is dropped before reading.
     */
    private static boolean selects(final KeyRange r, final ScanSpec spec) {
        if (r.kind() != KeyRange.Kind.CELL || (spec.families().isEmpty() && spec.columns().isEmpty())) {
            return true;
        }
        for (final byte[] f : spec.families()) {
            if (java.util.Arrays.equals(f, r.family())) {
                return true;
            }
        }
        for (final ScanSpec.Column c : spec.columns()) {
            if (java.util.Arrays.equals(c.family(), r.family()) && java.util.Arrays.equals(c.qualifier(), r.qualifier())) {
                return true;
            }
        }
        return false;
    }

    /** The Accumulo mutation a {@link RowMutation} is written as (a counter delta is its decimal text). */
    public static Mutation toMutation(final RowMutation m) {
        return AccumuloCellWriter.toMutation(m);
    }

    /** The Accumulo range of a {@link KeyRange}, built as rya-ng always built it. */
    static Range toRange(final KeyRange r) {
        return switch (r.kind()) {
            case ALL -> new Range();
            case ROW -> new Range(new Text(r.start()));
            case CELL -> Range.exact(new Text(r.start()), new Text(r.family()), new Text(r.qualifier()));
            case PREFIX -> Range.prefix(new Text(r.prefix()));
            case ROWS -> new Range(r.start() == null ? null : new Text(r.start()), r.startInclusive(),
                    r.end() == null ? null : new Text(r.end()), r.endInclusive());
        };
    }

    private static List<Range> toRanges(final Collection<KeyRange> ranges) {
        final List<Range> out = new ArrayList<>(ranges.size());
        for (final KeyRange r : ranges) {
            out.add(toRange(r));
        }
        return out;
    }

    private Authorizations authorizations(final Auths auths) {
        if (auths.isAllHeld()) {
            try {
                return client.securityOperations().getUserAuthorizations(client.whoami());
            } catch (final AccumuloException | AccumuloSecurityException e) {
                throw new RyaDAOException(e);
            }
        }
        return auths.labels().isEmpty() ? Authorizations.EMPTY : new Authorizations(auths.labels().toArray(new String[0]));
    }

    /** Columns, then the server-side filters, in the order and with the names rya-ng always used. */
    private static void configure(final ScannerBase scanner, final ScanSpec spec) {
        for (final ScanSpec.Column c : spec.columns()) {
            scanner.fetchColumn(new Text(c.family()), new Text(c.qualifier()));
        }
        for (final byte[] f : spec.families()) {
            scanner.fetchColumnFamily(new Text(f));
        }
        if (spec.qualifierRegex() != null) {
            final IteratorSetting setting = new IteratorSetting(8, "riq", RegExFilter.class.getName());
            RegExFilter.setRegexs(setting, null, null, spec.qualifierRegex(), null, false);
            scanner.addScanIterator(setting);
        }
        if (spec.minTimestamp() != null || spec.maxTimestamp() != null) {
            final IteratorSetting setting = new IteratorSetting(9, "fi", TimestampFilter.class.getName());
            if (spec.minTimestamp() != null) {
                TimestampFilter.setStart(setting, spec.minTimestamp(), true);
            }
            if (spec.maxTimestamp() != null) {
                TimestampFilter.setEnd(setting, spec.maxTimestamp(), true);
            }
            scanner.addScanIterator(setting);
        }
        if (spec.rowRegex() != null) {
            final IteratorSetting setting = new IteratorSetting(11, "ri", RegExFilter.class.getName());
            RegExFilter.setRegexs(setting, spec.rowRegex(), null, null, null, false);
            scanner.addScanIterator(setting);
        }
        for (final ServerIterator it : spec.iterators()) {
            scanner.addScanIterator(new IteratorSetting(it.priority(), it.name(), it.className(), it.options()));
        }
    }

    static BatchWriterConfig batchWriterConfig(final WriterOptions options) {
        final BatchWriterConfig config = new BatchWriterConfig()
                .setMaxMemory(options.maxMemoryBytes())
                .setMaxLatency(options.maxLatencyMs(), TimeUnit.MILLISECONDS)
                .setTimeout(options.timeoutMs(), TimeUnit.MILLISECONDS)
                .setMaxWriteThreads(options.threads());
        if (options.durability() != Durability.DEFAULT) {
            config.setDurability(org.apache.accumulo.core.client.Durability.valueOf(options.durability().name()));
        }
        return config;
    }

    /** The combiner that adds up the counter table's decimal deltas, on every scope. */
    public static IteratorSetting combinerSetting() {
        final IteratorSetting setting = new IteratorSetting(COMBINER_PRIORITY, COMBINER_NAME, SummingCombiner.class);
        LongCombiner.setEncodingType(setting, LongCombiner.Type.STRING);
        Combiner.setCombineAllColumns(setting, true);
        return setting;
    }

    private static boolean permissionDenied(final Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AccumuloSecurityException) {
                return true;
            }
        }
        return false;
    }

    /** The cells of a scanner; closing it closes the scanner. */
    private static final class Cells implements CellScanner {
        static final CellScanner EMPTY = new CellScanner() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Cell next() {
                throw new NoSuchElementException();
            }

            @Override
            public void close() {
            }
        };

        private final ScannerBase scanner;
        private final java.util.Iterator<Entry<Key, Value>> entries;

        Cells(final ScannerBase scanner, final java.util.Iterator<Entry<Key, Value>> entries) {
            this.scanner = scanner;
            this.entries = entries;
        }

        @Override
        public boolean hasNext() {
            return entries.hasNext();
        }

        @Override
        public Cell next() {
            final Entry<Key, Value> e = entries.next();
            final Key k = e.getKey();
            final Value v = e.getValue();
            return new Cell(k.getRowData().toArray(), k.getColumnFamilyData().toArray(), k.getColumnQualifierData().toArray(),
                    k.getColumnVisibilityData().toArray(), k.getTimestamp(), v == null ? new byte[0] : v.get());
        }

        @Override
        public void close() {
            scanner.close();
        }
    }

    // ------------------------------------------------------------------ tables

    private final class Admin implements TableAdmin {
        @Override
        public boolean exists(final String table) {
            return client.tableOperations().exists(table);
        }

        @Override
        public boolean create(final String table, final TableKind kind) {
            final TableOperations ops = client.tableOperations();
            if (ops.exists(table)) {
                return false;
            }
            log.debug("Creating accumulo table: {}", table);
            try {
                ops.create(table);
            } catch (final TableExistsException e) {
                return false;
            } catch (final AccumuloException | AccumuloSecurityException e) {
                throw new RyaDAOException(e);
            }
            if (kind == TableKind.COUNTERS) {
                ensureSettings(table, kind);
            }
            return true;
        }

        @Override
        public void ensureSettings(final String table, final TableKind kind) {
            if (kind != TableKind.COUNTERS) {
                return;
            }
            final TableOperations ops = client.tableOperations();
            final Map<String, EnumSet<IteratorScope>> iterators;
            try {
                iterators = ops.listIterators(table);
            } catch (final AccumuloException | AccumuloSecurityException e) {
                if (!permissionDenied(e)) {
                    throw new RyaDAOException(e);
                }
                // Reading table settings needs ALTER_TABLE; the user who created the table attached the combiner.
                log.debug("No permission to list the iterators of {}; assuming the stats combiner is attached", table);
                return;
            } catch (final TableNotFoundException e) {
                throw new RyaDAOException(e);
            }
            if (!iterators.containsKey(COMBINER_NAME)) {
                try {
                    ops.attachIterator(table, combinerSetting(), EnumSet.allOf(IteratorScope.class));
                } catch (final IllegalArgumentException e) {
                    // Another client attached it between the check and the attach.
                    log.debug("Stats combiner already attached to {}", table);
                } catch (final AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
                    throw new RyaDAOException(e);
                }
            }
        }

        @Override
        public void delete(final String table) {
            try {
                if (client.tableOperations().exists(table)) {
                    client.tableOperations().delete(table);
                }
            } catch (final TableNotFoundException e) {
                log.debug("Table {} already gone", table);
            } catch (final AccumuloException | AccumuloSecurityException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public void deleteRows(final String table) {
            try {
                client.tableOperations().deleteRows(table, null, null);
            } catch (final AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public List<String> list() {
            return new ArrayList<>(client.tableOperations().list());
        }

        @Override
        public void addSplits(final String table, final SortedSet<byte[]> splits) {
            final SortedSet<Text> texts = new TreeSet<>();
            for (final byte[] s : splits) {
                texts.add(new Text(s));
            }
            try {
                client.tableOperations().addSplits(table, texts);
            } catch (final AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public int splitCount(final String table) {
            try {
                return client.tableOperations().listSplits(table).size();
            } catch (final AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public int serverCount() {
            return client.instanceOperations().getTabletServers().size();
        }
    }

    // ------------------------------------------------------------------ security

    private final class Access implements StoreAccess {
        @Override
        public String whoami() {
            return client.whoami();
        }

        @Override
        public Optional<List<String>> heldLabels() {
            try {
                final List<String> out = new ArrayList<>();
                for (final byte[] a : client.securityOperations().getUserAuthorizations(client.whoami()).getAuthorizations()) {
                    out.add(new String(a, StandardCharsets.UTF_8));
                }
                return Optional.of(out);
            } catch (final AccumuloException | AccumuloSecurityException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public boolean mayCreateTables() {
            try {
                return client.securityOperations().hasSystemPermission(client.whoami(), SystemPermission.CREATE_TABLE);
            } catch (final AccumuloException | AccumuloSecurityException e) {
                throw new RyaDAOException(e);
            }
        }

        @Override
        public void registerLabels(final Collection<String> labels) {
            // Accumulo accepts any label.
        }

        @Override
        public void grantLabels(final String user, final Collection<String> labels) {
            synchronized (AccumuloStore.class) {
                try {
                    final Authorizations current = client.securityOperations().getUserAuthorizations(user);
                    final List<byte[]> merged = new ArrayList<>(current.getAuthorizations());
                    final List<String> have = new ArrayList<>();
                    for (final byte[] a : merged) {
                        have.add(new String(a, StandardCharsets.UTF_8));
                    }
                    for (final String a : labels) {
                        if (!have.contains(a)) {
                            merged.add(a.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    client.securityOperations().changeUserAuthorizations(user, new Authorizations(merged));
                } catch (final AccumuloException | AccumuloSecurityException e) {
                    throw new RyaDAOException(e);
                }
            }
        }
    }
}
