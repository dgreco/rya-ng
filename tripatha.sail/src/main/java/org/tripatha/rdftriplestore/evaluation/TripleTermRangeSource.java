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

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TripleRef;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.resolver.RdfToRyaConversions;
import org.tripatha.api.resolver.impl.TripleTermResolver;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;

/**
 * The store, for a statement pattern whose object is a triple term with a constant subject, as
 * in {@code ?r rdf:reifies <<( :alice ?p ?o )>>}. The pattern alone would read every
 * {@code rdf:reifies} statement, for the term's reference to discard all but Alice's. Stored
 * triple terms sort by their subject, so when neither end of the pattern is bound this reads
 * only the range of terms about that subject. Otherwise the lookup is already narrow, and goes
 * to the store as it is.
 *
 * <p>It only leaves out statements the term's reference would reject, so results don't change.
 */
final class TripleTermRangeSource implements TripleSource {
    private final StoreTripleSource<?> store;
    private final String prefix;

    TripleTermRangeSource(final StoreTripleSource<?> store, final String prefix) {
        this.store = store;
        this.prefix = prefix;
    }

    /**
     * @return the prefix of the stored triple terms this pattern's object must be one of, or
     *         {@code null} if its object is not a triple term with a constant subject.
     */
    static String prefixFor(final StatementPattern pattern) {
        final Var object = pattern.getObjectVar();
        if (object == null || object.hasValue()) {
            return null;
        }
        QueryModelNode root = pattern;
        while (root.getParentNode() != null) {
            root = root.getParentNode();
        }
        final String[] prefix = new String[1];
        root.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final TripleRef ref) {
                if (object.getName().equals(ref.getExprVar().getName()) && ref.getSubjectVar().getValue() instanceof Resource subject) {
                    final RyaIRI predicate = ref.getPredicateVar().getValue() instanceof IRI iri ? RdfToRyaConversions.convertIRI(iri) : null;
                    prefix[0] = TripleTermResolver.prefix(RdfToRyaConversions.convertResource(subject), predicate);
                }
            }
        });
        return prefix[0];
    }

    @Override
    public CloseableIteration<? extends Statement> getStatements(final Resource subject, final IRI predicate,
            final Value object, final Resource... contexts) throws QueryEvaluationException {
        if (subject != null || object != null) {
            return store.getStatements(subject, predicate, object, contexts);
        }
        return store.getStatementsAboutTripleTerms(predicate, prefix, contexts);
    }

    @Override
    public ValueFactory getValueFactory() {
        return store.getValueFactory();
    }
}
