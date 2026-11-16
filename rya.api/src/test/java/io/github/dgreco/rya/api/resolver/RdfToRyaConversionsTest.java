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
package io.github.dgreco.rya.api.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaSchema;
import io.github.dgreco.rya.api.domain.RyaTripleTerm;
import io.github.dgreco.rya.api.utils.LiteralLanguageUtils;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleLiteral;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Literals;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

/**
 * Tests the methods of {@link RdfToRyaConversions}.
 */
public class RdfToRyaConversionsTest {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final Set<String> LANGUAGE_CODES = LanguageCodesTestHelper.getInstance().getLanguageCodes();

    @Test
    public void testConvertLiteral_null() {
        final RyaType ryaType = RdfToRyaConversions.convertLiteral(null);
        assertNull(ryaType);
    }

    @Test
    public void testConvertLiteral_nullDataType() {
        final Literal literal = mock(SimpleLiteral.class);
        final String expectedData = "Ice Cream";
        when(literal.getLabel()).thenReturn(expectedData);
        when(literal.stringValue()).thenReturn(expectedData);
        // Don't think this is possible but test anyways. Need to mock to force this null value.
        when(literal.getDatatype()).thenReturn(null);
        final RyaType ryaType = RdfToRyaConversions.convertLiteral(literal);
        final RyaType expected = new RyaType(XMLSchema.STRING, expectedData);
        assertEquals(expected, ryaType);
        assertNull(ryaType.getLanguage());
    }

    @Test
    public void testConvertLiteral_validLanguage() {
        final String expectedData = "Hello";
        for (final String language : LANGUAGE_CODES) {
            // This only checks the validity of the format. Not that the language tag actually exists.
            assertTrue(Literals.isValidLanguageTag(language));
            final Literal literal = VF.createLiteral(expectedData, language);
            final RyaType ryaType = RdfToRyaConversions.convertLiteral(literal);
            assertEquals(RDF.LANGSTRING, ryaType.getDataType());
            assertEquals(expectedData, ryaType.getData());
            assertEquals(language, ryaType.getLanguage());
            final RyaType expectedRyaType = new RyaType(RDF.LANGSTRING, expectedData, language);
            assertEquals(expectedRyaType, ryaType);
        }
    }

    @Test
    public void testConvertLiteral_undeterminedLanguage() {
        final String expectedData = "Hello";
        final String language = LiteralLanguageUtils.UNDETERMINED_LANGUAGE;
        assertTrue(Literals.isValidLanguageTag(language));
        final Literal literal = VF.createLiteral(expectedData, language);
        final RyaType ryaType = RdfToRyaConversions.convertLiteral(literal);
        assertEquals(RDF.LANGSTRING, ryaType.getDataType());
        assertEquals(expectedData, ryaType.getData());
        final RyaType expectedRyaType = new RyaType(RDF.LANGSTRING, expectedData, language);
        assertEquals(expectedRyaType, ryaType);
        assertEquals(LiteralLanguageUtils.UNDETERMINED_LANGUAGE, ryaType.getLanguage());
    }

    @Test
    public void testConvertLiteral_invalidLanguage() {
        final String expectedData = "Hello";
        final List<String> badLanguages = Lists.newArrayList(
                "bad language",
                "en-",
                "en-US-"
        );
        for (final String badLanguage : badLanguages) {
            // This only checks the validity of the format. Not that the language tag actually exists.
            assertFalse(Literals.isValidLanguageTag(badLanguage));
            final Literal literal = VF.createLiteral(expectedData, badLanguage);
            final RyaType ryaType = RdfToRyaConversions.convertLiteral(literal);
            assertEquals(RDF.LANGSTRING, ryaType.getDataType());
            assertEquals(expectedData, ryaType.getData());
            // Check that the invalid language is replaced with "und"
            assertEquals(LiteralLanguageUtils.UNDETERMINED_LANGUAGE, ryaType.getLanguage());
        }
    }

    @Test
    public void testConvertLiteral_normalString() {
        final String expectedData = "Hello";
        final Literal literal = VF.createLiteral(expectedData);
        final RyaType ryaType = RdfToRyaConversions.convertLiteral(literal);
        assertEquals(XMLSchema.STRING, ryaType.getDataType());
        assertEquals(expectedData, ryaType.getData());
        final RyaType expectedRyaType = new RyaType(XMLSchema.STRING, expectedData);
        assertEquals(expectedRyaType, ryaType);
        assertNull(ryaType.getLanguage());
    }

    @Test
    public void testConvertValue_tripleTerm() {
        final Value inner = VF.createTripleTerm(VF.createBNode("b1"), VF.createIRI("urn:b"), VF.createLiteral("c", "en"));
        final Value tripleTerm = VF.createTripleTerm(VF.createIRI("urn:a"), VF.createIRI("urn:b"), inner);
        final RyaType ryaType = RdfToRyaConversions.convertValue(tripleTerm);
        final RyaTripleTerm outer = (RyaTripleTerm) ryaType;
        assertEquals(new RyaIRI("urn:a"), outer.getSubject());
        assertEquals(new RyaIRI(RyaSchema.BNODE_NAMESPACE + "b1"), ((RyaTripleTerm) outer.getObject()).getSubject());
        // Blank nodes come back as the IRIs they are stored as, like everywhere else.
        assertEquals(VF.createTripleTerm(VF.createIRI("urn:a"), VF.createIRI("urn:b"),
                VF.createTripleTerm(VF.createIRI(RyaSchema.BNODE_NAMESPACE + "b1"), VF.createIRI("urn:b"), VF.createLiteral("c", "en"))),
                RyaToRdfConversions.convertValue(ryaType));
        assertEquals(ryaType, RdfToRyaConversions.convertStatement(
                VF.createStatement(VF.createIRI("urn:s"), VF.createIRI("urn:p"), tripleTerm)).getObject());
    }

    @Test
    public void testConvertLiteral_reservedDatatypeIsRefused() {
        // Its resolver is the triple term's: such a literal would be written as a malformed one.
        assertThrows(UnsupportedValueException.class,
                () -> RdfToRyaConversions.convertValue(VF.createLiteral("\u0002a\u0002b\u0002c", RyaSchema.TRIPLE_TERM)));
    }

    @Test
    public void testConvertLiteral_baseDirection() {
        final Literal literal = VF.createLiteral("مرحبا", "ar", Literal.BaseDirection.RTL);
        final RyaType ryaType = RdfToRyaConversions.convertLiteral(literal);
        assertEquals(RDF.DIRLANGSTRING, ryaType.getDataType());
        assertEquals("مرحبا", ryaType.getData());
        assertEquals("ar--rtl", ryaType.getLanguage());
        assertEquals(ryaType, RdfToRyaConversions.convertValue(literal));
        assertEquals(literal, RyaToRdfConversions.convertValue(ryaType));

        final Literal ltr = VF.createLiteral("Hello", "en-GB", Literal.BaseDirection.LTR);
        assertEquals("en-GB--ltr", RdfToRyaConversions.convertLiteral(ltr).getLanguage());
        assertEquals(ltr, RyaToRdfConversions.convertLiteral(RdfToRyaConversions.convertLiteral(ltr)));
    }
}
