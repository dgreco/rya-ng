package io.github.dgreco.rya.accumulo.query;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.iterators.user.TimestampFilter;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
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
import org.eclipse.rdf4j.common.iteration.CloseableIteration;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;

/**
 * Answers statement patterns by range-scanning whichever of the SPO, PO or OSP tables
 * has the bound parts of the pattern as a row prefix.
 */
public class AccumuloRyaQueryEngine implements RyaQueryEngine<AccumuloRdfConfiguration> {

    private final AccumuloClient client;
    private AccumuloRdfConfiguration configuration;
    private final RyaTripleContext ryaContext;

    public AccumuloRyaQueryEngine(final AccumuloClient client, final AccumuloRdfConfiguration conf) {
        this.client = Preconditions.checkNotNull(client);
        this.configuration = Preconditions.checkNotNull(conf);
        this.ryaContext = RyaTripleContext.getInstance(conf);
    }

    @Override
    public CloseableIteration<RyaStatement> query(final RyaStatement stmt, AccumuloRdfConfiguration conf) {
        Preconditions.checkNotNull(stmt);
        if (conf == null) {
            conf = configuration;
        }
        try {
            TriplePatternStrategy strategy = ryaContext.retrieveStrategy(stmt);
            final TABLE_LAYOUT layout;
            final Range range;
            if (strategy != null) {
                final Map.Entry<TABLE_LAYOUT, ByteRange> entry = strategy.defineRange(stmt.getSubject(),
                        stmt.getPredicate(), stmt.getObject(), stmt.getContext(), conf);
                layout = entry.getKey();
                range = toRange(entry.getValue());
            } else {
                // Nothing bound: full scan of SPO.
                layout = TABLE_LAYOUT.SPO;
                range = new Range();
                strategy = ryaContext.retrieveStrategy(layout);
            }

            final TripleRowRegex regex = strategy.buildRegex(conf.getRegexSubject(), conf.getRegexPredicate(),
                    conf.getRegexObject(), null, objectTypeInfo(stmt.getObject()));

            final Scanner scanner = client.createScanner(table(layout, conf), conf.getAuthorizations());
            scanner.setRange(range);
            final Integer batchSize = conf.getBatchSize();
            if (batchSize != null) {
                scanner.setBatchSize(batchSize);
            }
            fillScanner(scanner, stmt.getContext(), stmt.getQualifer(), regex, conf);
            return limit(new RyaStatementKeyValueIterator(layout, ryaContext, scanner.iterator(),
                    Collections.singletonList(scanner)), conf);
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    /**
     * Returns the union of the statements matching each pattern. All patterns must resolve to
     * the same table and share a context.
     */
    @Override
    public CloseableIteration<RyaStatement> batchQuery(final Collection<RyaStatement> stmts, AccumuloRdfConfiguration conf) {
        Preconditions.checkNotNull(stmts);
        Preconditions.checkArgument(!stmts.isEmpty(), "No statements to query");
        if (conf == null) {
            conf = configuration;
        }
        try {
            final Set<Range> ranges = new LinkedHashSet<>();
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
            final Authorizations auths = conf.getAuthorizations();

            if (ranges.size() > conf.getMaxRangesForScanner()) {
                final BatchScanner scanner = client.createBatchScanner(table, auths, numThreads(conf));
                scanner.setRanges(ranges);
                fillScanner(scanner, context, null, regex, conf);
                return limit(new RyaStatementKeyValueIterator(layout, ryaContext, scanner.iterator(),
                        Collections.singletonList(scanner)), conf);
            }

            final List<Scanner> scanners = new ArrayList<>(ranges.size());
            final List<Iterator<Map.Entry<Key, Value>>> iters = new ArrayList<>(ranges.size());
            for (final Range range : ranges) {
                final Scanner scanner = client.createScanner(table, auths);
                scanner.setRange(range);
                fillScanner(scanner, context, null, regex, conf);
                scanners.add(scanner);
                iters.add(scanner.iterator());
            }
            return limit(new RyaStatementKeyValueIterator(layout, ryaContext, Iterators.concat(iters.iterator()), scanners), conf);
        } catch (final Exception e) {
            throw new RyaDAOException(e);
        }
    }

    private static RyaStatementKeyValueIterator limit(final RyaStatementKeyValueIterator iter, final AccumuloRdfConfiguration conf) {
        final Long maxResults = conf.getLimit();
        if (maxResults != null) {
            iter.setMaxResults(maxResults);
        }
        return iter;
    }

    private static int numThreads(final AccumuloRdfConfiguration conf) {
        final Integer n = conf.getNumThreads();
        return n == null || n < 1 ? 4 : n;
    }

    private static Range toRange(final ByteRange byteRange) {
        return new Range(new Text(byteRange.getStart()), new Text(byteRange.getEnd()));
    }

    private static byte[] objectTypeInfo(final RyaType object) throws Exception {
        if (object == null) {
            return null;
        }
        final RyaType typed = object instanceof RyaRange ? ((RyaRange) object).getStart() : object;
        return RyaContext.getInstance().serializeType(typed)[1];
    }

    private static String table(final TABLE_LAYOUT layout, final AccumuloRdfConfiguration conf) {
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        return RdfCloudTripleStoreUtils.layoutToTable(layout, tables);
    }

    private static void fillScanner(final ScannerBase scanner, final RyaIRI context, final String qualifier,
            final TripleRowRegex tripleRowRegex, final AccumuloRdfConfiguration conf) throws IOException {
        if (context != null && qualifier != null) {
            scanner.fetchColumn(new Text(context.getData()), new Text(qualifier));
        } else if (context != null) {
            scanner.fetchColumnFamily(new Text(context.getData()));
        } else if (qualifier != null) {
            final IteratorSetting setting = new IteratorSetting(8, "riq", RegExFilter.class.getName());
            RegExFilter.setRegexs(setting, null, null, qualifier, null, false);
            scanner.addScanIterator(setting);
        }
        final Long ttl = conf.getTtl();
        if (ttl != null) {
            final IteratorSetting setting = new IteratorSetting(9, "fi", TimestampFilter.class.getName());
            final Long currentTime = conf.getStartTime();
            if (currentTime != null) {
                TimestampFilter.setStart(setting, currentTime - ttl, true);
                TimestampFilter.setEnd(setting, currentTime, true);
            } else {
                TimestampFilter.setStart(setting, System.currentTimeMillis() - ttl, true);
            }
            scanner.addScanIterator(setting);
        }
        if (tripleRowRegex != null) {
            final IteratorSetting setting = new IteratorSetting(11, "ri", RegExFilter.class.getName());
            RegExFilter.setRegexs(setting, tripleRowRegex.getRow(), null, null, null, false);
            scanner.addScanIterator(setting);
        }
        for (final IteratorSetting itr : conf.getAdditionalIterators()) {
            scanner.addScanIterator(itr);
        }
    }

    @Override
    public void setConf(final AccumuloRdfConfiguration conf) {
        this.configuration = conf;
    }

    @Override
    public AccumuloRdfConfiguration getConf() {
        return configuration;
    }

    @Override
    public void close() {
    }
}
