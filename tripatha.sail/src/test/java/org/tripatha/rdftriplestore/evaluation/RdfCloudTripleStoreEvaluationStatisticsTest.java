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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.junit.jupiter.api.Test;
import org.tripatha.api.persist.RdfDAOException;
import org.tripatha.api.persist.RdfEvalStatsDAO;
import org.tripatha.rdftriplestore.inference.DoNotExpandSP;
import org.tripatha.rdftriplestore.utils.FixedStatementPattern;
import org.tripatha.store.StoreRdfConfiguration;

/**
 * Cardinality estimates read a missing counter as 1. A store may leave counters of 1 out (the
 * loader does, for subjects), so a missing counter and a counter of 1 must give one estimate.
 */
public class RdfCloudTripleStoreEvaluationStatisticsTest {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI KNOWS = VF.createIRI("urn:knows");
    private static final IRI FRIEND_OF = VF.createIRI("urn:friendOf");
    private static final IRI COLLEAGUE_OF = VF.createIRI("urn:colleagueOf");

    @Test
    public void aMissingCounterCountsAsOneForASinglePattern() {
        final StatementPattern counted = new StatementPattern(new Var("s"), constant("c-p", FRIEND_OF), new Var("o"));
        final StatementPattern missing = new StatementPattern(new Var("s"), constant("c-p", COLLEAGUE_OF), new Var("o"));
        final RdfCloudTripleStoreEvaluationStatistics<StoreRdfConfiguration> stats = statistics(Map.of(FRIEND_OF, 5.0));
        assertEquals(5.0, stats.getCardinality(counted));
        assertEquals(1.0, stats.getCardinality(missing));
    }

    /**
     * The inference rewrite of a sub-property: the fixed pattern lists the sub-properties, and the
     * pattern after it adds up their counters. A sub-property without a counter adds 1, not -1.
     */
    @Test
    public void aMissingCounterCountsAsOneInASubPropertySum() {
        final FixedStatementPattern subProperties = new FixedStatementPattern(new Var("p"),
                constant("c-sub", RDFS.SUBPROPERTYOF), constant("c-knows", KNOWS));
        subProperties.statements.add(VF.createStatement(FRIEND_OF, RDFS.SUBPROPERTYOF, KNOWS));
        subProperties.statements.add(VF.createStatement(COLLEAGUE_OF, RDFS.SUBPROPERTYOF, KNOWS));
        final DoNotExpandSP uses = new DoNotExpandSP(new Var("s"), new Var("p"), new Var("o"));

        statistics(Map.of(FRIEND_OF, 5.0)).getCardinality(new Join(subProperties, uses));

        assertEquals(2.0, subProperties.getCardinality());
        assertEquals(5.0 + 1.0, uses.getCardinality());
    }

    private static Var constant(final String name, final Value value) {
        return new Var(name, value);
    }

    /** Statistics over predicate counters; any other counter is missing. */
    private static RdfCloudTripleStoreEvaluationStatistics<StoreRdfConfiguration> statistics(final Map<IRI, Double> predicates) {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        return new RdfCloudTripleStoreEvaluationStatistics<>(conf, new RdfEvalStatsDAO<>() {
            @Override
            public void init() {
            }

            @Override
            public boolean isInitialized() {
                return true;
            }

            @Override
            public void destroy() {
            }

            @Override
            public double getCardinality(final StoreRdfConfiguration c, final CARDINALITY_OF card, final List<Value> val)
                    throws RdfDAOException {
                return getCardinality(c, card, val, null);
            }

            @Override
            public double getCardinality(final StoreRdfConfiguration c, final CARDINALITY_OF card, final List<Value> val,
                    final Resource context) throws RdfDAOException {
                if (card == CARDINALITY_OF.PREDICATE && val.size() == 1) {
                    return predicates.getOrDefault(val.get(0), -1.0);
                }
                return -1;
            }

            @Override
            public void setConf(final StoreRdfConfiguration c) {
            }

            @Override
            public StoreRdfConfiguration getConf() {
                return conf;
            }
        });
    }
}
