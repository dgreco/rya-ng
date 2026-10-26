package io.github.dgreco.rya.rdftriplestore.inference;

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

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

/**
 * Rewrites the query so it also returns statements implied by the ontology (subclasses,
 * subproperties, sameAs, inverse, symmetric and transitive properties, restrictions...).
 * Each kind of reasoning is one visitor; it can be switched off with its
 * {@code infer.include.*} configuration flag. Does nothing unless {@code query.infer} is true.
 */
public class InferenceOptimizer implements QueryOptimizer {
    private final RdfCloudTripleStoreConfiguration conf;
    private final InferenceEngine engine;

    public InferenceOptimizer(final RdfCloudTripleStoreConfiguration conf, final InferenceEngine engine) {
        this.conf = conf;
        this.engine = engine;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (engine == null || !conf.isInfer()) {
            return;
        }
        // Order matters: later visitors expand what earlier ones produced.
        final List<AbstractQueryModelVisitor<Exception>> visitors = List.of(
                new DomainRangeVisitor(conf, engine),
                new SomeValuesFromVisitor(conf, engine),
                new AllValuesFromVisitor(conf, engine),
                new HasValueVisitor(conf, engine),
                new IntersectionOfVisitor(conf, engine),
                new ReflexivePropertyVisitor(conf, engine),
                new PropertyChainVisitor(conf, engine),
                new TransitivePropertyVisitor(conf, engine),
                new SymmetricPropertyVisitor(conf, engine),
                new InverseOfVisitor(conf, engine),
                new SubPropertyOfVisitor(conf, engine),
                new SubClassOfVisitor(conf, engine),
                new SameAsVisitor(conf, engine),
                new OneOfVisitor(conf, engine),
                new HasSelfVisitor(conf, engine));
        try {
            for (final AbstractQueryModelVisitor<Exception> visitor : visitors) {
                tupleExpr.visit(visitor);
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new QueryEvaluationException("Inference rewrite failed", e);
        }
    }
}
