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
package io.github.dgreco.rya.rdftriplestore.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;

import io.github.dgreco.rya.rdftriplestore.utils.FixedStatementPattern;

/**
 * Answers {@code stats:approxSize} patterns from the statistics counters instead of the store.
 * <p>
 * {@code ?graph stats:approxSize ?n} matches one statement per named graph with data, whose
 * object is the graph's approximate number of statements. The pattern is replaced with a
 * {@link FixedStatementPattern} holding those statements, so it costs a few counter reads where
 * {@code COUNT(*)} over the graph would read every statement. Bound subjects or objects, and the
 * rest of the query, work as for any pattern.
 */
public class GraphSizeOptimizer implements QueryOptimizer {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final Supplier<Map<String, Long>> sizes;

    /**
     * @param sizes the graph sizes for the query's authorizations, read only if the query asks
     *        for them; null when the store keeps no statistics
     */
    public GraphSizeOptimizer(final Supplier<Map<String, Long>> sizes) {
        this.sizes = sizes;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        final List<StatementPattern> targets = new ArrayList<>();
        tupleExpr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern sp) {
                final Var p = sp.getPredicateVar();
                if (p != null && p.hasValue() && RyaStats.APPROX_SIZE.equals(p.getValue())) {
                    targets.add(sp);
                }
            }
        });
        if (targets.isEmpty()) {
            return;
        }
        if (sizes == null) {
            throw new QueryEvaluationException(RyaStats.APPROX_SIZE
                    + " needs the statistics, and this store was opened without them (rya.stats on the endpoint)");
        }
        final List<Statement> statements = new ArrayList<>();
        for (final Map.Entry<String, Long> e : sizes.get().entrySet()) {
            statements.add(VF.createStatement(VF.createIRI(e.getKey()), RyaStats.APPROX_SIZE, VF.createLiteral(e.getValue())));
        }
        for (final StatementPattern sp : targets) {
            final FixedStatementPattern fixed = new FixedStatementPattern(sp.getScope(), clone(sp.getSubjectVar()),
                    clone(sp.getPredicateVar()), clone(sp.getObjectVar()), clone(sp.getContextVar()));
            fixed.statements.addAll(statements);
            sp.replaceWith(fixed);
        }
    }

    /** A Var may belong to one parent only, so the new node gets copies. */
    private static Var clone(final Var var) {
        return var == null ? null : var.clone();
    }
}
