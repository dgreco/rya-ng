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
package org.tripatha.api.domain;

import org.eclipse.rdf4j.model.vocabulary.XSD;

/**
 * A range over an object's stored data, whatever the object's type: a lookup with it adds no
 * type check, so it returns strings, language-tagged strings, IRIs and any other value whose data
 * bytes fall in the range. The bounds are text, encoded as {@code xsd:string} data is: as is.
 *
 * <p>Only for callers that check every returned value themselves, such as a {@code STRSTARTS}
 * filter that stays in the query.
 */
public class RyaAnyTypeRange extends RyaTypeRange {

    public RyaAnyTypeRange(final String start, final String stop) {
        super(new RyaType(XSD.STRING, start), new RyaType(XSD.STRING, stop));
    }

    /**
     * Every stored value whose data starts with {@code prefix}: up to the prefix followed by
     * U+10FFFF, the last code point, as {@code getStatementsAboutTripleTerms} bounds its range.
     */
    public static RyaAnyTypeRange startingWith(final String prefix) {
        return new RyaAnyTypeRange(prefix, prefix + "􏿿");
    }
}
