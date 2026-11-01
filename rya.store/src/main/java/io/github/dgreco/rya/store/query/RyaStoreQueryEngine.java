package io.github.dgreco.rya.store.query;

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
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;

import com.google.common.base.Preconditions;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.RdfCloudTripleStoreUtils;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaRange;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.persist.query.RyaQueryEngine;
import io.github.dgreco.rya.api.query.strategy.ByteRange;
import io.github.dgreco.rya.api.query.strategy.TriplePatternStrategy;
import io.github.dgreco.rya.api.resolver.RyaContext;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.api.resolver.triple.TripleRowRegex;
import io.github.dgreco.rya.store.StoreRdfConfiguration;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.ServerIterator;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.store.trace.StoreTrace;
import io.github.dgreco.rya.store.trace.TraceText;

/**
 * Answers statement patterns by range-scanning whichever of the SPO, PO or OSP tables
 * has the bound parts of the pattern as a row prefix.
 */
public class RyaStoreQueryEngine implements RyaQueryEngine<StoreRdfConfiguration> {

    private final Store store;
    private StoreRdfConfiguration configuration;
    private final RyaTripleContext ryaContext;

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
            final TripleRowRegex regex = strategy.buildRegex(conf.getRegexSubject(), conf.getRegexPredicate(),
                    conf.getRegexObject(), null, objectTypeInfo(stmt.getObject()));
            final ScanSpec spec = spec(stmt.getContext(), stmt.getQualifer(), regex, conf, conf.getBatchSize());
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
                    final ScanSpec spec = spec(context, null, regex, conf, null).toBuilder().threads(numThreads(conf)).build();
                    return limit(new RyaStatementCellIterator(layout, ryaContext, store.batchScan(table, ranges, spec)), conf);
                }
                final ScanSpec spec = spec(context, null, regex, conf, null);
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

    /** Rya's ranges include both their start and their end row (Accumulo's {@code Range(Text, Text)}). */
    private static KeyRange toRange(final ByteRange byteRange) {
        return KeyRange.closed(byteRange.getStart(), byteRange.getEnd());
    }

    private static byte[] objectTypeInfo(final RyaType object) throws Exception {
        if (object == null) {
            return null;
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
     * filter without a context), the TTL window, the row regex and any extra iterators.
     */
    private static ScanSpec spec(final RyaIRI context, final String qualifier, final TripleRowRegex tripleRowRegex,
            final StoreRdfConfiguration conf, final Integer batchSize) {
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
