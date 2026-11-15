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
package io.github.dgreco.rya.api.resolver.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaSchema;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaTripleTerm;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.resolver.RyaContext;
import io.github.dgreco.rya.api.resolver.RyaTypeResolverException;
import io.github.dgreco.rya.api.resolver.RyaTypeResolverMapping;
import io.github.dgreco.rya.api.resolver.UnsupportedValueException;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;
import io.github.dgreco.rya.api.resolver.triple.TripleRowResolver;
import io.github.dgreco.rya.api.resolver.triple.impl.WholeRowHashedTripleResolver;
import io.github.dgreco.rya.api.resolver.triple.impl.WholeRowTripleResolver;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;
import org.junit.jupiter.api.Test;

public class TripleTermResolverTest {
    private static final RyaContext CONTEXT = RyaContext.getInstance();
    private static final RyaIRI S = new RyaIRI("urn:s");
    private static final RyaIRI P = new RyaIRI("urn:p");

    private static List<RyaType> objects() {
        final RyaTripleTerm simple = new RyaTripleTerm(S, P, new RyaIRI("urn:o"));
        return List.of(
                new RyaIRI("urn:o"),
                new RyaIRI(RyaSchema.BNODE_NAMESPACE + "b1"),
                new RyaType("plain"),
                new RyaType(""),
                new RyaType("the bytes \u0002 and \u0003, and \u0004\u0005\u0006 too"),
                new RyaType(XMLSchema.INTEGER, "42"),
                new RyaType(XMLSchema.DOUBLE, "-1.5"),
                new RyaType(XMLSchema.DATETIME, "2026-10-25T12:30:45.123Z"),
                new RyaType(RDF.LANGSTRING, "bonjour", "fr"),
                new RyaType(RDF.DIRLANGSTRING, "user@example.org", "ar--rtl"),
                new RyaType(SimpleValueFactory.getInstance().createIRI("urn:custom#type"), "custom \u0002 value"),
                simple,
                new RyaTripleTerm(S, P, new RyaTripleTerm(new RyaIRI("urn:s2"), P, simple)));
    }

    @Test
    public void roundTripsToTheSameBytes() throws Exception {
        for (final RyaType object : objects()) {
            final RyaTripleTerm term = new RyaTripleTerm(S, P, object);
            final byte[][] parts = CONTEXT.serializeType(term);
            for (final byte b : parts[0]) {
                assertFalse(b == 0 || b == 1, "reserved byte in " + term);
            }
            assertArrayEquals(new byte[] {1, 14}, parts[1]);

            final byte[] bytes = CONTEXT.serialize(term);
            final RyaTripleTerm read = (RyaTripleTerm) CONTEXT.deserialize(bytes);
            assertEquals(term, read);
            assertEquals(S, read.getSubject());
            assertEquals(P, read.getPredicate());
            assertEquals(object, read.getObject());
            assertEquals(object.getClass(), read.getObject().getClass());
            // Exact deletes, batched lookups and the index hash all rebuild the key from what was read.
            assertArrayEquals(bytes, CONTEXT.serialize(read));
            assertArrayEquals(bytes, CONTEXT.serialize(new RyaTripleTerm(read.getSubject(), read.getPredicate(), read.getObject())));
        }
    }

    @Test
    public void layout() throws Exception {
        final byte[] bytes = CONTEXT.serialize(new RyaTripleTerm(S, P, new RyaType("a\u0002b")));
        // 02 urn:s 02 urn:p 02 a (03 06) b (03 05) (03 03)   01 0E: the inner string's suffix is 01 03.
        assertEquals("02" + hex("urn:s") + "02" + hex("urn:p") + "02" + hex("a") + "0306" + hex("b") + "0305" + "0303" + "010e",
                HexFormat.of().formatHex(bytes));
    }

    @Test
    public void rowsInEveryLayout() throws Exception {
        final RyaTripleTerm term = new RyaTripleTerm(new RyaIRI("urn:a"), new RyaIRI("urn:b"),
                new RyaTripleTerm(S, P, new RyaType(RDF.LANGSTRING, "bonjour", "fr")));
        final RyaStatement statement = new RyaStatement(new RyaIRI("urn:r"), new RyaIRI(RDF.NAMESPACE + "reifies"), term);
        for (final TripleRowResolver resolver : List.of(new WholeRowTripleResolver(), new WholeRowHashedTripleResolver())) {
            final Map<TABLE_LAYOUT, TripleRow> rows = resolver.serialize(statement);
            for (final TABLE_LAYOUT layout : TABLE_LAYOUT.values()) {
                final RyaStatement read = resolver.deserialize(layout, rows.get(layout));
                assertEquals(statement.getSubject(), read.getSubject());
                assertEquals(statement.getPredicate(), read.getPredicate());
                assertEquals(term, read.getObject());
                assertTrue(read.getObject() instanceof RyaTripleTerm);
            }
        }
    }

    @Test
    public void aTripleTermIsNotTheValueWithTheSameData() throws Exception {
        final RyaTripleTerm term = new RyaTripleTerm(S, P, new RyaIRI("urn:o"));
        final RyaType string = new RyaType(term.getData());
        assertNotEquals(term, string);
        final byte[] termBytes = CONTEXT.serialize(term);
        final byte[] stringBytes = CONTEXT.serialize(string);
        assertFalse(java.util.Arrays.equals(termBytes, stringBytes));
        assertFalse(CONTEXT.deserialize(stringBytes) instanceof RyaTripleTerm);
    }

    @Test
    public void termsAboutASubjectShareAPrefix() {
        final String data = new RyaTripleTerm(S, P, new RyaType("o")).getData();
        assertTrue(data.startsWith(TripleTermResolver.prefix(null, null)));
        assertTrue(data.startsWith(TripleTermResolver.prefix(S, null)));
        assertTrue(data.startsWith(TripleTermResolver.prefix(S, P)));
        // "urn:s" is not a prefix of "urn:s2": the separator ends the subject.
        assertFalse(new RyaTripleTerm(new RyaIRI("urn:s2"), P, new RyaType("o")).getData()
                .startsWith(TripleTermResolver.prefix(S, null)));
    }

    @Test
    public void malformedBytesAreRefused() {
        for (final String hex : List.of("010e", "0261010e", "026102620263" + "0264" + "010e", "0261026202" + "0309" + "010e")) {
            assertThrows(RyaTypeResolverException.class, () -> CONTEXT.deserialize(HexFormat.of().parseHex(hex)), hex);
        }
        assertThrows(RyaTypeResolverException.class, () -> CONTEXT.serialize(new RyaType(RyaSchema.TRIPLE_TERM, "not one")));
    }

    @Test
    public void partsThatCannotBeEncodedAreRefused() {
        assertThrows(UnsupportedValueException.class, () -> new RyaTripleTerm(S, P, null));
        assertThrows(UnsupportedValueException.class, () -> new RyaTripleTerm(S, P, new RyaType(XMLSchema.INTEGER, "not a number")));
    }

    @Test
    public void aTakenMarkerIsRefused() {
        final RyaTypeResolverImpl squatter = new RyaTypeResolverImpl((byte) TripleTermResolver.TRIPLE_TERM_MARKER,
                SimpleValueFactory.getInstance().createIRI("urn:other#type"));
        assertThrows(IllegalArgumentException.class, () -> CONTEXT.addRyaTypeResolverMapping(new RyaTypeResolverMapping(squatter)));
        assertTrue(CONTEXT.retrieveResolver((byte) TripleTermResolver.TRIPLE_TERM_MARKER) instanceof TripleTermResolver);
    }

    private static String hex(final String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }
}
