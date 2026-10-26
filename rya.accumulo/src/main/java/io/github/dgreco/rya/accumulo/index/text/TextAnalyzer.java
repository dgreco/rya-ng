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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits text into the words the full-text index stores and searches for. The index, the query
 * parser and the {@code text:matches} function all use this class, so a word found in a literal
 * is always spelled the same way in the index and in a query.
 * <p>
 * Words are compared ignoring case and accents: the text is lowercased, decomposed, stripped of
 * combining marks and a few letters are folded ({@code ß} → {@code ss}, {@code ø} → {@code o}, …).
 * A word is a run of letters and digits; each Han or Hiragana character is a word on its own.
 * Words are cut to {@value #MAX_TERM} code points.
 * <p>
 * The rules are versioned ({@link #VERSION}): changing them changes the index keys, so the
 * stored index definition changes and the index has to be rebuilt.
 */
public final class TextAnalyzer {
    /** Part of the stored index definition. Bump it whenever the rules below change. */
    public static final String VERSION = "text/1";
    /** Longest word kept, in code points. */
    public static final int MAX_TERM = 64;

    private TextAnalyzer() {
    }

    /** The words of {@code text} in order, repeats included (used for phrase matching). */
    public static List<String> tokens(final String text) {
        final String normalized = normalize(text);
        final List<String> tokens = new ArrayList<>();
        final StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < normalized.length()) {
            final int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (isSingleCharacterWord(cp)) {
                flush(word, tokens);
                tokens.add(new String(Character.toChars(cp)));
            } else if (isWordPart(cp)) {
                word.appendCodePoint(cp);
            } else {
                flush(word, tokens);
            }
        }
        flush(word, tokens);
        return tokens;
    }

    /** The distinct words of {@code text}: the terms the index stores for it. */
    public static Set<String> terms(final String text) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tokens(text)));
    }

    /** Cuts a word to {@value #MAX_TERM} code points without splitting a surrogate pair. */
    public static String cut(final String word) {
        if (word.codePointCount(0, word.length()) <= MAX_TERM) {
            return word;
        }
        return word.substring(0, word.offsetByCodePoints(0, MAX_TERM));
    }

    /** Lowercased, accent-free form of {@code text}. */
    static String normalize(final String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        // Lowercasing can produce new combining marks (İ → i + dot), so decompose again.
        s = Normalizer.normalize(s, Normalizer.Form.NFKD);
        final StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            final int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            final int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                continue;
            }
            final String folded = fold(cp);
            if (folded != null) {
                out.append(folded);
            } else {
                out.appendCodePoint(cp);
            }
        }
        // Recompose what decomposition split apart without being an accent (Hangul syllables).
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }

    private static String fold(final int cp) {
        return switch (cp) {
            case 'ß' -> "ss";
            case 'æ' -> "ae";
            case 'œ' -> "oe";
            case 'ø' -> "o";
            case 'đ', 'ð' -> "d";
            case 'ħ' -> "h";
            case 'ı' -> "i";
            case 'ł' -> "l";
            case 'þ' -> "th";
            case 'ς' -> "σ";
            default -> null;
        };
    }

    private static boolean isSingleCharacterWord(final int cp) {
        return Character.isIdeographic(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA;
    }

    private static boolean isWordPart(final int cp) {
        return Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.COMBINING_SPACING_MARK;
    }

    private static void flush(final StringBuilder word, final List<String> tokens) {
        if (word.length() > 0) {
            tokens.add(cut(word.toString()));
            word.setLength(0);
        }
    }
}
