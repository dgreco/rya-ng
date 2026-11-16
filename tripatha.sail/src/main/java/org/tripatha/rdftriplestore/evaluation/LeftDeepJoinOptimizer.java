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
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.TupleExprs;

/**
 * Turns the right-nested joins RDF4J's join optimizer builds into left-deep ones, keeping the
 * order of their arguments:
 *
 * <pre>
 * Join                Join
 *   a                   Join
 *   Join         =>       a
 *     b                   b
 *     c                 c
 * </pre>
 *
 * <p>RDF4J evaluates both as the same nested loops, a then b then c, so results and their order
 * don't change. But in the right-nested form the inner join is evaluated once per row of
 * {@code a}, with a single row on its left: {@link BatchedJoinStep} has nothing to batch and
 * {@link EntityChain} sees no chain, so every row costs one store scan per pattern. In the
 * left-deep form each join has a statement pattern on its right and all the rows on its left.
 *
 * <p>A join is left alone when RDF4J wouldn't evaluate it as a nested loop over its left
 * bindings: a merge join, a right side that changes variable scope or holds a subquery, or a
 * join subclass such as the ones inference builds.
 *
 * <p>Runs last: RDF4J's iterative-evaluation and filter optimizers expect its own shape.
 */
public final class LeftDeepJoinOptimizer implements QueryOptimizer {

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        tupleExpr.visit(new Rotator());
    }

    private static final class Rotator extends AbstractQueryModelVisitor<RuntimeException> {
        @Override
        public void meet(final Join node) {
            while (plain(node) && node.getRightArg() instanceof Join right && plain(right) && nestedLoopOver(right)) {
                final Join left = new Join(node.getLeftArg(), right.getLeftArg());
                node.setLeftArg(left);
                node.setRightArg(right.getRightArg());
            }
            super.meet(node);
        }

        /** Whether this is RDF4J's own join, evaluated left to right. */
        private static boolean plain(final Join join) {
            return join.getClass() == Join.class && !join.isMergeJoin();
        }

        /** Whether RDF4J evaluates this right side, and its own right side, with the bindings of the rows to their left. */
        private static boolean nestedLoopOver(final Join right) {
            final TupleExpr innermost = right.getRightArg();
            return !TupleExprs.isVariableScopeChange(right) && !TupleExprs.isVariableScopeChange(innermost)
                    && !TupleExprs.containsSubquery(innermost);
        }
    }
}
