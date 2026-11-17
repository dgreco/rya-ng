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
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.FN;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;

/**
 * The store, for a statement pattern whose object a {@code STRSTARTS} filter constrains, as in
 * {@code ?p :sku ?s FILTER(STRSTARTS(?s, "SKU-AB"))}. The pattern alone would read every
 * {@code :sku} statement, for the filter to discard all but a few. When neither end of the
 * pattern is bound, this reads only the statements whose object's data starts with the prefix.
 * Otherwise the lookup is already narrow, and goes to the store as it is.
 *
 * <p>It only leaves out statements the filter would reject, so results don't change: the filter
 * stays in the query and decides every row. {@code STRSTARTS(?o, "abc")} is true only for a
 * string or language-tagged string that starts with {@code abc}, and both are stored as their
 * text (a language-tagged one as {@code text@lang}). Every other value, numbers, dates, IRIs or
 * triple terms, makes it an error, which a filter treats as false.
 *
 * <p>{@code STRSTARTS(STR(?o), …)} is left alone: {@code STR} of the number 42 is {@code "42"},
 * but numbers are stored in an encoding of their own, outside that range.
 */
final class ObjectPrefixSource implements TripleSource {
    private final StoreTripleSource<?> store;
    private final String prefix;

    ObjectPrefixSource(final StoreTripleSource<?> store, final String prefix) {
        this.store = store;
        this.prefix = prefix;
    }

    /**
     * @return the prefix a {@code STRSTARTS} filter requires of this pattern's object, or
     *         {@code null}. The filter must be above the pattern through joins and filters only,
     *         so that it applies to every row the pattern contributes to; the pattern's subject
     *         and object must be variables, and its predicate a variable or an IRI.
     */
    static String prefixFor(final StatementPattern pattern) {
        final Var object = pattern.getObjectVar();
        final Var subject = pattern.getSubjectVar();
        final Var predicate = pattern.getPredicateVar();
        if (object == null || object.hasValue() || subject == null || subject.hasValue()
                || (predicate != null && predicate.hasValue() && !(predicate.getValue() instanceof IRI))) {
            return null;
        }
        QueryModelNode child = pattern;
        QueryModelNode parent = pattern.getParentNode();
        while (parent instanceof Join || parent instanceof Filter) {
            if (parent instanceof Filter filter && filter.getArg() == child) {
                final String found = prefixIn(filter.getCondition(), object.getName());
                if (found != null) {
                    return found;
                }
            }
            child = parent;
            parent = parent.getParentNode();
        }
        return null;
    }

    /** The prefix of a {@code STRSTARTS(?var, "constant")} that must hold for the condition to hold, or null. */
    private static String prefixIn(final ValueExpr condition, final String var) {
        if (condition instanceof And and) {
            final String left = prefixIn(and.getLeftArg(), var);
            return left != null ? left : prefixIn(and.getRightArg(), var);
        }
        if (!(condition instanceof FunctionCall call) || !FN.STARTS_WITH.stringValue().equals(call.getURI())
                || call.getArgs().size() != 2) {
            return null;
        }
        if (!(call.getArgs().get(0) instanceof Var arg) || arg.hasValue() || !arg.getName().equals(var)) {
            return null;
        }
        final Value constant = call.getArgs().get(1) instanceof ValueConstant c ? c.getValue()
                : call.getArgs().get(1) instanceof Var v && v.hasValue() ? v.getValue() : null;
        if (!(constant instanceof Literal literal)) {
            return null;
        }
        final String text = literal.getLabel();
        // An empty prefix narrows nothing; U+0000 and U+0001 are never stored, so nothing would match.
        return text.isEmpty() || text.indexOf('\u0000') >= 0 || text.indexOf('\u0001') >= 0 ? null : text;
    }

    @Override
    public CloseableIteration<? extends Statement> getStatements(final Resource subject, final IRI predicate,
            final Value object, final Resource... contexts) throws QueryEvaluationException {
        if (subject != null || object != null) {
            return store.getStatements(subject, predicate, object, contexts);
        }
        return store.getStatementsWithObjectPrefix(predicate, prefix, contexts);
    }

    @Override
    public ValueFactory getValueFactory() {
        return store.getValueFactory();
    }
}
