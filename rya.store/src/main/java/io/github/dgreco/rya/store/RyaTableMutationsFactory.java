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
package io.github.dgreco.rya.store;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;
import io.github.dgreco.rya.api.resolver.triple.TripleRowResolverException;
import io.github.dgreco.rya.store.spi.RowMutation;

/** The spo, po and osp cells of a statement: one row mutation per table. */
public class RyaTableMutationsFactory {
    private final RyaTripleContext ryaContext;

    public RyaTableMutationsFactory(final RyaTripleContext ryaContext) {
        this.ryaContext = ryaContext;
    }

    /** The puts of a statement, by table. */
    public Map<TABLE_LAYOUT, RowMutation> serialize(final RyaStatement stmt) throws IOException {
        return build(stmt, false);
    }

    /** The deletes of a stored statement (exact label and timestamp), by table. */
    public Map<TABLE_LAYOUT, RowMutation> serializeDelete(final RyaStatement stmt) throws IOException {
        return build(stmt, true);
    }

    private Map<TABLE_LAYOUT, RowMutation> build(final RyaStatement stmt, final boolean delete) throws IOException {
        final Map<TABLE_LAYOUT, TripleRow> rows;
        try {
            rows = ryaContext.serializeTriple(stmt);
        } catch (final TripleRowResolverException e) {
            throw new IOException(e);
        }
        final Map<TABLE_LAYOUT, RowMutation> out = new EnumMap<>(TABLE_LAYOUT.class);
        for (final TABLE_LAYOUT layout : new TABLE_LAYOUT[] {TABLE_LAYOUT.SPO, TABLE_LAYOUT.PO, TABLE_LAYOUT.OSP}) {
            out.put(layout, delete ? deleteMutation(rows.get(layout)) : createMutation(rows.get(layout)));
        }
        return out;
    }

    protected RowMutation deleteMutation(final TripleRow row) {
        return new RowMutation(row.getRow()).delete(row.getColumnFamily(), row.getColumnQualifier(),
                row.getColumnVisibility(), row.getTimestamp());
    }

    protected RowMutation createMutation(final TripleRow row) {
        return new RowMutation(row.getRow()).put(row.getColumnFamily(), row.getColumnQualifier(),
                row.getColumnVisibility(), row.getTimestamp(), row.getValue());
    }
}
