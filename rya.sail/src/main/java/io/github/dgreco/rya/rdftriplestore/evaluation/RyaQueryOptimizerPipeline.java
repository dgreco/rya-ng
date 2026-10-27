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

import java.util.ArrayList;

import io.github.dgreco.rya.accumulo.index.IndexCatalog;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.rdftriplestore.evaluation.index.IndexSearchOptimizer;
import io.github.dgreco.rya.rdftriplestore.evaluation.index.TextQueryValidator;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceOptimizer;
import io.github.dgreco.rya.rdftriplestore.stats.GraphSizeOptimizer;
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
 * RDF4J's standard optimizer pipeline (as of 6.1), with Rya's graph-size, inference and index
 * rewrites inserted after the tree is normalized and before join ordering. Join ordering
 * therefore runs once, on the final tree, using Rya's cardinality statistics.
 */
public class RyaQueryOptimizerPipeline implements QueryOptimizerPipeline {
    private final EvaluationStrategy strategy;
    private final TripleSource tripleSource;
    private final EvaluationStatistics statistics;
    private final InferenceOptimizer inference;
    private final IndexSearchOptimizer indexSearch;
    private final GraphSizeOptimizer graphSizes;

    /** Without secondary indexes. */
    public RyaQueryOptimizerPipeline(final EvaluationStrategy strategy, final TripleSource tripleSource,
            final EvaluationStatistics statistics, final InferenceOptimizer inference) {
        this(strategy, tripleSource, statistics, inference, IndexCatalog.NONE, null);
    }

    /**
     * @param indexes the secondary indexes queries may use
     * @param conf the query's configuration (whether to use the indexes); ignored without indexes
     */
    public RyaQueryOptimizerPipeline(final EvaluationStrategy strategy, final TripleSource tripleSource,
            final EvaluationStatistics statistics, final InferenceOptimizer inference, final IndexCatalog indexes,
            final RdfCloudTripleStoreConfiguration conf) {
        this(strategy, tripleSource, statistics, inference, indexes, conf, null);
    }

    /**
     * @param graphSizes answers {@code stats:approxSize} patterns from the statistics; null
     *        leaves them as ordinary patterns
     */
    public RyaQueryOptimizerPipeline(final EvaluationStrategy strategy, final TripleSource tripleSource,
            final EvaluationStatistics statistics, final InferenceOptimizer inference, final IndexCatalog indexes,
            final RdfCloudTripleStoreConfiguration conf, final GraphSizeOptimizer graphSizes) {
        this.strategy = strategy;
        this.tripleSource = tripleSource;
        this.statistics = statistics;
        this.inference = inference;
        this.indexSearch = indexes == IndexCatalog.NONE || conf == null ? null : new IndexSearchOptimizer(indexes, conf);
        this.graphSizes = graphSizes;
    }

    @Override
    public Iterable<QueryOptimizer> getOptimizers() {
        final List<QueryOptimizer> optimizers = new ArrayList<>(List.of(
                StandardQueryOptimizerPipeline.BINDING_ASSIGNER,
                StandardQueryOptimizerPipeline.BINDING_SET_ASSIGNMENT_INLINER,
                // Rejects malformed constant text queries whether or not an index will be used.
                new TextQueryValidator(),
                new ConstantOptimizer(strategy),
                new RegexAsStringFunctionOptimizer(tripleSource.getValueFactory()),
                StandardQueryOptimizerPipeline.COMPARE_OPTIMIZER,
                StandardQueryOptimizerPipeline.CONJUNCTIVE_CONSTRAINT_SPLITTER,
                StandardQueryOptimizerPipeline.DISJUNCTIVE_CONSTRAINT_OPTIMIZER,
                StandardQueryOptimizerPipeline.SAME_TERM_FILTER_OPTIMIZER,
                StandardQueryOptimizerPipeline.UNION_SCOPE_CHANGE_OPTIMIZER,
                StandardQueryOptimizerPipeline.QUERY_MODEL_NORMALIZER,
                StandardQueryOptimizerPipeline.PROJECTION_REMOVAL_OPTIMIZER));
        if (graphSizes != null) {
            // Before inference, which leaves the fixed patterns it produces alone.
            optimizers.add(graphSizes);
        }
        optimizers.add(inference);
        if (indexSearch != null) {
            // After inference, so inferred sub-properties are searched too; before join ordering,
            // which then sees the much smaller indexed patterns.
            optimizers.add(indexSearch);
        }
        optimizers.addAll(List.of(
                new QueryJoinOptimizer(statistics, strategy.isTrackResultSize(), tripleSource),
                StandardQueryOptimizerPipeline.ITERATIVE_EVALUATION_OPTIMIZER,
                new FilterOptimizer(statistics, false, true),
                StandardQueryOptimizerPipeline.FILTER_IN_VALUES_OPTIMIZER,
                new FilterOptimizer(statistics),
                StandardQueryOptimizerPipeline.ORDER_LIMIT_OPTIMIZER));
        return optimizers;
    }
}
