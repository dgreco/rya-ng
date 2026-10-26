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
package io.github.dgreco.rya.accumulo.index.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.index.text.TextQuery.And;
import io.github.dgreco.rya.accumulo.index.text.TextQuery.Not;
import io.github.dgreco.rya.accumulo.index.text.TextQuery.Or;
import io.github.dgreco.rya.accumulo.index.text.TextQuery.Phrase;
import io.github.dgreco.rya.accumulo.index.text.TextQuery.Prefix;
import io.github.dgreco.rya.accumulo.index.text.TextQuery.Term;

class TextQueryTest {

    @Test
    void precedenceIsNotThenAndThenOr() {
        assertEquals(new Or(List.of(new Term("a"), new And(List.of(new Term("b"), new Not(new Term("c")))))),
                TextQuery.parse("a OR b AND NOT c"));
        assertEquals(new And(List.of(new Term("a"), new Term("b"))), TextQuery.parse("a b"));
        assertEquals(new And(List.of(new Or(List.of(new Term("a"), new Term("b"))), new Term("c"))),
                TextQuery.parse("(a OR b) c"));
        assertEquals(new Not(new Not(new Term("a"))), TextQuery.parse("NOT NOT a"));
    }

    @Test
    void operatorsAreUpperCaseOnly() {
        assertEquals(new And(List.of(new Term("cats"), new Term("and"), new Term("dogs"))),
                TextQuery.parse("cats and dogs"));
        assertEquals(new And(List.of(new Term("not"), new Term("or"))), TextQuery.parse("not or"));
    }

    @Test
    void wordsGoThroughTheAnalyzer() {
        assertEquals(new Term("cafe"), TextQuery.parse("CAFÉ"));
        assertEquals(new Phrase(List.of("e", "mail")), TextQuery.parse("e-mail"));
        assertEquals(new Phrase(List.of("new", "york")), TextQuery.parse("\"New  York\""));
        assertEquals(new Term("york"), TextQuery.parse("\"york\""));
        assertEquals(new Prefix("cafe"), TextQuery.parse("Café*"));
        // Punctuation-only words are ignored.
        assertEquals(new Term("a"), TextQuery.parse("a -"));
    }

    @Test
    void malformedQueriesAreRejected() {
        for (final String bad : List.of("", "   ", "-", "()", "(a", "a)", "\"a", "\"\"", "NOT", "a NOT",
                "a OR", "OR a", "AND a", "a AND", "a AND AND b", "a**", "*a", "a*b", "*", "e-ma*")) {
            assertThrows(TextQueryException.class, () -> TextQuery.parse(bad), bad);
        }
    }

    @Test
    void matchingFollowsTheOperators() {
        final String text = "The quick brown fox jumps over the lazy dog";
        assertTrue(TextQuery.parse("quick fox").matches(text));
        assertTrue(TextQuery.parse("QUICK AND (cat OR dog)").matches(text));
        assertFalse(TextQuery.parse("quick AND cat").matches(text));
        assertTrue(TextQuery.parse("\"brown fox\"").matches(text));
        assertFalse(TextQuery.parse("\"fox brown\"").matches(text));
        assertTrue(TextQuery.parse("jum*").matches(text));
        assertFalse(TextQuery.parse("jumped*").matches(text));
        assertTrue(TextQuery.parse("fox NOT cat").matches(text));
        assertFalse(TextQuery.parse("fox NOT dog").matches(text));
        assertTrue(TextQuery.parse("NOT cat").matches(text));
        assertTrue(TextQuery.parse("e-mail").matches("send an E-Mail"));
        assertFalse(TextQuery.parse("e-mail").matches("e and mail"));
    }
}
