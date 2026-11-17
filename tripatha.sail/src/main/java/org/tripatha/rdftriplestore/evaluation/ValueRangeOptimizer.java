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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.Compare;
import org.eclipse.rdf4j.query.algebra.Compare.CompareOp;
import org.eclipse.rdf4j.query.algebra.Difference;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.TupleExprs;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.domain.RyaValueRange;

/**
 * Replaces a pattern {@code ?s p ?o} whose object a filter compares with constants by a
 * {@link ValueRangePattern}, which reads only the ranges of values the comparisons allow
 * (SCALE-STUDY.md O14): {@code ?obs :hasResult ?v FILTER(?v > 60)} reads the results above 60
 * (widened a little), not every result.
 *
 * <p><b>Which comparisons:</b> the conjuncts {@code <}, {@code <=}, {@code >}, {@code >=} and
 * {@code =} between the pattern's object and a constant number or {@code xsd:dateTime}, in a
 * chain of filters. The filter applies to every row of the pattern: it is reached from the
 * pattern through joins, unions, the left side of an OPTIONAL or a MINUS, and extensions that
 * don't bind the variable, as with {@code IndexSearchOptimizer}.
 *
 * <p><b>Which patterns:</b> a plain statement pattern (not one inference or an index built) with
 * a variable subject, an IRI predicate and the compared variable as object, used nowhere else in
 * it.
 *
 * <p><b>When:</b> the predicate's datatypes are read from the store's datatype markers, under the
 * query's labels; there must be markers (a store created with them), and every datatype must
 * sort like its values ({@link ValueRanges}). Otherwise the pattern is left as it is. Nothing is
 * rewritten with {@code query.valueranges} off, a result limit, the hashed row layout, or row
 * regular expressions.
 *
 * <p>The filter stays and decides every row, so answers don't change. Runs after inference and
 * the index search, before join ordering, which then sees the narrowed estimate.
 */
public final class ValueRangeOptimizer implements QueryOptimizer {
    private final RdfCloudTripleStoreConfiguration conf;
    private final Function<IRI, Optional<Set<String>>> datatypes;
    /** The datatypes of each predicate, read once per query. */
    private final Map<IRI, Optional<Set<String>>> read = new HashMap<>();

    /**
     * @param conf the query's configuration
     * @param datatypes the datatypes of a predicate's objects, as the markers record them under
     *        the query's labels; empty when the store has no complete markers
     */
    public ValueRangeOptimizer(final RdfCloudTripleStoreConfiguration conf, final Function<IRI, Optional<Set<String>>> datatypes) {
        this.conf = conf;
        this.datatypes = datatypes;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (conf == null || datatypes == null || !conf.isValueRanges() || conf.getLimit() != null
                || Boolean.TRUE.equals(conf.isPrefixRowsWithHash()) || conf.getRegexSubject() != null
                || conf.getRegexPredicate() != null || conf.getRegexObject() != null) {
            return;
        }
        tupleExpr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Filter filter) {
                // A chain of filters (from FILTER(a && b) once split) is handled once, from its top.
                if (!(filter.getParentNode() instanceof Filter)) {
                    rewrite(filter);
                }
                super.meet(filter);
            }
        });
    }

    private void rewrite(final Filter top) {
        final Map<String, ValueRanges.Bounds> byVariable = new LinkedHashMap<>();
        final Map<String, List<String>> conditions = new LinkedHashMap<>();
        TupleExpr below = top;
        while (below instanceof Filter f) {
            collect(f.getCondition(), byVariable, conditions);
            below = f.getArg();
        }
        for (final Map.Entry<String, ValueRanges.Bounds> e : byVariable.entrySet()) {
            if (!e.getValue().bounded()) {
                continue;
            }
            final List<StatementPattern> targets = new ArrayList<>();
            findTargets(below, e.getKey(), targets);
            for (final StatementPattern sp : targets) {
                if (!accepts(sp, e.getKey())) {
                    continue;
                }
                final IRI predicate = (IRI) sp.getPredicateVar().getValue();
                final Optional<Set<String>> types = read.computeIfAbsent(predicate, datatypes);
                if (types.isEmpty()) {
                    continue;
                }
                final List<RyaValueRange> ranges = e.getValue().ranges(types.get());
                if (ranges != null) {
                    sp.replaceWith(new ValueRangePattern(sp, ranges, String.join(", ", conditions.get(e.getKey()))));
                }
            }
        }
    }

    /** Adds the comparisons of a variable with a constant among the condition's conjuncts. */
    private static void collect(final ValueExpr condition, final Map<String, ValueRanges.Bounds> byVariable,
            final Map<String, List<String>> conditions) {
        if (condition instanceof And and) {
            collect(and.getLeftArg(), byVariable, conditions);
            collect(and.getRightArg(), byVariable, conditions);
            return;
        }
        if (!(condition instanceof Compare compare)) {
            return;
        }
        String var = variable(compare.getLeftArg());
        Value constant = constant(compare.getRightArg());
        CompareOp op = compare.getOperator();
        if (var == null || constant == null) {
            var = variable(compare.getRightArg());
            constant = constant(compare.getLeftArg());
            op = flip(op);
        }
        if (var == null || constant == null) {
            return;
        }
        byVariable.computeIfAbsent(var, k -> new ValueRanges.Bounds()).add(op, constant);
        conditions.computeIfAbsent(var, k -> new ArrayList<>())
                .add("?" + var + " " + op.getSymbol() + " " + (constant instanceof Literal l ? l.getLabel() : constant.stringValue()));
    }

    private static String variable(final ValueExpr expr) {
        return expr instanceof Var v && !v.hasValue() ? v.getName() : null;
    }

    private static Value constant(final ValueExpr expr) {
        if (expr instanceof ValueConstant c) {
            return c.getValue();
        }
        return expr instanceof Var v && v.hasValue() ? v.getValue() : null;
    }

    /** The operator with its arguments swapped: {@code c < ?v} is {@code ?v > c}. */
    private static CompareOp flip(final CompareOp op) {
        return switch (op) {
            case LT -> CompareOp.GT;
            case LE -> CompareOp.GE;
            case GT -> CompareOp.LT;
            case GE -> CompareOp.LE;
            default -> op;
        };
    }

    /** The plain statement patterns below a filter whose every row the filter applies to. */
    private static void findTargets(final TupleExpr node, final String variable, final List<StatementPattern> out) {
        if (node.getClass() == StatementPattern.class) {
            out.add((StatementPattern) node);
            return;
        }
        if (TupleExprs.isVariableScopeChange(node)) {
            return;
        }
        if (node instanceof Join join) {
            findTargets(join.getLeftArg(), variable, out);
            findTargets(join.getRightArg(), variable, out);
        } else if (node instanceof Union union) {
            findTargets(union.getLeftArg(), variable, out);
            findTargets(union.getRightArg(), variable, out);
        } else if (node instanceof Filter filter) {
            findTargets(filter.getArg(), variable, out);
        } else if (node instanceof LeftJoin leftJoin) {
            findTargets(leftJoin.getLeftArg(), variable, out);
        } else if (node instanceof Difference difference) {
            findTargets(difference.getLeftArg(), variable, out);
        } else if (node instanceof Extension extension) {
            for (final ExtensionElem elem : extension.getElements()) {
                if (elem.getName().equals(variable)) {
                    return;
                }
            }
            findTargets(extension.getArg(), variable, out);
        }
    }

    /** {@code ?s <p> ?var}, with the variable nowhere else in the pattern. */
    private static boolean accepts(final StatementPattern sp, final String variable) {
        final Var s = sp.getSubjectVar();
        final Var p = sp.getPredicateVar();
        final Var o = sp.getObjectVar();
        final Var c = sp.getContextVar();
        return o != null && !o.hasValue() && o.getName().equals(variable) && s != null && !s.hasValue()
                && !s.getName().equals(variable) && p != null && p.getValue() instanceof IRI
                && (c == null || c.hasValue() || !c.getName().equals(variable)) && !TupleExprs.isVariableScopeChange(sp);
    }
}
