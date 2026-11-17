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
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.AbstractQueryModelNode;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.TupleExprs;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;

/**
 * Replaces the constant patterns of a star with a {@link SubjectMergeJoin}, when reading their
 * subject lists in order costs less than looking each one up per row (SCALE-STUDY.md O22).
 *
 * <p>A star pattern is {@code ?x p o}: a plain statement pattern in the default scope, with a
 * variable subject, a constant IRI predicate and a constant object. In a chain of joins whose
 * first argument, the driver that join ordering chose, is a star pattern on {@code ?x}, the star
 * patterns on {@code ?x} are candidates. Each one's length is its {@code po} counter. A candidate
 * is merged if its length is at most {@code query.join.mergeratio} times the shortest one's,
 * that is, if reading it costs less than looking up the shortest list's subjects in it.
 * The merge node becomes the chain's first argument, and the other arguments follow in their
 * order: patterns left out (a whole class, say) are then looked up for the merge's rows only.
 *
 * <p>Inner joins can be reordered freely, so the answers don't change. The chain is walked down
 * plain joins only: a filter, an OPTIONAL or a join with a scope of its own ends it. Nothing is
 * rewritten without counters (they decide), with a dataset, with a result limit or with the
 * hashed row layout, whose {@code po} rows are not sorted by subject within a predicate and object.
 *
 * <p>Runs after {@link LeftDeepJoinOptimizer}, so that the merge is the driver of the batched
 * joins and entity chains above it.
 */
public final class SubjectMergeOptimizer implements QueryOptimizer {
    private final EvaluationStatistics statistics;
    private final RdfCloudTripleStoreConfiguration conf;
    private final boolean allowed;

    /**
     * @param statistics the counters; nothing is merged unless they are the store's
     * @param conf the query's configuration
     * @param allowed false when the query is evaluated with RDF4J's result-size or time tracking
     */
    public SubjectMergeOptimizer(final EvaluationStatistics statistics, final RdfCloudTripleStoreConfiguration conf,
            final boolean allowed) {
        this.statistics = statistics;
        this.conf = conf;
        this.allowed = allowed;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (!allowed || conf == null || dataset != null || conf.getJoinMergeRatio() <= 0 || conf.getLimit() != null
                || Boolean.TRUE.equals(conf.isPrefixRowsWithHash())
                || !(statistics instanceof RdfCloudTripleStoreEvaluationStatistics<?>)) {
            return;
        }
        final List<Join> tops = new ArrayList<>();
        tupleExpr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Join node) {
                if (plain(node) && !(node.getParentNode() instanceof Join parent && plain(parent)
                        && parent.getLeftArg() == node && !node.isVariableScopeChange())) {
                    tops.add(node);
                }
                super.meet(node);
            }
        });
        for (final Join top : tops) {
            rewrite(top, conf.getJoinMergeRatio());
        }
    }

    private void rewrite(final Join top, final double ratio) {
        final List<TupleExpr> args = chain(top);
        if (!(args.get(0) instanceof StatementPattern driver) || !isStarPattern(driver)) {
            return;
        }
        final String subject = driver.getSubjectVar().getName();
        final Map<StatementPattern, Double> lengths = new IdentityHashMap<>();
        double shortest = Double.MAX_VALUE;
        for (final TupleExpr arg : args) {
            if (arg instanceof StatementPattern sp && isStarPattern(sp) && sp.getSubjectVar().getName().equals(subject)) {
                final double length = statistics.getCardinality(sp);
                lengths.put(sp, length);
                shortest = Math.min(shortest, length);
            }
        }
        final double limit = ratio * Math.max(1, shortest);
        final List<StatementPattern> merged = new ArrayList<>();
        final List<TupleExpr> rest = new ArrayList<>();
        for (final TupleExpr arg : args) {
            final Double length = arg instanceof StatementPattern sp ? lengths.get(sp) : null;
            if (length != null && length <= limit) {
                merged.add((StatementPattern) arg);
            } else {
                rest.add(arg);
            }
        }
        if (merged.size() < 2) {
            return;
        }
        merged.sort(Comparator.comparingDouble(lengths::get));
        AbstractQueryModelNode tree = new SubjectMergeJoin(subject, merged);
        for (final TupleExpr arg : rest) {
            tree = new Join((TupleExpr) tree, arg);
        }
        // A group with a scope of its own is evaluated once and joined with the rows around it:
        // the new top keeps that, or the merge would run again for every outer row.
        tree.setVariableScopeChange(top.isVariableScopeChange());
        top.replaceWith(tree);
    }

    /** The arguments of a left-deep chain of plain joins, first to last. */
    private static List<TupleExpr> chain(final Join top) {
        final List<TupleExpr> args = new ArrayList<>();
        TupleExpr node = top;
        while (node instanceof Join join && plain(join) && (join == top || !join.isVariableScopeChange())) {
            args.add(0, join.getRightArg());
            node = join.getLeftArg();
        }
        args.add(0, node);
        return args;
    }

    /** RDF4J's own join, evaluated as a nested loop. */
    private static boolean plain(final Join join) {
        return join.getClass() == Join.class && !join.isMergeJoin();
    }

    /**
     * {@code ?x p o} in the default scope: a plain pattern (not one inference or an index built),
     * a variable subject, a constant IRI predicate and a constant IRI or literal object.
     */
    static boolean isStarPattern(final StatementPattern sp) {
        if (sp.getClass() != StatementPattern.class || sp.getScope() != StatementPattern.Scope.DEFAULT_CONTEXTS
                || sp.getContextVar() != null || TupleExprs.isVariableScopeChange(sp)) {
            return false;
        }
        final Var s = sp.getSubjectVar();
        final Var p = sp.getPredicateVar();
        final Var o = sp.getObjectVar();
        return s != null && !s.hasValue() && p != null && p.getValue() instanceof IRI && o != null
                && (o.getValue() instanceof IRI || o.getValue() instanceof Literal);
    }
}
