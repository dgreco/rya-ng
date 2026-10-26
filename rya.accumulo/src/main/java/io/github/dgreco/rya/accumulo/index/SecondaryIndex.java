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

import java.util.Collection;
import java.util.List;

import org.apache.accumulo.core.data.Mutation;

import io.github.dgreco.rya.api.domain.RyaStatement;

/**
 * A secondary index: which terms (words, or geohash cells) a statement is filed under.
 * {@link #terms} must depend only on what is stored in spo, so the deletes computed from a stored
 * statement always hit the entries its insert wrote.
 */
public interface SecondaryIndex {
    IndexType type();

    /** Describes the indexing rules; stored with the index, and a change requires a rebuild. */
    String definition();

    /** The terms a statement is indexed under; empty when the index doesn't cover it. */
    Collection<String> terms(RyaStatement statement);

    default List<Mutation> puts(final RyaStatement statement) {
        return IndexKeys.mutations(terms(statement), statement, false);
    }

    default List<Mutation> deletes(final RyaStatement statement) {
        return IndexKeys.mutations(terms(statement), statement, true);
    }
}
