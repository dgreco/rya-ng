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

import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaSchema;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaTripleTerm;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.utils.LiteralLanguageUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Literals;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;

/**
 * Methods for converting values from their Tripatha object representations into
 * their RDF4J object equivalents.
 */
public class RyaToRdfConversions {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * Converts a {@link RyaIRI} into a {@link IRI} representation of the
     * {@code ryaIri}.
     * @param ryaIri the {@link RyaIRI} to convert.
     * @return the {@link IRI} representation of the {@code ryaIri}.
     */
    public static IRI convertIRI(final RyaIRI ryaIri) {
        return VF.createIRI(ryaIri.getData());
    }

    /**
     * Converts a {@link RyaType} into a {@link IRI} representation of the
     * {@code ryaType}.
     * @param ryaType the {@link RyaType} to convert.
     * @return the {@link IRI} representation of the {@code ryaType}.
     */
    private static IRI convertIRI(final RyaType ryaType) {
        return VF.createIRI(ryaType.getData());
    }

    /**
     * Converts a {@link RyaType} into a {@link Literal} representation of the
     * {@code ryaType}.
     * @param ryaType the {@link RyaType} to convert.
     * @return the {@link Literal} representation of the {@code ryaType}.
     */
    public static Literal convertLiteral(final RyaType ryaType) {
        if (XMLSchema.STRING.equals(ryaType.getDataType())) {
            return VF.createLiteral(ryaType.getData());
        } else if (RDF.LANGSTRING.equals(ryaType.getDataType())) {
            final String data = ryaType.getData();
            final String language = ryaType.getLanguage();
            if (language != null && Literals.isValidLanguageTag(language)) {
                return VF.createLiteral(data, language);
            } else {
                return VF.createLiteral(data, LiteralLanguageUtils.UNDETERMINED_LANGUAGE);
            }
        } else if (RDF.DIRLANGSTRING.equals(ryaType.getDataType())) {
            final String tag = ryaType.getLanguage();
            final int separator = LiteralLanguageUtils.directionSeparator(tag);
            if (separator < 0) {
                // Written before base directions were supported, without tag or direction. RDF4J
                // rejects the datatype without them, which would fail every scan reaching the row.
                return VF.createLiteral(ryaType.getData());
            }
            return VF.createLiteral(ryaType.getData(), tag.substring(0, separator),
                    Literal.BaseDirection.fromString(tag.substring(separator)));
        }
        return VF.createLiteral(ryaType.getData(), ryaType.getDataType());
    }

    /**
     * Converts a {@link RyaType} into a {@link Value} representation of the
     * {@code ryaType}.
     * @param ryaType the {@link RyaType} to convert.
     * @return the {@link Value} representation of the {@code ryaType}.
     */
    /**
     * Converts a stored subject or context. Blank nodes are stored, and returned, as IRIs in
     * the {@link RyaSchema#BNODE_NAMESPACE} namespace; the inference engine relies on that.
     */
    public static Resource convertResource(final RyaIRI ryaIri) {
        return toResource(ryaIri.getData());
    }

    private static Resource toResource(final String data) {
        return VF.createIRI(data);
    }

    public static Value convertValue(final RyaType ryaType) {
        if (ryaType instanceof RyaTripleTerm) {
            final RyaTripleTerm term = (RyaTripleTerm) ryaType;
            return VF.createTripleTerm(convertResource(term.getSubject()), convertIRI(term.getPredicate()),
                    convertValue(term.getObject()));
        }
        return (ryaType instanceof RyaIRI || ryaType.getDataType().equals(XMLSchema.ANYURI)) ? toResource(ryaType.getData()) : convertLiteral(ryaType);
    }

    /**
     * Converts a {@link RyaStatement} into a {@link Statement} representation
     * of the {@code ryaStatement}.
     * @param ryaStatement the {@link RyaStatement} to convert.
     * @return the {@link Statement} representation of the {@code ryaStatement}.
     */
    public static Statement convertStatement(final RyaStatement ryaStatement) {
        assert ryaStatement != null;
        if (ryaStatement.getContext() != null) {
            return VF.createStatement(convertResource(ryaStatement.getSubject()),
                    convertIRI(ryaStatement.getPredicate()),
                    convertValue(ryaStatement.getObject()),
                    convertResource(ryaStatement.getContext()));
        } else {
            return VF.createStatement(convertResource(ryaStatement.getSubject()),
                    convertIRI(ryaStatement.getPredicate()),
                    convertValue(ryaStatement.getObject()));
        }
    }
}
