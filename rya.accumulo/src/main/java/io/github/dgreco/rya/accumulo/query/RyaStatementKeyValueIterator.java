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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;
import io.github.dgreco.rya.api.resolver.triple.TripleRowResolverException;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;

/**
 * Turns the key/values of a scan over one of the SPO/PO/OSP tables into {@link RyaStatement}s.
 * Closing the iteration closes the underlying scanners.
 */
public class RyaStatementKeyValueIterator implements CloseableIteration<RyaStatement> {
    private final TABLE_LAYOUT tableLayout;
    private final RyaTripleContext context;
    private final List<? extends ScannerBase> scanners;
    private Iterator<Map.Entry<Key, Value>> dataIterator;
    private long remaining = -1L;

    public RyaStatementKeyValueIterator(final TABLE_LAYOUT tableLayout, final RyaTripleContext context,
            final Iterator<Map.Entry<Key, Value>> dataIterator, final List<? extends ScannerBase> scanners) {
        this.tableLayout = tableLayout;
        this.context = context;
        this.dataIterator = dataIterator;
        this.scanners = scanners;
    }

    /** Stops after {@code maxResults} statements; a negative value means no limit. */
    public void setMaxResults(final long maxResults) {
        this.remaining = maxResults;
    }

    @Override
    public boolean hasNext() {
        return dataIterator != null && remaining != 0 && dataIterator.hasNext();
    }

    @Override
    public RyaStatement next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        final Map.Entry<Key, Value> next = dataIterator.next();
        final Key key = next.getKey();
        final Value value = next.getValue();
        try {
            final RyaStatement statement = context.deserializeTriple(tableLayout,
                    new TripleRow(key.getRowData().toArray(), key.getColumnFamilyData().toArray(),
                            key.getColumnQualifierData().toArray(), key.getTimestamp(),
                            key.getColumnVisibilityData().toArray(), value != null ? value.get() : null));
            if (remaining > 0) {
                remaining--;
            }
            return statement;
        } catch (final TripleRowResolverException e) {
            throw new RyaDAOException(e);
        }
    }

    @Override
    public void close() {
        dataIterator = null;
        for (final ScannerBase scanner : scanners) {
            scanner.close();
        }
    }
}
