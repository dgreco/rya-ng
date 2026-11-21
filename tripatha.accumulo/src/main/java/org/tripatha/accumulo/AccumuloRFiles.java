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
package org.tripatha.accumulo;

import java.util.Map;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.hadoop.conf.Configuration;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.BulkImport;

/**
 * Accumulo's bulk files for a process with no connection to Accumulo, such as a Spark executor
 * (tripatha.spark): the driver reads each table's configuration once, and the executors write
 * RFiles with it, exactly as the loader's bulk build writes them ({@link AccumuloBulkImport}).
 */
public final class AccumuloRFiles {
    /** The largest key a bulk file may hold: Accumulo's default key size constraint (an import skips constraints). */
    public static final int MAX_KEY_BYTES = AccumuloBulkImport.MAX_KEY_BYTES;

    private AccumuloRFiles() {
    }

    /** A table's configuration, which the files are written with (compression, block sizes). */
    public static Map<String, String> tableProperties(final AccumuloClient client, final String table) {
        try {
            return client.tableOperations().getConfiguration(table);
        } catch (final Exception e) {
            throw new RyaDAOException("Could not read the configuration of " + table, e);
        }
    }

    /**
     * An RFile {@code dir/name.rf} on the file system of {@code dir}, for one table and one of its
     * tablets: puts or counter deltas (decimal text, stamped with {@code counterTimestamp}), in
     * key order.
     */
    public static BulkImport.BulkFile open(final Map<String, String> tableProperties, final Configuration conf, final String dir,
            final String name, final long counterTimestamp) {
        return AccumuloBulkImport.open(tableProperties, conf, dir, name, counterTimestamp);
    }
}
