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
package io.github.dgreco.rya.rdftriplestore.evaluation.index;

import java.util.Objects;

import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;

import io.github.dgreco.rya.accumulo.index.IndexQuery;

/**
 * A statement pattern whose object is filtered by a text or geo function, so its matches can be
 * looked up in a secondary index instead of scanned. It matches the same statements as the
 * pattern it replaces would once the filter is applied: the filter stays in the query and makes
 * the exact check.
 */
public class IndexedStatementPattern extends StatementPattern {
    /** How much an index lookup is assumed to narrow a pattern down, for join ordering. */
    public static final double SELECTIVITY = 0.001;

    private final IndexQuery indexQuery;

    public IndexedStatementPattern(final StatementPattern original, final IndexQuery indexQuery) {
        super(original.getScope(), clone(original.getSubjectVar()), clone(original.getPredicateVar()),
                clone(original.getObjectVar()), clone(original.getContextVar()));
        this.indexQuery = indexQuery;
    }

    private static Var clone(final Var var) {
        return var == null ? null : var.clone();
    }

    public IndexQuery getIndexQuery() {
        return indexQuery;
    }

    @Override
    public IndexedStatementPattern clone() {
        return (IndexedStatementPattern) super.clone();
    }

    @Override
    public String getSignature() {
        return super.getSignature() + " [" + indexQuery.type().id() + " index]";
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof IndexedStatementPattern o && super.equals(o) && indexQuery.equals(o.indexQuery);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), indexQuery);
    }
}
