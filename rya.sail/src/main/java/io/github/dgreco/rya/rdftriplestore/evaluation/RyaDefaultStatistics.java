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

import io.github.dgreco.rya.rdftriplestore.evaluation.index.IndexedStatementPattern;
import io.github.dgreco.rya.rdftriplestore.utils.FixedStatementPattern;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;

/**
 * RDF4J's default cardinality estimates, used when Rya statistics are off. Ontology patterns
 * produced by inference are answered from memory, so their real size is used; this keeps
 * them ahead of the store lookups they feed.
 */
public class RyaDefaultStatistics extends EvaluationStatistics {

    @Override
    protected CardinalityCalculator createCardinalityCalculator() {
        return new CardinalityCalculator() {
            @Override
            protected double getCardinality(final StatementPattern sp) {
                if (sp instanceof FixedStatementPattern) {
                    return ((FixedStatementPattern) sp).statements.size();
                }
                if (sp instanceof IndexedStatementPattern) {
                    return Math.max(1, super.getCardinality(sp) * IndexedStatementPattern.SELECTIVITY);
                }
                return super.getCardinality(sp);
            }
        };
    }
}
