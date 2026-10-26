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
package io.github.dgreco.rya.rdftriplestore.function;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/** IRIs of the full-text search function. */
public final class RyaText {
    /** Namespace of the text functions; conventionally bound to the prefix {@code text:}. */
    public static final String NAMESPACE = "urn:rya-ng:text#";
    /** {@code text:matches(?literal, "query")}: whether the literal satisfies the text query. */
    public static final IRI MATCHES = SimpleValueFactory.getInstance().createIRI(NAMESPACE, "matches");

    private RyaText() {
    }
}
