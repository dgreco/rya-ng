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
package io.github.dgreco.rya.store.spi;

import java.util.List;
import java.util.SortedSet;

/** Tables of a store. */
public interface TableAdmin {

    boolean exists(String table);

    /**
     * Creates a table with the settings its kind needs, unless it exists. Safe when several
     * clients race to create it.
     *
     * @return true if this call created the table
     */
    boolean create(String table, TableKind kind);

    /**
     * Makes sure an existing table has the settings its kind needs (the counter combiner, for
     * {@link TableKind#COUNTERS}). An account that may not read table settings assumes they are
     * there: whoever created the table set them.
     */
    void ensureSettings(String table, TableKind kind);

    /** Deletes a table; does nothing if it doesn't exist. */
    void delete(String table);

    /** Deletes every row of a table, whatever its labels, and keeps the table. */
    void deleteRows(String table);

    /** Every table name. */
    List<String> list();

    /** Adds split points (rows where a table is divided between servers). */
    void addSplits(String table, SortedSet<byte[]> splits);

    /** The number of split points a table has. */
    int splitCount(String table);

    /** The number of servers holding data. */
    int serverCount();
}
