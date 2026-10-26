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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.dgreco.rya.RyaSailITBase;
import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRdfEvalStatsDAO;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceOptimizer;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The eval-table counters must drive join order: the selective pattern goes first even when
 * the query lists it last.
 */
public class StatsJoinOrderIT extends RyaSailITBase {
    private static final String QUERY =
            "SELECT * WHERE { ?s <urn:common> ?o . ?s <urn:rare> ?x }";

    private AccumuloRdfConfiguration conf;
    private AccumuloRyaDAO dao;

    @BeforeEach
    public void load() {
        conf = newConf();
        dao = newDao(conf);
        final List<RyaStatement> data = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            data.add(new RyaStatement(new RyaIRI("urn:s" + i), new RyaIRI("urn:common"), new RyaIRI("urn:o" + i)));
        }
        data.add(new RyaStatement(new RyaIRI("urn:s7"), new RyaIRI("urn:rare"), new RyaIRI("urn:x")));
        dao.add(data.iterator());
    }

    @AfterEach
    public void close() {
        dao.destroy();
    }

    @Test
    public void ryaStatisticsPutTheRarePatternFirst() throws Exception {
        final AccumuloRdfEvalStatsDAO stats = new AccumuloRdfEvalStatsDAO(getClient(), conf);
        stats.init();
        assertEquals(50.0, stats.getCardinality(conf, io.github.dgreco.rya.api.persist.RdfEvalStatsDAO.CARDINALITY_OF.PREDICATE,
                List.of(org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance().createIRI("urn:common"))));

        final List<String> order = joinOrder(new RdfCloudTripleStoreEvaluationStatistics<>(conf, stats));
        assertEquals(List.of("urn:rare", "urn:common"), order);
    }

    @Test
    public void withoutStatisticsTheQueryOrderIsKept() throws Exception {
        final List<String> order = joinOrder(new RyaDefaultStatistics());
        assertTrue(order.indexOf("urn:common") < order.indexOf("urn:rare"), order.toString());
    }

    /** @return the predicates of the optimized query's statement patterns, in evaluation order. */
    private List<String> joinOrder(final EvaluationStatistics statistics) {
        final StoreTripleSource<AccumuloRdfConfiguration> source = new StoreTripleSource<>(conf, dao);
        final RyaEvaluationStrategy strategy = new RyaEvaluationStrategy(source, null, null, statistics);
        strategy.setOptimizerPipeline(new RyaQueryOptimizerPipeline(strategy, source, statistics,
                new InferenceOptimizer(conf, null)));
        final TupleExpr parsed = new QueryRoot(new SPARQLParser().parseQuery(QUERY, null).getTupleExpr());
        final TupleExpr optimized = strategy.optimize(parsed, statistics, EmptyBindingSet.getInstance());

        final List<String> predicates = new ArrayList<>();
        optimized.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Join node) {
                node.getLeftArg().visit(this);
                node.getRightArg().visit(this);
            }

            @Override
            public void meet(final StatementPattern node) {
                predicates.add(node.getPredicateVar().getValue().stringValue());
            }
        });
        return predicates;
    }
}
