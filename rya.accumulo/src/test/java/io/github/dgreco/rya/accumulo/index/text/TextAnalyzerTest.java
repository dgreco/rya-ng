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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TextAnalyzerTest {

    @Test
    void splitsOnPunctuationAndLowercases() {
        assertEquals(List.of("hello", "world", "don", "t", "3", "14", "e", "mail"),
                TextAnalyzer.tokens("Hello, WORLD! Don't 3.14 e-mail"));
    }

    @Test
    void removesAccentsAndFoldsLetters() {
        assertEquals(List.of("cafe", "creme", "brulee"), TextAnalyzer.tokens("Café Crème Brûlée"));
        assertEquals(List.of("strasse", "aeble", "oere", "o", "lodz", "thorn"),
                TextAnalyzer.tokens("Straße Æble œre Ø Łódź Þorn"));
        assertEquals(List.of("istanbul", "i"), TextAnalyzer.tokens("İstanbul ı"));
        // Final sigma and sigma are the same letter.
        assertEquals(TextAnalyzer.tokens("οδος"), TextAnalyzer.tokens("ΟΔΟΣ"));
    }

    @Test
    void compatibilityCharactersBecomePlainLetters() {
        assertEquals(List.of("file", "abc"), TextAnalyzer.tokens("ﬁle ＡＢＣ"));
    }

    @Test
    void hanAndHiraganaAreOneWordPerCharacter() {
        assertEquals(List.of("東", "京", "tokyo"), TextAnalyzer.tokens("東京 Tokyo"));
        assertEquals(List.of("こ", "ん"), TextAnalyzer.tokens("こん"));
        // Hangul is kept as whole words (decomposition is undone).
        assertEquals(List.of("한국어"), TextAnalyzer.tokens("한국어"));
    }

    @Test
    void cutsLongWordsWithoutSplittingSurrogates() {
        final String longWord = "a".repeat(100);
        assertEquals(TextAnalyzer.MAX_TERM, TextAnalyzer.tokens(longWord).get(0).length());
        // 𝐱 (U+1D431) is a letter outside the BMP: two chars per code point.
        final String wide = "𝐱".repeat(70);
        final String cut = TextAnalyzer.tokens(wide).get(0);
        assertEquals(TextAnalyzer.MAX_TERM, cut.codePointCount(0, cut.length()));
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }

    @Test
    void termsAreDistinctAndHaveNoDelimiterBytes() {
        assertEquals(Set.of("a", "b"), TextAnalyzer.terms("a b a B"));
        for (final String t : TextAnalyzer.terms("x\u0000y\u0001z tab\there")) {
            assertTrue(t.indexOf('\u0000') < 0 && t.indexOf('\u0001') < 0, t);
        }
        assertTrue(TextAnalyzer.terms("  ...  ").isEmpty());
    }
}
