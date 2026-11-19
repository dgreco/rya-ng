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
package org.tripatha.api.resolver.triple.impl;

import static org.tripatha.api.RdfCloudTripleStoreConstants.DELIM_BYTE;
import static org.tripatha.api.RdfCloudTripleStoreConstants.DELIM_BYTES;
import static org.tripatha.api.RdfCloudTripleStoreConstants.EMPTY_BYTES;
import static org.tripatha.api.RdfCloudTripleStoreConstants.TYPE_DELIM_BYTE;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.resolver.RyaContext;
import org.tripatha.api.resolver.RyaTypeResolverException;
import org.tripatha.api.resolver.triple.TripleRow;
import org.tripatha.api.resolver.triple.TripleRowResolver;
import org.tripatha.api.resolver.triple.TripleRowResolverException;

import com.google.common.primitives.Bytes;

/**
 * Will store triple in spo, po, osp. Storing everything in the whole row.
 * Date: 7/13/12
 * Time: 8:51 AM
 */
public class WholeRowTripleResolver implements TripleRowResolver {

    @Override
    public Map<TABLE_LAYOUT, TripleRow> serialize(final RyaStatement stmt) throws TripleRowResolverException {
        try {
            final RyaIRI subject = stmt.getSubject();
            final RyaIRI predicate = stmt.getPredicate();
            final RyaType object = stmt.getObject();
            final RyaIRI context = stmt.getContext();
            final Long timestamp = stmt.getTimestamp();
            final byte[] columnVisibility = stmt.getColumnVisibility();
            final String qualifer = stmt.getQualifer();
            final byte[] qualBytes = qualifer == null ? EMPTY_BYTES : qualifer.getBytes(StandardCharsets.UTF_8);
            final byte[] value = stmt.getValue();
            assert subject != null && predicate != null && object != null;
            final byte[] cf = (context == null) ? EMPTY_BYTES : context.getData().getBytes(StandardCharsets.UTF_8);
            final Map<TABLE_LAYOUT, TripleRow> tripleRowMap = new HashMap<TABLE_LAYOUT, TripleRow>();
            final byte[] subjBytes = subject.getData().getBytes(StandardCharsets.UTF_8);
            final byte[] predBytes = predicate.getData().getBytes(StandardCharsets.UTF_8);
            final byte[][] objBytes = RyaContext.getInstance().serializeType(object);
            tripleRowMap.put(TABLE_LAYOUT.SPO,
                    new TripleRow(Bytes.concat(subjBytes, DELIM_BYTES,
                            predBytes, DELIM_BYTES,
                            objBytes[0], objBytes[1]), cf, qualBytes,
                            timestamp, columnVisibility, value));
            tripleRowMap.put(TABLE_LAYOUT.PO,
                    new TripleRow(Bytes.concat(predBytes, DELIM_BYTES,
                            objBytes[0], DELIM_BYTES,
                            subjBytes, objBytes[1]), cf, qualBytes,
                            timestamp, columnVisibility, value));
            tripleRowMap.put(TABLE_LAYOUT.OSP,
                    new TripleRow(Bytes.concat(objBytes[0], DELIM_BYTES,
                            subjBytes, DELIM_BYTES,
                            predBytes, objBytes[1]), cf, qualBytes,
                            timestamp, columnVisibility, value));
            return tripleRowMap;
        } catch (final RyaTypeResolverException e) {
            throw new TripleRowResolverException(e);
        }
    }

    @Override
    public RyaStatement deserialize(final TABLE_LAYOUT table_layout, final TripleRow tripleRow) throws TripleRowResolverException {
        try {
            assert tripleRow != null && table_layout != null;
            final byte[] row = tripleRow.getRow();
            // One pass for the two delimiters and the type marker; the parts are read in place.
            int firstIndex = -1;
            int secondIndex = -1;
            int typeIndex = -1;
            for (int i = 0; i < row.length; i++) {
                if (row[i] == DELIM_BYTE) {
                    if (firstIndex < 0) {
                        firstIndex = i;
                    }
                    secondIndex = i;
                } else if (row[i] == TYPE_DELIM_BYTE && typeIndex < 0) {
                    typeIndex = i;
                }
            }
            if (firstIndex < 0 || secondIndex == firstIndex || typeIndex < secondIndex) {
                throw new TripleRowResolverException("TripleRow[" + tripleRow + "] is not a statement row");
            }
            final byte[] columnFamily = tripleRow.getColumnFamily();
            final boolean contextExists = columnFamily != null && columnFamily.length > 0;
            final RyaIRI context = (contextExists) ? (new RyaIRI(new String(columnFamily, StandardCharsets.UTF_8))) : null;
            final byte[] columnQualifier = tripleRow.getColumnQualifier();
            final String qualifier = columnQualifier != null && columnQualifier.length > 0 ? new String(columnQualifier, StandardCharsets.UTF_8) : null;
            final Long timestamp = tripleRow.getTimestamp();
            final byte[] columnVisibility = tripleRow.getColumnVisibility();
            final byte[] value = tripleRow.getValue();

            switch (table_layout) {
                case SPO: {
                    // s 00 p 00 o 01 type: the object and its type are the row's last run.
                    final byte[] obj = Arrays.copyOfRange(row, secondIndex + 1, row.length);
                    return new RyaStatement(
                            new RyaIRI(new String(row, 0, firstIndex, StandardCharsets.UTF_8)),
                            new RyaIRI(new String(row, firstIndex + 1, secondIndex - firstIndex - 1, StandardCharsets.UTF_8)),
                            RyaContext.getInstance().deserialize(obj),
                            context, qualifier, columnVisibility, value, timestamp);
                }
                case PO: {
                    // p 00 o 00 s 01 type
                    final byte[] obj = joined(row, firstIndex + 1, secondIndex, typeIndex);
                    return new RyaStatement(
                            new RyaIRI(new String(row, secondIndex + 1, typeIndex - secondIndex - 1, StandardCharsets.UTF_8)),
                            new RyaIRI(new String(row, 0, firstIndex, StandardCharsets.UTF_8)),
                            RyaContext.getInstance().deserialize(obj),
                            context, qualifier, columnVisibility, value, timestamp);
                }
                case OSP: {
                    // o 00 s 00 p 01 type
                    final byte[] obj = joined(row, 0, firstIndex, typeIndex);
                    return new RyaStatement(
                            new RyaIRI(new String(row, firstIndex + 1, secondIndex - firstIndex - 1, StandardCharsets.UTF_8)),
                            new RyaIRI(new String(row, secondIndex + 1, typeIndex - secondIndex - 1, StandardCharsets.UTF_8)),
                            RyaContext.getInstance().deserialize(obj),
                            context, qualifier, columnVisibility, value, timestamp);
                }
            }
        } catch (final RyaTypeResolverException e) {
            throw new TripleRowResolverException(e);
        }
        throw new TripleRowResolverException("TripleRow[" + tripleRow + "] with Table layout[" + table_layout + "] is not deserializable");
    }

    /** {@code row[from, to)} followed by the type, {@code row[typeIndex, end)}: an object and its type, apart in the row. */
    private static byte[] joined(final byte[] row, final int from, final int to, final int typeIndex) {
        final int dataLength = to - from;
        final byte[] out = new byte[dataLength + row.length - typeIndex];
        System.arraycopy(row, from, out, 0, dataLength);
        System.arraycopy(row, typeIndex, out, dataLength, row.length - typeIndex);
        return out;
    }

}
