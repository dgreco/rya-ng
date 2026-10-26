package io.github.dgreco.rya.rdftriplestore.evaluation;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import io.github.dgreco.rya.rdftriplestore.evaluation.index.IndexTripleSource;
import io.github.dgreco.rya.rdftriplestore.evaluation.index.IndexedStatementPattern;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceEngine;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceEngineException;
import io.github.dgreco.rya.rdftriplestore.utils.FixedStatementPattern;
import io.github.dgreco.rya.rdftriplestore.utils.TransitivePropertySP;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.TupleExprs;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.evaluationsteps.StatementPatternQueryEvaluationStep;

/**
 * RDF4J's stock evaluation, plus the two statement-pattern kinds produced by Rya's inference
 * rewrite, which must not be looked up in the store as ordinary patterns:
 * <ul>
 *   <li>{@link FixedStatementPattern}: matches a list of ontology statements held in memory;</li>
 *   <li>{@link TransitivePropertySP}: matches the transitive closure of a property, computed by
 *       the {@link InferenceEngine}. If neither end is bound it falls back to a plain lookup.</li>
 * </ul>
 * and {@link IndexedStatementPattern}s, whose matches come from a secondary index.
 */
public class RyaEvaluationStrategy extends DefaultEvaluationStrategy {
    private final InferenceEngine inferenceEngine;

    public RyaEvaluationStrategy(final TripleSource tripleSource, final Dataset dataset,
            final InferenceEngine inferenceEngine, final EvaluationStatistics statistics) {
        super(tripleSource, dataset, null, 0, statistics);
        this.inferenceEngine = inferenceEngine;
    }

    /**
     * Uses a {@link BatchedJoinStep} when the right side is a plain store lookup, optionally under
     * filters, and nothing calls for one of RDF4J's other join algorithms; otherwise RDF4J's own.
     */
    @Override
    protected QueryEvaluationStep prepare(final Join node, final QueryEvaluationContext context)
            throws QueryEvaluationException {
        final StatementPattern rightPattern = batchableRightPattern(node);
        if (rightPattern == null) {
            return super.prepare(node, context);
        }
        final StoreTripleSource<?> source = (StoreTripleSource<?>) tripleSource;
        final QueryEvaluationStep left = precompile(node.getLeftArg(), context);
        final QueryEvaluationStep right = precompile(node.getRightArg(), context);
        node.setAlgorithm("RyaBatchedJoin");
        return new BatchedJoinStep(left, right, rightPattern, source, source.getConf().getJoinBatchSize());
    }

    /** @return the statement pattern under the join's right side if the join can be batched, else null. */
    private StatementPattern batchableRightPattern(final Join node) {
        if (!(tripleSource instanceof StoreTripleSource<?> source)
                || source.getConf().getJoinBatchSize() <= 1
                || source.getConf().getLimit() != null
                || dataset != null
                || node.isMergeJoin()
                || isTrackResultSize() || isTrackTime()) {
            return null;
        }
        // Same rule as RDF4J's own join: such a right side is evaluated without the left bindings.
        if (TupleExprs.isVariableScopeChange(node.getRightArg()) || TupleExprs.containsSubquery(node.getRightArg())) {
            return null;
        }
        TupleExpr arg = node.getRightArg();
        while (arg instanceof Filter filter) {
            if (filter.isVariableScopeChange()) {
                return null;
            }
            arg = filter.getArg();
        }
        if (!(arg instanceof StatementPattern sp) || sp.isVariableScopeChange()
                || sp instanceof FixedStatementPattern || sp instanceof TransitivePropertySP) {
            return null;
        }
        // An indexed pattern is a plain store lookup only once its subject or object is bound.
        if (sp instanceof IndexedStatementPattern
                && !(boundByLeft(node, sp.getSubjectVar()) || boundByLeft(node, sp.getObjectVar()))) {
            return null;
        }
        return sp;
    }

    private static boolean boundByLeft(final Join node, final Var var) {
        return var != null && !var.hasValue() && node.getLeftArg().getAssuredBindingNames().contains(var.getName());
    }

    @Override
    protected QueryEvaluationStep prepare(final StatementPattern node, final QueryEvaluationContext context)
            throws QueryEvaluationException {
        if (node instanceof IndexedStatementPattern indexed && IndexTripleSource.supports(tripleSource)) {
            return new StatementPatternQueryEvaluationStep(node, context,
                    new IndexTripleSource((StoreTripleSource<?>) tripleSource, indexed.getIndexQuery()));
        }
        if (node instanceof FixedStatementPattern) {
            final FixedStatementPattern fixed = (FixedStatementPattern) node;
            return bindings -> join(fixed, fixed.statements, bindings);
        }
        if (node instanceof TransitivePropertySP && inferenceEngine != null) {
            final QueryEvaluationStep plain = super.prepare(node, context);
            return bindings -> {
                final Value subj = getVarValue(node.getSubjectVar(), bindings);
                final Value pred = getVarValue(node.getPredicateVar(), bindings);
                final Value obj = getVarValue(node.getObjectVar(), bindings);
                if (!(pred instanceof IRI) || (subj == null && obj == null)) {
                    return plain.evaluate(bindings);
                }
                final Value ctx = node.getContextVar() == null ? null : getVarValue(node.getContextVar(), bindings);
                final Resource[] contexts = ctx instanceof Resource ? new Resource[] { (Resource) ctx } : new Resource[0];
                try {
                    final Set<Statement> closure = inferenceEngine.findTransitiveProperty(
                            subj instanceof Resource ? (Resource) subj : null, (IRI) pred, obj, contexts);
                    if (closure == null) {
                        return plain.evaluate(bindings);
                    }
                    return join(node, closure, bindings);
                } catch (final InferenceEngineException e) {
                    throw new QueryEvaluationException(e);
                }
            };
        }
        return super.prepare(node, context);
    }

    /**
     * Binds the pattern's variables to each statement that agrees with the values already bound.
     */
    private static CloseableIteration<BindingSet> join(final StatementPattern sp, final Collection<Statement> statements,
            final BindingSet bindings) {
        final Var s = sp.getSubjectVar();
        final Var p = sp.getPredicateVar();
        final Var o = sp.getObjectVar();
        final Var c = sp.getContextVar();
        final Value sv = getVarValue(s, bindings);
        final Value pv = getVarValue(p, bindings);
        final Value ov = getVarValue(o, bindings);
        final Value cv = c == null ? null : getVarValue(c, bindings);

        final List<BindingSet> out = new ArrayList<>();
        for (final Statement st : statements) {
            if (!matches(sv, st.getSubject()) || !matches(pv, st.getPredicate()) || !matches(ov, st.getObject())
                    || (cv != null && st.getContext() != null && !cv.equals(st.getContext()))) {
                continue;
            }
            final QueryBindingSet result = new QueryBindingSet(bindings);
            bind(result, s, st.getSubject());
            bind(result, p, st.getPredicate());
            bind(result, o, st.getObject());
            if (st.getContext() != null) {
                bind(result, c, st.getContext());
            }
            out.add(result);
        }
        return new CloseableIteratorIteration<>(out.iterator());
    }

    private static boolean matches(final Value bound, final Value actual) {
        return bound == null || bound.equals(actual);
    }

    private static void bind(final QueryBindingSet result, final Var var, final Value value) {
        if (var != null && !var.isConstant() && !result.hasBinding(var.getName())) {
            result.addBinding(var.getName(), value);
        }
    }
}
