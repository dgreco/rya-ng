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
package io.github.dgreco.rya.rdftriplestore.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SESAME;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;

/**
 * All predicates are changed
 * Class SubPropertyOfVisitor
 * Date: Mar 29, 2011
 * Time: 11:28:34 AM
 */
public class PropertyChainVisitor extends AbstractInferVisitor {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();


    public PropertyChainVisitor(final RdfCloudTripleStoreConfiguration conf, final InferenceEngine inferenceEngine) {
        super(conf, inferenceEngine);
        include = conf.isInferPropertyChain();
    }


    @Override
    protected void meetSP(final StatementPattern node) throws Exception {
        final StatementPattern sp = node.clone();
        final Var predVar = sp.getPredicateVar();

        final IRI pred = (IRI) predVar.getValue();
        final String predNamespace = pred.getNamespace();

        final Var objVar = sp.getObjectVar();
        final Var cntxtVar = sp.getContextVar();
        if (objVar != null &&
                !RDF.NAMESPACE.equals(predNamespace) &&
                !SESAME.NAMESPACE.equals(predNamespace) &&
                !RDFS.NAMESPACE.equals(predNamespace)
                && !EXPANDED.equals(cntxtVar)) {

            final IRI chainPropIRI = (IRI) predVar.getValue();
            final List<IRI> chain = inferenceEngine.getPropertyChain(chainPropIRI);
            final List<StatementPattern> expandedPatterns = new ArrayList<StatementPattern>();
            if (chain.size() > 0) {
                final Var originalSubj = sp.getSubjectVar();
                final Var originalObj = sp.getObjectVar();

                // s chain[0] ?c1 . ?c1 chain[1] ?c2 . ... ?cn-1 chain[n-1] o  (inverse links swap ends)
                Var nextSubj = originalSubj;
                for (int i = 0; i < chain.size(); i++) {
                    final IRI chainElement = chain.get(i);
                    final String s = UUID.randomUUID().toString();
                    final boolean last = i == chain.size() - 1;
                    final Var currentObj = last ? originalObj : new Var("c-" + s);
                    final Var chainPred = new Var("p-" + s, VF.createIRI(chainElement.stringValue()));
                    final Var context = sp.getContextVar() == null ? null : sp.getContextVar().clone();
                    final StatementPattern statementPattern = chainElement instanceof InverseIRI
                            ? new StatementPattern(c(currentObj), c(chainPred), c(nextSubj), c(context))
                            : new StatementPattern(c(nextSubj), c(chainPred), c(currentObj), c(context));
                    expandedPatterns.add(statementPattern);
                    // Each Var node may have only one parent.
                    nextSubj = currentObj.clone();
                }

                TupleExpr lastRight = null;
                // replace the statement pattern with a series of joins
                for (final StatementPattern pattern : expandedPatterns){
                    if (lastRight == null){
                        lastRight = pattern;
                    }
                    else {
                        final Join newJoin = new Join(pattern, lastRight);
                        lastRight = newJoin;
                    }
                }
                if (lastRight != null){
                    node.replaceWith(lastRight);
                }

            }
        }
    }
}
