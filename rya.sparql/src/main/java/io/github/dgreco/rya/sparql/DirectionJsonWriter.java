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
package io.github.dgreco.rya.sparql;

import java.io.OutputStream;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.QueryResultHandlerException;
import org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter;

/**
 * RDF4J's SPARQL JSON writer, with the base direction of a literal written as SPARQL 1.2 defines
 * it: {@code "its:dir": "rtl"}. RDF4J 6.1 writes {@code "--rtl"}, which its own parser rejects.
 */
final class DirectionJsonWriter extends SPARQLResultsJSONWriter {
    DirectionJsonWriter(final OutputStream out) {
        super(out);
    }

    @Override
    protected void writeValue(final Value value) throws QueryResultHandlerException {
        if (value instanceof Literal literal && literal.getBaseDirection() != Literal.BaseDirection.NONE) {
            jg.writeStartObject();
            jg.writeStringProperty("type", "literal");
            jg.writeStringProperty("value", literal.getLabel());
            jg.writeStringProperty("xml:lang", literal.getLanguage().orElseThrow());
            jg.writeStringProperty("its:dir",
                    literal.getBaseDirection().toString().substring(Literal.BASE_DIR_SEPARATOR.length()));
            jg.writeEndObject();
        } else {
            super.writeValue(value);
        }
    }
}
