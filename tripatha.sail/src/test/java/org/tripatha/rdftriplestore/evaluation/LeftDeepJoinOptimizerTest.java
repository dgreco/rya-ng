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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.tripatha.rdftriplestore.inference.InferJoin;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.ProjectionElemList;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.junit.jupiter.api.Test;

/** Which right-nested joins become left-deep, and that the order of their arguments is kept. */
public class LeftDeepJoinOptimizerTest {

    @Test
    public void rightNestedJoinsBecomeLeftDeep() {
        final TupleExpr tree = optimize(new Join(sp("a"), new Join(sp("b"), new Join(sp("c"), sp("d")))));
        assertEquals("(((a b) c) d)", shape(tree));
    }

    @Test
    public void leftDeepJoinsAreLeftAlone() {
        final TupleExpr tree = optimize(new Join(new Join(sp("a"), sp("b")), sp("c")));
        assertEquals("((a b) c)", shape(tree));
    }

    @Test
    public void joinsOnBothSidesKeepTheOrderOfTheirArguments() {
        final TupleExpr tree = optimize(new Join(new Join(sp("a"), new Join(sp("b"), sp("c"))), new Join(sp("d"), sp("e"))));
        assertEquals("((((a b) c) d) e)", shape(tree));
    }

    @Test
    public void entityChainSeesTheRotatedJoins() {
        final Join top = (Join) optimize(new Join(sp("a"), new Join(sp("b"), sp("c"))));
        final EntityChain chain = EntityChain.find(top);
        assertNotNull(chain);
        assertEquals("a", shape(chain.driver()));
        assertEquals(2, chain.links().size());
    }

    @Test
    public void mergeJoinsAreLeftAlone() {
        final Join inner = new Join(sp("b"), sp("c"));
        inner.setMergeJoin(true);
        assertEquals("(a (b c))", shape(optimize(new Join(sp("a"), inner))));

        final Join outer = new Join(sp("a"), new Join(sp("b"), sp("c")));
        outer.setMergeJoin(true);
        assertEquals("(a (b c))", shape(optimize(outer)));
    }

    @Test
    public void scopeChangesAreLeftAlone() {
        final Join inner = new Join(sp("b"), sp("c"));
        inner.setVariableScopeChange(true);
        assertEquals("(a (b c))", shape(optimize(new Join(sp("a"), inner))));

        final StatementPattern scoped = sp("c");
        scoped.setVariableScopeChange(true);
        assertEquals("(a (b c))", shape(optimize(new Join(sp("a"), new Join(sp("b"), scoped)))));
    }

    @Test
    public void subqueriesAreLeftAlone() {
        final Projection subquery = new Projection(sp("c"), new ProjectionElemList());
        subquery.setSubquery(true);
        final TupleExpr tree = optimize(new Join(sp("a"), new Join(sp("b"), subquery)));
        assertEquals("(a (b ?))", shape(tree));
    }

    @Test
    public void inferenceJoinsAreLeftAlone() {
        assertEquals("(a (b c))", shape(optimize(new Join(sp("a"), new InferJoin(sp("b"), sp("c"))))));
        assertEquals("(a (b c))", shape(optimize(new InferJoin(sp("a"), new Join(sp("b"), sp("c"))))));
    }

    private static TupleExpr optimize(final TupleExpr tree) {
        final QueryRoot root = new QueryRoot(tree);
        new LeftDeepJoinOptimizer().optimize(root, null, EmptyBindingSet.getInstance());
        return root.getArg();
    }

    private static StatementPattern sp(final String name) {
        return new StatementPattern(new Var("s"), new Var("p_" + name), new Var(name));
    }

    private static String shape(final TupleExpr expr) {
        if (expr instanceof Join join) {
            return "(" + shape(join.getLeftArg()) + " " + shape(join.getRightArg()) + ")";
        }
        if (expr instanceof StatementPattern sp) {
            return sp.getObjectVar().getName();
        }
        return "?";
    }
}
