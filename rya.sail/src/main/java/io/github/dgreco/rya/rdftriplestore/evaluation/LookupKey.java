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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;

/**
 * One statement lookup, exactly as RDF4J passes it to
 * {@code TripleSource.getStatements(subject, predicate, object, contexts...)}. Null subject,
 * predicate or object are wildcards. Contexts follow RDF4J: empty means every graph, a
 * {@code null} element means the default graph.
 */
public record LookupKey(Resource subject, IRI predicate, Value object, List<Resource> contexts) {

    public static LookupKey of(final Resource subject, final IRI predicate, final Value object,
            final Resource... contexts) {
        final List<Resource> ctx = contexts == null || contexts.length == 0
                ? Collections.emptyList()
                : Collections.unmodifiableList(Arrays.asList(contexts.clone()));
        return new LookupKey(subject, predicate, object, ctx);
    }
}
