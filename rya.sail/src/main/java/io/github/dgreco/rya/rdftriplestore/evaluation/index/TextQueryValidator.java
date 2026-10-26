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

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;

import io.github.dgreco.rya.accumulo.index.TextIndex;
import io.github.dgreco.rya.accumulo.index.text.TextQueryException;
import io.github.dgreco.rya.rdftriplestore.function.RyaText;
import io.github.dgreco.rya.rdftriplestore.function.TextMatchesFunction;

/**
 * Rejects a {@code text:matches} call whose query is a constant that can't be parsed, before
 * evaluation. It always runs, so a bad query fails the same way whether or not an index is used.
 */
public class TextQueryValidator implements QueryOptimizer {

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        tupleExpr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final FunctionCall call) {
                if (RyaText.MATCHES.stringValue().equals(call.getURI())) {
                    check(call);
                }
                super.meet(call);
            }
        });
    }

    private static void check(final FunctionCall call) {
        if (call.getArgs().size() != 2) {
            throw new MalformedQueryException("text:matches takes 2 arguments, got " + call.getArgs().size());
        }
        final Value query = constant(call.getArgs().get(1));
        if (query == null) {
            return; // computed at evaluation time
        }
        if (!(query instanceof Literal literal) || !TextIndex.isText(literal.getDatatype())) {
            throw new MalformedQueryException("text:matches: the query must be a string, got " + query);
        }
        try {
            TextMatchesFunction.parse(literal.getLabel());
        } catch (final TextQueryException e) {
            throw new MalformedQueryException(e.getMessage());
        }
    }

    /** The value of a constant argument, or null if it is computed per row. */
    static Value constant(final ValueExpr expr) {
        if (expr instanceof ValueConstant c) {
            return c.getValue();
        }
        if (expr instanceof Var v && v.hasValue()) {
            return v.getValue();
        }
        return null;
    }
}
