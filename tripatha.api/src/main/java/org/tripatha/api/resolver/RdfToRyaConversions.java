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
package org.tripatha.api.resolver;

import static org.tripatha.api.utils.LiteralLanguageUtils.UNDETERMINED_LANGUAGE;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tripatha.api.domain.RangeIRI;
import org.tripatha.api.domain.RangeValue;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaIRIRange;
import org.tripatha.api.domain.RyaSchema;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaTripleTerm;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.domain.RyaTypeRange;
import org.tripatha.api.log.LogUtils;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Literals;
import org.eclipse.rdf4j.model.vocabulary.RDF;

/**
 * Methods for converting values from their RDF4J object representations into
 * their Tripatha object equivalents.
 */
public class RdfToRyaConversions {
    private static final Logger log = LoggerFactory.getLogger(RdfToRyaConversions.class);

    /**
     * Converts a {@link IRI} into a {@link RyaIRI} representation of the
     * {@code iri}.
     * @param iri the {@link IRI} to convert.
     * @return the {@link RyaIRI} representation of the {@code iri}.
     */
    public static RyaIRI convertIRI(final IRI iri) {
        if (iri == null) {
            return null;
        }
        if (iri instanceof RangeIRI) {
            final RangeIRI riri = (RangeIRI) iri;
            return new RyaIRIRange(convertIRI(riri.getStart()), convertIRI(riri.getEnd()));
        }
        return new RyaIRI(iri.stringValue());
    }

    /**
     * Converts a {@link Literal} into a {@link RyaType} representation of the
     * {@code literal}.
     * @param literal the {@link Literal} to convert.
     * @return the {@link RyaType} representation of the {@code literal}.
     */
    public static RyaType convertLiteral(final Literal literal) {
        if (literal == null) {
            return null;
        }
        if (literal.getDatatype() != null) {
            if (RyaSchema.TRIPLE_TERM.equals(literal.getDatatype())) {
                // Reserved: it is how a triple term's resolver is found, and such a literal would be written as one.
                throw new UnsupportedValueException("Literals of the reserved datatype " + RyaSchema.TRIPLE_TERM + " cannot be stored");
            }
            if (RDF.DIRLANGSTRING.equals(literal.getDatatype())) {
                // The RDF 1.2 tag as written, "ar--rtl": language and direction travel together.
                final String language = literal.getLanguage().filter(Literals::isValidLanguageTag).orElse(UNDETERMINED_LANGUAGE);
                return new RyaType(literal.getDatatype(), literal.stringValue(), language + literal.getBaseDirection());
            }
            if (Literals.isLanguageLiteral(literal)) {
                final String language = literal.getLanguage().get();
                if (Literals.isValidLanguageTag(language)) {
                    return new RyaType(literal.getDatatype(), literal.stringValue(), language);
                } else {
                    log.warn("Invalid language (" + LogUtils.clean(language) + ") found in Literal. Defaulting to: " + UNDETERMINED_LANGUAGE);
                    // Replace invalid language with "und"
                    return new RyaType(literal.getDatatype(), literal.stringValue(), UNDETERMINED_LANGUAGE);
                }
            }
            return new RyaType(literal.getDatatype(), literal.stringValue());
        }
        return new RyaType(literal.stringValue());
    }

    /**
     * Converts a {@link Value} into a {@link RyaType} representation of the
     * {@code value}.
     * @param value the {@link Value} to convert.
     * @return the {@link RyaType} representation of the {@code value}.
     * @throws UnsupportedValueException if the store has no encoding for the value. Returning
     *         {@code null} instead would turn it into a wildcard.
     */
    public static RyaType convertValue(final Value value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Resource) {
            return convertResource((Resource) value);
        }
        if (value instanceof TripleTerm) {
            final TripleTerm term = (TripleTerm) value;
            return new RyaTripleTerm(convertResource(term.getSubject()), convertIRI(term.getPredicate()),
                    convertValue(term.getObject()));
        }
        if (value instanceof Literal) {
            return convertLiteral((Literal) value);
        }
        if (value instanceof RangeValue) {
            final RangeValue<?> rv = (RangeValue<?>) value;
            if (rv.getStart() instanceof IRI) {
                return new RyaIRIRange(convertIRI((IRI) rv.getStart()), convertIRI((IRI) rv.getEnd()));
            } else {
                //literal
                return new RyaTypeRange(convertLiteral((Literal) rv.getStart()), convertLiteral((Literal) rv.getEnd()));
            }
        }
        throw new UnsupportedValueException("Values of this kind cannot be stored or looked up: " + LogUtils.clean(value.toString()));
    }

    /**
     * Converts a {@link Resource} into a {@link RyaIRI} representation of the
     * {@code resource}.
     * @param resource the {@link Resource} to convert. Generally this will be
     * the subject.
     * @return the {@link RyaIRI} representation of the {@code resource}.
     */
    public static RyaIRI convertResource(final Resource resource) {
        if (resource == null) {
            return null;
        }
        if (resource instanceof BNode) {
            return new RyaIRI(RyaSchema.BNODE_NAMESPACE + ((BNode) resource).getID());
        }
        return convertIRI((IRI) resource);
    }

    /**
     * Converts a {@link Statement} into a {@link RyaStatement} representation
     * of the {@code statement}.
     * @param statement the {@link Statement} to convert.
     * @return the {@link RyaStatement} representation of the {@code statement}.
     */
    public static RyaStatement convertStatement(final Statement statement) {
        if (statement == null) {
            return null;
        }
        final Resource subject = statement.getSubject();
        final IRI predicate = statement.getPredicate();
        final Value object = statement.getObject();
        final Resource context = statement.getContext();
        return new RyaStatement(
                convertResource(subject),
                convertIRI(predicate),
                convertValue(object),
                convertResource(context));
    }
}
