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
package org.tripatha.rdftriplestore.evaluation;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;

/**
 * A statement pattern whose object variable is used nowhere else in its query (SCALE-STUDY.md
 * O26), such as {@code ?f} in {@code SELECT ?p WHERE { ?p a :Person ; :knows ?f }}: the pattern
 * only decides how many times each row comes back. So its lookups ask the store how many
 * statements match instead of reading them, and each row is repeated that many times
 * ({@link CountedPatternStep}). {@link CountedPatternOptimizer} marks them; anything else treats
 * them as the statement patterns they are.
 */
public final class CountedPattern extends StatementPattern {
    CountedPattern(final StatementPattern sp) {
        super(sp.getScope(), sp.getSubjectVar().clone(), sp.getPredicateVar().clone(), sp.getObjectVar().clone(),
                sp.getContextVar() == null ? null : sp.getContextVar().clone());
    }

    /**
     * The lookup a row would make, to be counted; null when the row must be looked up instead.
     * Only the object may be free: the subject, the predicate and any graph must be bound,
     * and the default graph alone (a lookup the store can't narrow to it) is looked up.
     */
    static LookupKey countKey(final StatementPattern sp, final BindingSet row) {
        if (bound(sp.getObjectVar(), row) || !bound(sp.getSubjectVar(), row) || !bound(sp.getPredicateVar(), row)
                || (sp.getContextVar() != null && !bound(sp.getContextVar(), row))) {
            return null;
        }
        final LookupKey key = BatchedJoinStep.lookupFor(sp, row);
        if (key == null || key.subject() == null || key.predicate() == null || key.object() != null
                || key.contexts().size() == 1 && key.contexts().get(0) == null) {
            return null;
        }
        return key;
    }

    private static boolean bound(final Var var, final BindingSet row) {
        return var.hasValue() || row.hasBinding(var.getName());
    }
}
