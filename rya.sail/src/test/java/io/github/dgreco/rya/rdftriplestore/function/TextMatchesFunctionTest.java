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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;
import org.junit.jupiter.api.Test;

class TextMatchesFunctionTest {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private final TextMatchesFunction f = new TextMatchesFunction();

    private boolean matches(final org.eclipse.rdf4j.model.Value text, final String query) {
        return ((org.eclipse.rdf4j.model.Literal) f.evaluate(VF, text, VF.createLiteral(query))).booleanValue();
    }

    @Test
    void isRegisteredWithRdf4j() {
        assertTrue(FunctionRegistry.getInstance().has(RyaText.MATCHES.stringValue()));
        assertEquals("urn:rya-ng:text#matches", f.getURI());
    }

    @Test
    void matchesStringAndLanguageLiterals() {
        assertTrue(matches(VF.createLiteral("The Quick brown fox"), "quick AND fox"));
        assertTrue(matches(VF.createLiteral("Café crème", "fr"), "cafe"));
        assertTrue(!matches(VF.createLiteral("The quick brown fox"), "\"fox brown\""));
    }

    @Test
    void otherValuesAreEvaluationErrors() {
        assertThrows(ValueExprEvaluationException.class, () -> matches(VF.createLiteral("fox", XSD.TOKEN), "fox"));
        assertThrows(ValueExprEvaluationException.class, () -> matches(VF.createIRI("urn:fox"), "fox"));
        assertThrows(ValueExprEvaluationException.class, () -> matches(VF.createLiteral("fox"), "fox AND"));
        assertThrows(ValueExprEvaluationException.class, () -> f.evaluate(VF, VF.createLiteral("fox")));
        assertThrows(ValueExprEvaluationException.class,
                () -> f.evaluate(VF, VF.createLiteral("fox"), VF.createLiteral(3)));
    }
}
