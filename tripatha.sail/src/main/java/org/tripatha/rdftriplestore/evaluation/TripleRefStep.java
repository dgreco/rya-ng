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

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.EmptyIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.common.iteration.SingletonIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MutableBindingSet;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TripleRef;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;

/**
 * Evaluates a triple term in a pattern, {@code <<( s p o )>>}, which RDF4J parses as a
 * {@link TripleRef} joined to the pattern that uses the term through an anonymous variable.
 *
 * <p>When that variable is already bound, which {@link TripleTermOptimizer} arranges, the term
 * is taken apart in memory and its parts are matched against the pattern: no store access.
 * Otherwise the store's triple terms are enumerated ({@link StoreTripleSource#tripleTerms}).
 *
 * <p>RDF4J's own step enumerates the store in both cases, and fails with a
 * {@code ClassCastException} when the subject is bound to a literal.
 */
final class TripleRefStep implements QueryEvaluationStep {
    private final Var subjectVar;
    private final Var predicateVar;
    private final Var objectVar;
    private final Var termVar;
    private final boolean topLevelOnly;
    private final StoreTripleSource<?> source;
    private final QueryEvaluationContext context;

    TripleRefStep(final TripleRef ref, final StoreTripleSource<?> source, final QueryEvaluationContext context) {
        this.subjectVar = ref.getSubjectVar();
        this.predicateVar = ref.getPredicateVar();
        this.objectVar = ref.getObjectVar();
        this.termVar = ref.getExprVar();
        this.topLevelOnly = usedOnlyAsStatementObject(ref);
        this.source = source;
        this.context = context;
    }

    /**
     * Whether the term is the object of a statement pattern and of no other triple term. Then
     * only terms that are themselves objects of statements can match, and the enumeration need
     * not look inside other terms.
     */
    private static boolean usedOnlyAsStatementObject(final TripleRef ref) {
        final String name = ref.getExprVar().getName();
        QueryModelNode root = ref;
        while (root.getParentNode() != null) {
            root = root.getParentNode();
        }
        final boolean[] used = new boolean[2];
        root.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern node) {
                used[0] |= node.getObjectVar() != null && name.equals(node.getObjectVar().getName());
            }

            @Override
            public void meet(final TripleRef node) {
                used[1] |= name.equals(node.getObjectVar().getName());
            }
        });
        return used[0] && !used[1];
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(final BindingSet bindings) {
        final Value bound = value(termVar, bindings);
        if (bound != null) {
            final BindingSet result = bound instanceof TripleTerm ? unify((TripleTerm) bound, bindings, false) : null;
            return result == null ? new EmptyIteration<>() : new SingletonIteration<>(result);
        }
        final Value subject = value(subjectVar, bindings);
        final Value predicate = value(predicateVar, bindings);
        final Value object = value(objectVar, bindings);
        if ((subject != null && !(subject instanceof Resource)) || (predicate != null && !(predicate instanceof IRI))) {
            return new EmptyIteration<>();
        }
        final CloseableIteration<? extends TripleTerm> terms = source.tripleTerms((Resource) subject, (IRI) predicate,
                object, topLevelOnly);
        return new LookAheadIteration<>() {
            @Override
            protected BindingSet getNextElement() {
                while (terms.hasNext()) {
                    final BindingSet result = unify(terms.next(), bindings, true);
                    if (result != null) {
                        return result;
                    }
                }
                return null;
            }

            @Override
            protected void handleClose() {
                terms.close();
            }
        };
    }

    /** @return the bindings extended with the term's parts, or {@code null} if a part disagrees with what is bound. */
    private BindingSet unify(final TripleTerm term, final BindingSet bindings, final boolean bindTerm) {
        final MutableBindingSet result = context.createBindingSet(bindings);
        if (!unify(subjectVar, term.getSubject(), result) || !unify(predicateVar, term.getPredicate(), result)
                || !unify(objectVar, term.getObject(), result)) {
            return null;
        }
        if (bindTerm) {
            result.addBinding(termVar.getName(), term);
        }
        return result;
    }

    /** Reads the binding back each time, so a variable used twice in the term must match itself. */
    private static boolean unify(final Var var, final Value part, final MutableBindingSet result) {
        final Value bound = value(var, result);
        if (bound == null) {
            result.addBinding(var.getName(), part);
            return true;
        }
        return bound.equals(part);
    }

    private static Value value(final Var var, final BindingSet bindings) {
        return var.hasValue() ? var.getValue() : bindings.getValue(var.getName());
    }
}
