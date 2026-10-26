package io.github.dgreco.rya.api.resolver;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;
import io.github.dgreco.rya.api.domain.RyaType;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * Row keys are split on 0x00 (between subject, predicate and object) and 0x01 (before the
 * type marker). Every encoded value must be free of both bytes and must round-trip.
 */
public class TypeEncodingDelimiterTest {
    private static final IRI CUSTOM = SimpleValueFactory.getInstance().createIRI("urn:custom#type");

    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of(XMLSchema.STRING, "", ""),
                Arguments.of(XMLSchema.STRING, "hello world", "hello world"),
                Arguments.of(XMLSchema.STRING, "unicode ✓ 日本 😀", "unicode ✓ 日本 😀"),
                Arguments.of(XMLSchema.STRING, "tab\tnew\nline", "tab\tnew\nline"),
                Arguments.of(XMLSchema.ANYURI, "urn:a", "urn:a"),
                Arguments.of(XMLSchema.ANYURI, "http://example.org/a/b#c?d=e", "http://example.org/a/b#c?d=e"),
                Arguments.of(XMLSchema.INTEGER, "0", "0"),
                Arguments.of(XMLSchema.INTEGER, "-1", "-1"),
                Arguments.of(XMLSchema.INTEGER, "+42", "42"),
                Arguments.of(XMLSchema.INTEGER, "123456789012345678901234567890", "123456789012345678901234567890"),
                Arguments.of(XMLSchema.INTEGER, "-98765432109876543210", "-98765432109876543210"),
                Arguments.of(XMLSchema.INT, "0", "0"),
                Arguments.of(XMLSchema.INT, "-1", "-1"),
                Arguments.of(XMLSchema.INT, String.valueOf(Integer.MIN_VALUE), String.valueOf(Integer.MIN_VALUE)),
                Arguments.of(XMLSchema.INT, String.valueOf(Integer.MAX_VALUE), String.valueOf(Integer.MAX_VALUE)),
                Arguments.of(XMLSchema.LONG, "0", "0"),
                Arguments.of(XMLSchema.LONG, "-1", "-1"),
                Arguments.of(XMLSchema.LONG, String.valueOf(Long.MIN_VALUE), String.valueOf(Long.MIN_VALUE)),
                Arguments.of(XMLSchema.LONG, String.valueOf(Long.MAX_VALUE), String.valueOf(Long.MAX_VALUE)),
                Arguments.of(XMLSchema.SHORT, String.valueOf(Short.MIN_VALUE), String.valueOf(Short.MIN_VALUE)),
                Arguments.of(XMLSchema.SHORT, String.valueOf(Short.MAX_VALUE), String.valueOf(Short.MAX_VALUE)),
                Arguments.of(XMLSchema.BYTE, "-128", "-128"),
                Arguments.of(XMLSchema.BYTE, "127", "127"),
                Arguments.of(XMLSchema.BYTE, "0", "0"),
                Arguments.of(XMLSchema.DOUBLE, "0.0", "0.0"),
                Arguments.of(XMLSchema.DOUBLE, "-1.5", "-1.5"),
                Arguments.of(XMLSchema.DOUBLE, "NaN", "NaN"),
                Arguments.of(XMLSchema.DOUBLE, "INF", "INF"),
                Arguments.of(XMLSchema.DOUBLE, "-INF", "-INF"),
                Arguments.of(XMLSchema.DOUBLE, String.valueOf(Double.MAX_VALUE), String.valueOf(Double.MAX_VALUE)),
                Arguments.of(XMLSchema.DOUBLE, String.valueOf(Double.MIN_VALUE), String.valueOf(Double.MIN_VALUE)),
                Arguments.of(XMLSchema.FLOAT, "-1.5", "-1.5"),
                Arguments.of(XMLSchema.FLOAT, "INF", "INF"),
                Arguments.of(XMLSchema.FLOAT, "NaN", "NaN"),
                Arguments.of(XMLSchema.BOOLEAN, "true", "true"),
                Arguments.of(XMLSchema.BOOLEAN, "false", "false"),
                Arguments.of(XMLSchema.BOOLEAN, "1", "true"),
                Arguments.of(XMLSchema.BOOLEAN, "0", "false"),
                Arguments.of(XMLSchema.DATETIME, "1970-01-01T00:00:00.000Z", "1970-01-01T00:00:00.000Z"),
                Arguments.of(XMLSchema.DATETIME, "2026-10-25T12:30:45.123Z", "2026-10-25T12:30:45.123Z"),
                Arguments.of(CUSTOM, "custom value", "custom value"));
    }

    @ParameterizedTest
    @MethodSource("values")
    public void encodingHasNoDelimitersAndRoundTrips(final IRI datatype, final String data, final String expected) throws Exception {
        final RyaContext context = RyaContext.getInstance();
        final RyaType in = new RyaType(datatype, data);

        final byte[][] parts = context.serializeType(in);
        assertNoDelimiter(parts[0], datatype + " " + data);

        final RyaType out = context.deserialize(context.serialize(in));
        assertEquals(datatype, out.getDataType(), "datatype must survive the round trip");
        assertEquals(expected, out.getData());
    }

    @Test
    public void languageTaggedStringRoundTrips() throws Exception {
        final RyaContext context = RyaContext.getInstance();
        final RyaType in = new RyaType(RDF.LANGSTRING, "bonjour", "fr");
        assertNoDelimiter(context.serializeType(in)[0], "langString");
        final RyaType out = context.deserialize(context.serialize(in));
        assertEquals("bonjour", out.getData());
        assertEquals("fr", out.getLanguage());
    }

    @Test
    public void reservedControlCharactersAreRejected() {
        final RyaContext context = RyaContext.getInstance();
        assertThrows(RyaTypeResolverException.class,
                () -> context.serializeType(new RyaType(XMLSchema.STRING, "bad\u0000value")));
        assertThrows(RyaTypeResolverException.class,
                () -> context.serializeType(new RyaType(XMLSchema.STRING, "bad\u0001value")));
        assertThrows(RyaTypeResolverException.class,
                () -> context.serializeType(new RyaType(CUSTOM, "bad\u0000value")));
    }

    private static void assertNoDelimiter(final byte[] bytes, final String what) {
        for (final byte b : bytes) {
            assertFalse(b == RdfCloudTripleStoreConstants.DELIM_BYTE || b == RdfCloudTripleStoreConstants.TYPE_DELIM_BYTE,
                    "encoded " + what + " contains a row delimiter byte");
        }
    }
}
