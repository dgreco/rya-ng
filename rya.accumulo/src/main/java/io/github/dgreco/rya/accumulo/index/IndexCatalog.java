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
package io.github.dgreco.rya.accumulo.index;

import java.util.Set;

/** What queries need to know about the secondary indexes. Reads are in memory. */
public interface IndexCatalog {
    /** A catalog with no index. */
    IndexCatalog NONE = new IndexCatalog() {
        @Override
        public boolean isReady(final IndexType type) {
            return false;
        }

        @Override
        public Set<String> textPredicates() {
            return Set.of();
        }

        @Override
        public String table(final IndexType type) {
            throw new IllegalStateException("No " + type.id() + " index");
        }
    };

    /** Whether the index is enabled and covers every statement, so queries may use it. */
    boolean isReady(IndexType type);

    /** The predicates the text index covers; empty means every predicate. */
    Set<String> textPredicates();

    /** Whether the text index covers statements with this predicate. */
    default boolean textCovers(final String predicate) {
        final Set<String> predicates = textPredicates();
        return predicates.isEmpty() || predicates.contains(predicate);
    }

    /** The index table. */
    String table(IndexType type);
}
