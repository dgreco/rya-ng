package io.github.dgreco.rya.sparql;

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

import io.github.dgreco.rya.api.domain.RyaSchema;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResultHandler;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.helpers.RDFHandlerWrapper;

/**
 * Rya stores blank nodes as IRIs in {@link RyaSchema#BNODE_NAMESPACE}. These wrappers turn
 * them back into blank nodes in the results the endpoint sends.
 */
final class BlankNodeOutput {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String PREFIX = RyaSchema.BNODE_NAMESPACE;

    private BlankNodeOutput() {
    }

    static Value convert(final Value value) {
        if (value instanceof IRI && value.stringValue().startsWith(PREFIX)) {
            return VF.createBNode(value.stringValue().substring(PREFIX.length()));
        }
        return value;
    }

    static TupleQueryResultHandler wrap(final TupleQueryResultHandler delegate) {
        return new TupleQueryResultHandler() {
            @Override
            public void handleBoolean(final boolean value) {
                delegate.handleBoolean(value);
            }

            @Override
            public void handleLinks(final List<String> linkUrls) {
                delegate.handleLinks(linkUrls);
            }

            @Override
            public void startQueryResult(final List<String> bindingNames) {
                delegate.startQueryResult(bindingNames);
            }

            @Override
            public void endQueryResult() {
                delegate.endQueryResult();
            }

            @Override
            public void handleSolution(final BindingSet bindings) {
                final MapBindingSet converted = new MapBindingSet(bindings.size());
                for (final Binding b : bindings) {
                    converted.addBinding(b.getName(), convert(b.getValue()));
                }
                delegate.handleSolution(converted);
            }
        };
    }

    static RDFHandler wrap(final RDFHandler delegate) {
        return new RDFHandlerWrapper(delegate) {
            @Override
            public void handleStatement(final Statement st) {
                final Resource ctx = st.getContext() == null ? null : (Resource) convert(st.getContext());
                final Resource subj = (Resource) convert(st.getSubject());
                final Value obj = convert(st.getObject());
                super.handleStatement(ctx == null ? VF.createStatement(subj, st.getPredicate(), obj)
                        : VF.createStatement(subj, st.getPredicate(), obj, ctx));
            }
        };
    }
}
