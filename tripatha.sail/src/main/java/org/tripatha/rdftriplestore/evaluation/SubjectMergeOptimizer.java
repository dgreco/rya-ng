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
import org.eclipse.rdf4j.query.algebra.Difference;
import org.eclipse.rdf4j.query.algebra.Distinct;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Group;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.MultiProjection;
import org.eclipse.rdf4j.query.algebra.Order;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.Reduced;
import org.eclipse.rdf4j.query.algebra.Slice;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
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
 * <p><b>A pattern on its own</b> (SCALE-STUDY.md O7a): a star pattern that isn't merged with
 * another, but is evaluated once per query, becomes a merge of one list when its {@code po} counter
 * is at least {@code query.join.mergesingle}: {@code ?x a :C} alone, under a count, a filter or a
 * {@code DISTINCT}, or as the driver of a chain whose other patterns are looked up. It is then read
 * as subjects only, in slices on threads of their own, instead of as statements decoded on the
 * query thread; its rows are the same, a subject with several statements giving several. Evaluated
 * once means that only unary operators, unions and the left sides of joins, OPTIONALs and MINUS
 * lie between it and the query's root: on a join's right side it would be evaluated per row. Not
 * under a {@code LIMIT} shorter than that length, which one scan answers sooner.
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
    private final boolean everyStarPattern;

    /**
     * @param statistics the counters; nothing is merged unless they are the store's
     * @param conf the query's configuration
     * @param allowed false when the query is evaluated with RDF4J's result-size or time tracking
     */
    public SubjectMergeOptimizer(final EvaluationStatistics statistics, final RdfCloudTripleStoreConfiguration conf,
            final boolean allowed) {
        this(statistics, conf, allowed, false);
    }

    /**
     * @param everyStarPattern merge every star pattern on the subject, whatever its length: the
     *        servers intersect the entity index's shards, seeking past what can't match, so a
     *        long list costs little more than a short one (SCALE-STUDY.md O23)
     */
    public SubjectMergeOptimizer(final EvaluationStatistics statistics, final RdfCloudTripleStoreConfiguration conf,
            final boolean allowed, final boolean everyStarPattern) {
        this.statistics = statistics;
        this.conf = conf;
        this.allowed = allowed;
        this.everyStarPattern = everyStarPattern;
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
        final long single = conf.getJoinMergeSingle();
        if (single > 0) {
            mergeAlone(tupleExpr, single);
        }
    }

    /** Makes each long star pattern evaluated once per query a merge of one list. */
    private void mergeAlone(final TupleExpr tupleExpr, final long single) {
        final List<StatementPattern> alone = new ArrayList<>();
        tupleExpr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern node) {
                // A pattern with a scope of its own (a UNION's arm, a group) passes it on to the merge.
                if (isStarShape(node) && evaluatedOnce(node, single) && statistics.getCardinality(node) >= single) {
                    alone.add(node);
                }
            }
        });
        for (final StatementPattern sp : alone) {
            final QueryModelNode parent = sp.getParentNode();
            final SubjectMergeJoin merge = new SubjectMergeJoin(sp.getSubjectVar().getName(), List.of(sp));
            merge.setVariableScopeChange(sp.isVariableScopeChange());
            parent.replaceChildNode(sp, merge);
        }
    }

    /**
     * Whether the pattern is evaluated once per evaluation of the query: only unary operators,
     * unions and the left sides of joins, OPTIONALs and MINUS lead from it to the root, and no
     * {@code LIMIT} shorter than {@code single} is among them.
     */
    private static boolean evaluatedOnce(final StatementPattern sp, final long single) {
        QueryModelNode child = sp;
        QueryModelNode parent = sp.getParentNode();
        while (parent != null && !(parent instanceof QueryRoot)) {
            final Class<?> type = parent.getClass();
            if (parent instanceof Slice slice && slice.hasLimit() && Math.max(0, slice.getOffset()) + slice.getLimit() < single) {
                return false;
            }
            final boolean once = type == Filter.class || type == Extension.class || type == Projection.class
                    || type == MultiProjection.class || type == Distinct.class || type == Reduced.class
                    || type == Slice.class || type == Order.class || type == Group.class || type == Union.class
                    || type == Join.class && ((Join) parent).getLeftArg() == child && !((Join) parent).isMergeJoin()
                    || type == LeftJoin.class && ((LeftJoin) parent).getLeftArg() == child
                    || type == Difference.class && ((Difference) parent).getLeftArg() == child;
            if (!once) {
                return false;
            }
            child = parent;
            parent = parent.getParentNode();
        }
        return true;
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
        final double limit = everyStarPattern ? Double.POSITIVE_INFINITY : ratio * Math.max(1, shortest);
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
        return isStarShape(sp) && !TupleExprs.isVariableScopeChange(sp);
    }

    /** A star pattern, maybe with a scope of its own. */
    private static boolean isStarShape(final StatementPattern sp) {
        if (sp.getClass() != StatementPattern.class || sp.getScope() != StatementPattern.Scope.DEFAULT_CONTEXTS
                || sp.getContextVar() != null) {
            return false;
        }
        final Var s = sp.getSubjectVar();
        final Var p = sp.getPredicateVar();
        final Var o = sp.getObjectVar();
        return s != null && !s.hasValue() && p != null && p.getValue() instanceof IRI && o != null
                && (o.getValue() instanceof IRI || o.getValue() instanceof Literal);
    }
}
