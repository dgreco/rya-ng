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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment;
import org.eclipse.rdf4j.query.algebra.EmptySet;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.TripleRef;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.ListBindingSet;

/**
 * Keeps triple terms in patterns away from the store. RDF4J parses {@code <<( s p o )>>} as a
 * {@link TripleRef} that binds an anonymous variable, joined to the pattern that uses it; left
 * alone, the reference may be evaluated first, which enumerates every triple term stored.
 *
 * <ul>
 *   <li>{@link #fold}, before join ordering: a term whose three parts are constants becomes a
 *       constant row, so the pattern that uses it is an exact lookup.</li>
 *   <li>{@link #order}, after join ordering: each remaining reference is moved to just after
 *       the pattern that binds its variable, where {@link TripleRefStep} takes the bound term
 *       apart in memory.</li>
 * </ul>
 * Both return at once for a query without triple terms.
 */
public final class TripleTermOptimizer implements QueryOptimizer {
    private final ValueFactory valueFactory;
    private final boolean folding;

    private TripleTermOptimizer(final ValueFactory valueFactory, final boolean folding) {
        this.valueFactory = valueFactory;
        this.folding = folding;
    }

    /** Turns triple terms made of constants into constants. */
    public static TripleTermOptimizer fold(final ValueFactory valueFactory) {
        return new TripleTermOptimizer(valueFactory, true);
    }

    /** Moves each triple term reference to after the pattern that binds its variable. */
    public static TripleTermOptimizer order() {
        return new TripleTermOptimizer(null, false);
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (references(tupleExpr).isEmpty()) {
            return;
        }
        if (folding) {
            // Folding an inner term can make the term around it constant too.
            final Map<String, Value> folded = new HashMap<>();
            while (foldOne(tupleExpr, folded)) {
                continue;
            }
        } else {
            tupleExpr.visit(new JoinOrderer());
        }
    }

    private static List<TripleRef> references(final TupleExpr tupleExpr) {
        final List<TripleRef> refs = new ArrayList<>();
        tupleExpr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final TripleRef node) {
                refs.add(node);
            }
        });
        return refs;
    }

    private boolean foldOne(final TupleExpr tupleExpr, final Map<String, Value> folded) {
        for (final TripleRef ref : references(tupleExpr)) {
            final Value s = constant(ref.getSubjectVar(), folded);
            final Value p = constant(ref.getPredicateVar(), folded);
            final Value o = constant(ref.getObjectVar(), folded);
            final Var term = ref.getExprVar();
            if (s == null || p == null || o == null || term.hasValue()) {
                continue;
            }
            if (!(s instanceof Resource) || !(p instanceof IRI)) {
                // Not a triple term at all, such as one with a literal subject: it matches nothing.
                ref.replaceWith(new EmptySet());
                return true;
            }
            final TripleTerm value = valueFactory.createTripleTerm((Resource) s, (IRI) p, o);
            final String name = term.getName();
            // A row that binds the variable, not a constant in the patterns that use it: RDF4J's
            // statement pattern step shares a constant triple term's node between two parents,
            // which its own assertion forbids. Join ordering puts the row first, so the pattern
            // is still an exact lookup; and an update's templates read the variable from it.
            final BindingSetAssignment assignment = new BindingSetAssignment();
            assignment.setBindingNames(Set.of(name));
            assignment.setBindingSets(List.of(new ListBindingSet(List.of(name), value)));
            ref.replaceWith(assignment);
            folded.put(name, value);
            return true;
        }
        return false;
    }

    private static Value constant(final Var var, final Map<String, Value> folded) {
        return var.hasValue() ? var.getValue() : folded.get(var.getName());
    }

    /** Reorders the arguments of each tree of joins that holds a triple term reference. */
    private static final class JoinOrderer extends AbstractQueryModelVisitor<RuntimeException> {
        @Override
        public void meet(final Join node) {
            final List<TupleExpr> args = new ArrayList<>();
            flatten(node, args);
            final List<TupleExpr> ordered = order(args);
            if (!sameInstances(ordered, args)) {
                TupleExpr tree = ordered.get(0);
                final QueryModelNode parent = node.getParentNode();
                for (int i = 1; i < ordered.size(); i++) {
                    tree = new Join(tree, ordered.get(i));
                }
                parent.replaceChildNode(node, tree);
            }
            for (final TupleExpr arg : args) {
                arg.visit(this);
            }
        }

        private static boolean sameInstances(final List<TupleExpr> a, final List<TupleExpr> b) {
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i) != b.get(i)) {
                    return false;
                }
            }
            return true;
        }

        private static void flatten(final TupleExpr expr, final List<TupleExpr> args) {
            if (expr instanceof Join join && (args.isEmpty() || !join.isVariableScopeChange())) {
                flatten(join.getLeftArg(), args);
                flatten(join.getRightArg(), args);
            } else {
                args.add(expr);
            }
        }

        /**
         * Keeps the other arguments in the order join ordering chose, and puts each reference
         * directly after the first argument that binds its variable: a pattern, or the reference
         * of the term it is nested in. A reference nothing binds stays first: it has to
         * enumerate, and others may depend on it.
         */
        private static List<TupleExpr> order(final List<TupleExpr> args) {
            final List<TupleExpr> placed = new ArrayList<>();
            final List<TripleRef> pending = new ArrayList<>();
            for (final TupleExpr arg : args) {
                if (arg instanceof TripleRef ref) {
                    pending.add(ref);
                } else {
                    placed.add(arg);
                }
            }
            if (pending.isEmpty()) {
                return args;
            }
            boolean progress = true;
            while (progress && !pending.isEmpty()) {
                progress = false;
                for (final TripleRef ref : List.copyOf(pending)) {
                    final String name = ref.getExprVar().getName();
                    for (int i = 0; i < placed.size(); i++) {
                        if (placed.get(i).getAssuredBindingNames().contains(name)) {
                            placed.add(i + 1, ref);
                            pending.remove(ref);
                            progress = true;
                            break;
                        }
                    }
                }
            }
            final List<TupleExpr> ordered = new ArrayList<>(pending);
            ordered.addAll(placed);
            return ordered;
        }
    }
}
