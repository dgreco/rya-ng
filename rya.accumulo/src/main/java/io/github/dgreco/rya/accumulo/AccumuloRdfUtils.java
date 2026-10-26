package io.github.dgreco.rya.accumulo;

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

import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.EMPTY_BYTES;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AccumuloRdfUtils {
    private static final Logger logger = LoggerFactory.getLogger(AccumuloRdfUtils.class);

    private AccumuloRdfUtils() {
    }

    /**
     * Creates the table unless it exists. Safe when several clients race to create it.
     *
     * @return true if this call created the table.
     */
    public static boolean createTableIfNotExist(final TableOperations tableOperations, final String tableName)
            throws AccumuloException, AccumuloSecurityException {
        if (tableOperations.exists(tableName)) {
            return false;
        }
        logger.debug("Creating accumulo table: {}", tableName);
        try {
            tableOperations.create(tableName);
            return true;
        } catch (final TableExistsException e) {
            return false;
        }
    }

    public static Key from(final TripleRow tripleRow) {
        return new Key(defaultTo(tripleRow.getRow(), EMPTY_BYTES),
                defaultTo(tripleRow.getColumnFamily(), EMPTY_BYTES),
                defaultTo(tripleRow.getColumnQualifier(), EMPTY_BYTES),
                defaultTo(tripleRow.getColumnVisibility(), EMPTY_BYTES),
                tripleRow.getTimestamp() != null ? tripleRow.getTimestamp() : Long.MAX_VALUE);
    }

    public static Value extractValue(final TripleRow tripleRow) {
        return new Value(defaultTo(tripleRow.getValue(), EMPTY_BYTES));
    }

    private static byte[] defaultTo(final byte[] bytes, final byte[] def) {
        return bytes != null ? bytes : def;
    }
}
