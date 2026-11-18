package org.tripatha.store.query;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Bytes;

import org.tripatha.api.RdfCloudTripleStoreConstants;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.RdfCloudTripleStoreUtils;
import org.tripatha.api.domain.RyaAnyTypeRange;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaRange;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.domain.RyaValueRange;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.api.persist.query.RyaQueryEngine;
import org.tripatha.api.query.strategy.ByteRange;
import org.tripatha.api.query.strategy.TriplePatternStrategy;
import org.tripatha.api.resolver.RyaContext;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.api.resolver.triple.TripleRowRegex;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.ServerIterator;
import org.tripatha.store.spi.Store;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceText;

/**
 * Answers statement patterns by range-scanning whichever of the SPO, PO or OSP tables
 * has the bound parts of the pattern as a row prefix.
 */
public class RyaStoreQueryEngine implements RyaQueryEngine<StoreRdfConfiguration> {

    private final Store store;
    private StoreRdfConfiguration configuration;
    private final RyaTripleContext ryaContext;
    /** Whether each entity table's servers can load Tripatha's iterator, asked once. */
    private final java.util.Map<String, Boolean> serverIntersection = new java.util.concurrent.ConcurrentHashMap<>();

    public RyaStoreQueryEngine(final Store store, final StoreRdfConfiguration conf) {
        this.store = Preconditions.checkNotNull(store);
        this.configuration = Preconditions.checkNotNull(conf);
        this.ryaContext = RyaTripleContext.getInstance(conf);
    }

    @Override
    public CloseableIteration<RyaStatement> query(final RyaStatement stmt, StoreRdfConfiguration conf) {
        Preconditions.checkNotNull(stmt);
        if (conf == null) {
            conf = configuration;
        }
        if (stmt.getObject() instanceof RyaValueRange range) {
            return queryValueRange(stmt, range, conf);
        }
        try {
            TriplePatternStrategy strategy = ryaContext.retrieveStrategy(stmt);
            final TABLE_LAYOUT layout;
            final KeyRange range;
            if (strategy != null) {
                final Map.Entry<TABLE_LAYOUT, ByteRange> entry = strategy.defineRange(stmt.getSubject(),
                        stmt.getPredicate(), stmt.getObject(), stmt.getContext(), conf);
                layout = entry.getKey();
                range = toRange(entry.getValue());
            } else {
                // Nothing bound: full scan of SPO.
                layout = TABLE_LAYOUT.SPO;
                range = KeyRange.all();
                strategy = ryaContext.retrieveStrategy(layout);
            }
            // The object's type check alone is "the row ends with 01 and the type", a byte suffix; any
            // other row condition (or the hashed layout) keeps the regular expression.
            final byte[] typeInfo = objectTypeInfo(stmt.getObject());
            final boolean typeOnly = typeInfo != null && conf.getRegexSubject() == null && conf.getRegexPredicate() == null
                    && conf.getRegexObject() == null && !conf.isPrefixRowsWithHash();
            final TripleRowRegex regex = typeOnly ? null : strategy.buildRegex(conf.getRegexSubject(), conf.getRegexPredicate(),
                    conf.getRegexObject(), null, typeInfo);
            final ScanSpec spec = spec(stmt.getContext(), stmt.getQualifer(), regex, typeOnly ? typeInfo : null, conf,
                    conf.getBatchSize());
            final CellScanner cells;
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "lookup " + TraceText.pattern(stmt) + " on " + layout)) {
                cells = store.scan(table(layout, conf), range, spec);
            }
            return limit(new RyaStatementCellIterator(layout, ryaContext, cells), conf);
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    /**
     * The statements {@code ?s p ?o} whose object is in a range of one datatype's values
     * (SCALE-STUDY.md O14): one scan of po from {@code p 00 start} to {@code p 00 stop 00 FF},
     * keeping the rows whose object has the range's type. An open end reaches the first or last
     * row of the predicate, and the type check keeps that type's.
     */
    private CloseableIteration<RyaStatement> queryValueRange(final RyaStatement stmt, final RyaValueRange range,
            final StoreRdfConfiguration conf) {
        Preconditions.checkArgument(stmt.getSubject() == null && stmt.getPredicate() != null
                && !(stmt.getPredicate() instanceof RyaRange), "A value range needs a predicate and no subject: %s", stmt);
        try {
            final CellScanner cells;
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "lookup " + TraceText.pattern(stmt) + " on PO")) {
                cells = valueRangeScan(stmt.getPredicate(), stmt.getContext(), stmt.getQualifer(), range, conf, conf.getBatchSize());
            }
            return limit(new RyaStatementCellIterator(TABLE_LAYOUT.PO, ryaContext, cells), conf);
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    /**
     * The subjects of the statements {@code ?s predicate object}, in every graph and in subject
     * order: one range of po, read without decoding the statements (SCALE-STUDY.md O24). For merge
     * joins, which compare only subjects.
     *
     * <p>Each cell gives its subject's UTF-8 bytes, cut from the row between the known
     * {@code p 00 object 00} and the object's type suffix, so a subject with several statements
     * (graphs, labels) comes once per statement. Unsigned byte order is code point order.
     *
     * @param from the first subject included, or null from the first
     * @param to the first subject left out, or null to the last: a slice of the subject space
     */
    public CloseableIteration<byte[]> subjects(final RyaIRI predicate, final RyaType object, final byte[] from,
            final byte[] to, StoreRdfConfiguration conf) {
        Preconditions.checkNotNull(predicate);
        Preconditions.checkNotNull(object);
        if (conf == null) {
            conf = configuration;
        }
        // The hashed layout puts a hash before the predicate, which these rows don't have.
        Preconditions.checkArgument(!Boolean.TRUE.equals(conf.isPrefixRowsWithHash()),
                "Subject scans don't support the hashed row layout");
        try {
            final byte[][] value = RyaContext.getInstance().serializeType(object);
            final byte[] prefix = Bytes.concat(predicate.getData().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    RdfCloudTripleStoreConstants.DELIM_BYTES, value[0], RdfCloudTripleStoreConstants.DELIM_BYTES);
            final byte[] typeInfo = value[1];
            final byte[] start = from == null ? prefix : Bytes.concat(prefix, from);
            final byte[] stop = to == null ? Bytes.concat(prefix, RdfCloudTripleStoreConstants.LAST_BYTES) : Bytes.concat(prefix, to);
            final boolean typeOnly = conf.getRegexSubject() == null && conf.getRegexPredicate() == null
                    && conf.getRegexObject() == null;
            final TripleRowRegex regex = typeOnly ? null : ryaContext.retrieveStrategy(TABLE_LAYOUT.PO).buildRegex(
                    conf.getRegexSubject(), conf.getRegexPredicate(), conf.getRegexObject(), null, typeInfo);
            final ScanSpec spec = spec(null, null, regex, typeOnly ? typeInfo : null, conf, conf.getBatchSize());
            final RyaStatement pattern = new RyaStatement(null, predicate, object);
            final CellScanner cells;
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "lookup " + TraceText.pattern(pattern)
                    + " on PO, subjects only" + (from == null && to == null ? "" : ", one slice"))) {
                cells = store.scan(table(TABLE_LAYOUT.PO, conf), KeyRange.rows(start, true, stop, false), spec);
            }
            return new SubjectIterator(cells, prefix.length, typeInfo);
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    /**
     * The subjects filed under one term (a predicate and an object) in one shard of the entity
     * index (SCALE-STUDY.md O23), in subject order, once per posting: so a subject with several
     * statements (graphs, labels) comes once per statement, as in a po range.
     *
     * @param shardRow the shard's row ({@code EntityIndex.shardRows})
     * @param term the term ({@code EntityIndex.term})
     */
    public CloseableIteration<byte[]> entitySubjects(final byte[] shardRow, final byte[] term, StoreRdfConfiguration conf) {
        if (conf == null) {
            conf = configuration;
        }
        final ScanSpec.Builder spec = ScanSpec.builder(conf.getStoreAuths()).batchSize(conf.getBatchSize()).family(term);
        final Long ttl = conf.getTtl();
        if (ttl != null) {
            final Long now = conf.getStartTime();
            spec.timestamps((now != null ? now : System.currentTimeMillis()) - ttl, now);
        }
        final CellScanner cells = store.scan(conf.getTableLayoutStrategy().getEntity(), KeyRange.row(shardRow), spec.build());
        return new CloseableIteration<>() {
            @Override
            public boolean hasNext() {
                return cells.hasNext();
            }

            @Override
            public byte[] next() {
                return org.tripatha.store.index.EntityIndex.subject(cells.next().qualifier());
            }

            @Override
            public void close() {
                cells.close();
            }
        };
    }

    /**
     * Whether the store intersects the entity index's shards in its servers: the setting is on
     * and the servers can load Tripatha's iterator (asked once per table; Accumulo only).
     */
    public boolean intersectsEntitiesInServers(StoreRdfConfiguration conf) {
        if (conf == null) {
            conf = configuration;
        }
        if (!conf.isEntityIndexServers()) {
            return false;
        }
        return serverIntersection.computeIfAbsent(conf.getTableLayoutStrategy().getEntity(), table -> {
            final boolean loads = store.tables().canLoadIterator(table, org.tripatha.store.index.EntityIndex.ACCUMULO_ITERATOR);
            if (!loads && store.capabilities().scanIterators()) {
                org.slf4j.LoggerFactory.getLogger(RyaStoreQueryEngine.class).info("The servers of {} can't load {}: stars are "
                        + "intersected on the client. Put the tripatha.accumulo.iterators jar on every tablet server.", table,
                        org.tripatha.store.index.EntityIndex.ACCUMULO_ITERATOR);
            }
            return loads;
        });
    }

    /**
     * The subjects every term has in each shard of the entity index, intersected in the servers
     * (SCALE-STUDY.md O23): one batch scan over every shard row with Tripatha's iterator, which
     * returns one cell per match. Unordered across shards. Only when
     * {@link #intersectsEntitiesInServers} says so.
     */
    public CloseableIteration<org.tripatha.store.index.EntityIndex.Match> entityMatches(final List<byte[]> shardRows,
            final List<byte[]> terms, StoreRdfConfiguration conf) {
        if (conf == null) {
            conf = configuration;
        }
        final ScanSpec.Builder spec = ScanSpec.builder(conf.getStoreAuths()).threads(numThreads(conf))
                .iterator(new ServerIterator(50, "ei", org.tripatha.store.index.EntityIndex.ACCUMULO_ITERATOR,
                        java.util.Map.of("terms", encodeTerms(terms))));
        final Long ttl = conf.getTtl();
        if (ttl != null) {
            final Long now = conf.getStartTime();
            spec.timestamps((now != null ? now : System.currentTimeMillis()) - ttl, now);
        }
        final List<KeyRange> rows = new ArrayList<>(shardRows.size());
        for (final byte[] row : shardRows) {
            rows.add(KeyRange.row(row));
        }
        final CellScanner cells = store.batchScan(conf.getTableLayoutStrategy().getEntity(), rows, spec.build());
        return new CloseableIteration<>() {
            @Override
            public boolean hasNext() {
                return cells.hasNext();
            }

            @Override
            public org.tripatha.store.index.EntityIndex.Match next() {
                final Cell cell = cells.next();
                return new org.tripatha.store.index.EntityIndex.Match(cell.qualifier(),
                        Long.parseLong(new String(cell.value(), java.nio.charset.StandardCharsets.UTF_8)));
            }

            @Override
            public void close() {
                cells.close();
            }
        };
    }

    /** The iterator's {@code terms} option: each term in Base64, separated by commas. */
    private static String encodeTerms(final List<byte[]> terms) {
        final StringBuilder b = new StringBuilder();
        for (final byte[] term : terms) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(java.util.Base64.getEncoder().encodeToString(term));
        }
        return b.toString();
    }

    /** The subject bytes of po rows {@code p 00 object 00 subject typeInfo}. */
    private static final class SubjectIterator implements CloseableIteration<byte[]> {
        private final CellScanner cells;
        private final int prefixLength;
        private final byte[] typeInfo;
        private byte[] next;

        SubjectIterator(final CellScanner cells, final int prefixLength, final byte[] typeInfo) {
            this.cells = cells;
            this.prefixLength = prefixLength;
            this.typeInfo = typeInfo;
        }

        @Override
        public boolean hasNext() {
            while (next == null && cells.hasNext()) {
                final byte[] row = cells.next().row();
                final int end = row.length - typeInfo.length;
                // The scan keeps only rows of the object's type; check the suffix anyway.
                if (end >= prefixLength && Arrays.equals(row, end, row.length, typeInfo, 0, typeInfo.length)) {
                    next = Arrays.copyOfRange(row, prefixLength, end);
                }
            }
            return next != null;
        }

        @Override
        public byte[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final byte[] subject = next;
            next = null;
            return subject;
        }

        @Override
        public void close() {
            cells.close();
        }
    }

    /**
     * Counts the cells of {@code ?s predicate ?o} in these value ranges, in every graph, stopping
     * at {@code cap} (SCALE-STUDY.md O25): the cells a lookup of the ranges would read. The cells
     * are counted, not decoded, and the ranges share the cap.
     *
     * @return the count, or {@code cap} when the ranges hold that many cells or more
     */
    public long countValueRanges(final RyaIRI predicate, final List<RyaValueRange> ranges, final long cap,
            StoreRdfConfiguration conf) {
        Preconditions.checkArgument(cap > 0, "The cap must be positive");
        if (conf == null) {
            conf = configuration;
        }
        long counted = 0;
        try {
            for (final RyaValueRange range : ranges) {
                // One batch holds the rest of the cap, so a range is one round trip.
                final int batch = (int) Math.min(Integer.MAX_VALUE - 1, cap - counted) + 1;
                try (CellScanner cells = valueRangeScan(predicate, null, null, range, conf, batch)) {
                    while (counted < cap && cells.hasNext()) {
                        cells.next();
                        counted++;
                    }
                }
                if (counted >= cap) {
                    return cap;
                }
            }
            return counted;
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    /**
     * One scan of po from {@code p 00 start} to {@code p 00 stop 00 FF}, keeping the rows whose
     * object has the range's type. An open end reaches the first or last row of the predicate,
     * and the type check keeps that type's.
     */
    private CellScanner valueRangeScan(final RyaIRI predicate, final RyaIRI context, final String qualifier,
            final RyaValueRange range, final StoreRdfConfiguration conf, final Integer batchSize) throws Exception {
        // The hashed layout puts a hash before the predicate, which these rows don't have.
        Preconditions.checkArgument(!Boolean.TRUE.equals(conf.isPrefixRowsWithHash()),
                "Value ranges don't support the hashed row layout");
        final byte[] prefix = Bytes.concat(predicate.getData().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                RdfCloudTripleStoreConstants.DELIM_BYTES);
        final byte[] startData = range.getStartData();
        final byte[] stopData = range.getStopData();
        final byte[] start = startData == null ? prefix : Bytes.concat(prefix, startData);
        final byte[] stop = stopData == null ? Bytes.concat(prefix, RdfCloudTripleStoreConstants.LAST_BYTES)
                : Bytes.concat(prefix, stopData, RdfCloudTripleStoreConstants.DELIM_BYTES, RdfCloudTripleStoreConstants.LAST_BYTES);
        final byte[] typeInfo = { RdfCloudTripleStoreConstants.TYPE_DELIM_BYTE, range.getMarker() };
        final boolean typeOnly = conf.getRegexSubject() == null && conf.getRegexPredicate() == null
                && conf.getRegexObject() == null;
        final TripleRowRegex regex = typeOnly ? null : ryaContext.retrieveStrategy(TABLE_LAYOUT.PO).buildRegex(
                conf.getRegexSubject(), conf.getRegexPredicate(), conf.getRegexObject(), null, typeInfo);
        final ScanSpec spec = spec(context, qualifier, regex, typeOnly ? typeInfo : null, conf, batchSize);
        return store.scan(table(TABLE_LAYOUT.PO, conf), KeyRange.closed(start, stop), spec);
    }

    /**
     * Returns the union of the statements matching each pattern. All patterns must resolve to
     * the same table and share a context.
     */
    @Override
    public CloseableIteration<RyaStatement> batchQuery(final Collection<RyaStatement> stmts, StoreRdfConfiguration conf) {
        Preconditions.checkNotNull(stmts);
        Preconditions.checkArgument(!stmts.isEmpty(), "No statements to query");
        if (conf == null) {
            conf = configuration;
        }
        try {
            final Set<KeyRange> ranges = new LinkedHashSet<>();
            TABLE_LAYOUT layout = null;
            TriplePatternStrategy strategy = null;
            RyaIRI context = null;
            for (final RyaStatement stmt : stmts) {
                context = stmt.getContext();
                strategy = ryaContext.retrieveStrategy(stmt);
                if (strategy == null) {
                    throw new IllegalArgumentException("TriplePattern[" + stmt + "] not supported");
                }
                final Map.Entry<TABLE_LAYOUT, ByteRange> entry = strategy.defineRange(stmt.getSubject(),
                        stmt.getPredicate(), stmt.getObject(), stmt.getContext(), conf);
                if (layout != null && layout != entry.getKey()) {
                    throw new IllegalArgumentException("All batched patterns must use the same table");
                }
                layout = entry.getKey();
                ranges.add(toRange(entry.getValue()));
            }
            final TripleRowRegex regex = strategy.buildRegex(conf.getRegexSubject(), conf.getRegexPredicate(),
                    conf.getRegexObject(), null, null);
            final String table = table(layout, conf);
            final TABLE_LAYOUT batchLayout = layout;
            final int patterns = stmts.size();
            final StoreTrace.Reason why = StoreTrace.reason(() -> "batched lookup of " + patterns + " pattern"
                    + (patterns == 1 ? "" : "s") + " on " + batchLayout);
            try {
                if (ranges.size() > conf.getMaxRangesForScanner()) {
                    final ScanSpec spec = spec(context, null, regex, null, conf, null).toBuilder().threads(numThreads(conf)).build();
                    return limit(new RyaStatementCellIterator(layout, ryaContext, store.batchScan(table, ranges, spec)), conf);
                }
                final ScanSpec spec = spec(context, null, regex, null, conf, null);
                final List<CellScanner> scanners = new ArrayList<>(ranges.size());
                try {
                    for (final KeyRange range : ranges) {
                        scanners.add(store.scan(table, range, spec));
                    }
                } catch (final RuntimeException e) {
                    scanners.forEach(CellScanner::close);
                    throw e;
                }
                return limit(new RyaStatementCellIterator(layout, ryaContext, new Concat(scanners)), conf);
            } finally {
                why.close();
            }
        } catch (final RyaDAOException e) {
            throw e;
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    /** The cells of several scanners, one after the other; closing it closes them all. */
    private static final class Concat implements CellScanner {
        private final List<CellScanner> parts;
        private final Iterator<CellScanner> next;
        private CellScanner current;

        Concat(final List<CellScanner> parts) {
            this.parts = parts;
            this.next = parts.iterator();
        }

        @Override
        public boolean hasNext() {
            while ((current == null || !current.hasNext()) && next.hasNext()) {
                current = next.next();
            }
            return current != null && current.hasNext();
        }

        @Override
        public Cell next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return current.next();
        }

        @Override
        public void close() {
            for (final CellScanner s : parts) {
                s.close();
            }
        }
    }

    private static RyaStatementCellIterator limit(final RyaStatementCellIterator iter, final StoreRdfConfiguration conf) {
        final Long maxResults = conf.getLimit();
        if (maxResults != null) {
            iter.setMaxResults(maxResults);
        }
        return iter;
    }

    private static int numThreads(final StoreRdfConfiguration conf) {
        final Integer n = conf.getNumThreads();
        return n == null || n < 1 ? 4 : n;
    }

    /** Tripatha's ranges include both their start and their end row (Accumulo's {@code Range(Text, Text)}). */
    private static KeyRange toRange(final ByteRange byteRange) {
        return KeyRange.closed(byteRange.getStart(), byteRange.getEnd());
    }

    private static byte[] objectTypeInfo(final RyaType object) throws Exception {
        if (object == null || object instanceof RyaAnyTypeRange) {
            return null; // no object, or a range of any type: no type check
        }
        final RyaType typed = object instanceof RyaRange ? ((RyaRange) object).getStart() : object;
        return RyaContext.getInstance().serializeType(typed)[1];
    }

    private static String table(final TABLE_LAYOUT layout, final StoreRdfConfiguration conf) {
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        return RdfCloudTripleStoreUtils.layoutToTable(layout, tables);
    }

    /**
     * What to read: the context's column family (and the qualifier's column, or a qualifier
     * filter without a context), the TTL window, the row regex or suffix and any extra iterators.
     */
    private static ScanSpec spec(final RyaIRI context, final String qualifier, final TripleRowRegex tripleRowRegex,
            final byte[] rowSuffix, final StoreRdfConfiguration conf, final Integer batchSize) {
        final ScanSpec.Builder spec = ScanSpec.builder(conf.getStoreAuths()).batchSize(batchSize);
        if (context != null && qualifier != null) {
            spec.column(utf8(context.getData()), utf8(qualifier));
        } else if (context != null) {
            spec.family(utf8(context.getData()));
        } else if (qualifier != null) {
            spec.qualifierRegex(qualifier);
        }
        final Long ttl = conf.getTtl();
        if (ttl != null) {
            final Long currentTime = conf.getStartTime();
            if (currentTime != null) {
                spec.timestamps(currentTime - ttl, currentTime);
            } else {
                spec.timestamps(System.currentTimeMillis() - ttl, null);
            }
        }
        if (tripleRowRegex != null) {
            spec.rowRegex(tripleRowRegex.getRow());
        }
        if (rowSuffix != null) {
            spec.rowSuffix(rowSuffix);
        }
        for (final ServerIterator it : conf.getServerIterators()) {
            spec.iterator(it);
        }
        return spec.build();
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void setConf(final StoreRdfConfiguration conf) {
        this.configuration = conf;
    }

    @Override
    public StoreRdfConfiguration getConf() {
        return configuration;
    }

    @Override
    public void close() {
    }
}
