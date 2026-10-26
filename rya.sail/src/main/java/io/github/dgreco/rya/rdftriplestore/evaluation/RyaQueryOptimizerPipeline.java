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

import java.util.List;

import io.github.dgreco.rya.rdftriplestore.inference.InferenceOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizerPipeline;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.ConstantOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.FilterOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.QueryJoinOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.RegexAsStringFunctionOptimizer;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.StandardQueryOptimizerPipeline;

/**
 * RDF4J's standard optimizer pipeline (as of 6.1), with Rya's inference rewrite inserted
 * after the tree is normalized and before join ordering. Join ordering therefore runs once,
 * on the final tree, using Rya's cardinality statistics.
 */
public class RyaQueryOptimizerPipeline implements QueryOptimizerPipeline {
    private final EvaluationStrategy strategy;
    private final TripleSource tripleSource;
    private final EvaluationStatistics statistics;
    private final InferenceOptimizer inference;

    public RyaQueryOptimizerPipeline(final EvaluationStrategy strategy, final TripleSource tripleSource,
            final EvaluationStatistics statistics, final InferenceOptimizer inference) {
        this.strategy = strategy;
        this.tripleSource = tripleSource;
        this.statistics = statistics;
        this.inference = inference;
    }

    @Override
    public Iterable<QueryOptimizer> getOptimizers() {
        return List.of(
                StandardQueryOptimizerPipeline.BINDING_ASSIGNER,
                StandardQueryOptimizerPipeline.BINDING_SET_ASSIGNMENT_INLINER,
                new ConstantOptimizer(strategy),
                new RegexAsStringFunctionOptimizer(tripleSource.getValueFactory()),
                StandardQueryOptimizerPipeline.COMPARE_OPTIMIZER,
                StandardQueryOptimizerPipeline.CONJUNCTIVE_CONSTRAINT_SPLITTER,
                StandardQueryOptimizerPipeline.DISJUNCTIVE_CONSTRAINT_OPTIMIZER,
                StandardQueryOptimizerPipeline.SAME_TERM_FILTER_OPTIMIZER,
                StandardQueryOptimizerPipeline.UNION_SCOPE_CHANGE_OPTIMIZER,
                StandardQueryOptimizerPipeline.QUERY_MODEL_NORMALIZER,
                StandardQueryOptimizerPipeline.PROJECTION_REMOVAL_OPTIMIZER,
                inference,
                new QueryJoinOptimizer(statistics, strategy.isTrackResultSize(), tripleSource),
                StandardQueryOptimizerPipeline.ITERATIVE_EVALUATION_OPTIMIZER,
                new FilterOptimizer(statistics, false, true),
                StandardQueryOptimizerPipeline.FILTER_IN_VALUES_OPTIMIZER,
                new FilterOptimizer(statistics),
                StandardQueryOptimizerPipeline.ORDER_LIMIT_OPTIMIZER);
    }
}
