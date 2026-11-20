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
package org.tripatha.rdftriplestore.stats;

import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/** IRIs of the statistics that queries can read. */
public final class RyaStats {
    /** Namespace of the statistics; conventionally bound to the prefix {@code stats:}. */
    public static final String NAMESPACE = "urn:tripatha:stats#";
    /**
     * {@code ?graph stats:approxSize ?n}: each named graph with data, and its approximate number
     * of statements (an {@code xsd:long}), read from the statistics counters.
     */
    public static final IRI APPROX_SIZE = SimpleValueFactory.getInstance().createIRI(NAMESPACE, "approxSize");
    /**
     * {@code ?class stats:approxInstances ?n}: each class with {@code rdf:type} statements, and
     * their approximate number (an {@code xsd:long}), read from the counters; no inference.
     */
    public static final IRI APPROX_INSTANCES = SimpleValueFactory.getInstance().createIRI(NAMESPACE, "approxInstances");
    /**
     * {@code ?predicate stats:approxTriples ?n}: each predicate with statements, and their
     * approximate number (an {@code xsd:long}), read from the counters.
     */
    public static final IRI APPROX_TRIPLES = SimpleValueFactory.getInstance().createIRI(NAMESPACE, "approxTriples");
    /** Every predicate answered from the counters. */
    public static final Set<IRI> COUNTED = Set.of(APPROX_SIZE, APPROX_INSTANCES, APPROX_TRIPLES);

    private RyaStats() {
    }
}
