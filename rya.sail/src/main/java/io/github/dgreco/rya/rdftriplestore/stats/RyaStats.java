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
package io.github.dgreco.rya.rdftriplestore.stats;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/** IRIs of the statistics that queries can read. */
public final class RyaStats {
    /** Namespace of the statistics; conventionally bound to the prefix {@code stats:}. */
    public static final String NAMESPACE = "urn:rya-ng:stats#";
    /**
     * {@code ?graph stats:approxSize ?n}: each named graph with data, and its approximate number
     * of statements (an {@code xsd:long}), read from the statistics counters.
     */
    public static final IRI APPROX_SIZE = SimpleValueFactory.getInstance().createIRI(NAMESPACE, "approxSize");

    private RyaStats() {
    }
}
