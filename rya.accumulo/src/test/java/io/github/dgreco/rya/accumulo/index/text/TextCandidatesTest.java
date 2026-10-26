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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.index.text.TextCandidates.AllOf;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates.AnyOf;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates.Start;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates.Word;

class TextCandidatesTest {

    private static Optional<TextCandidates> of(final String query) {
        return TextCandidates.of(TextQuery.parse(query));
    }

    @Test
    void rules() {
        assertEquals(Optional.of(new Word("a")), of("a"));
        assertEquals(Optional.of(new Start("abc")), of("abc*"));
        assertEquals(Optional.empty(), of("ab*"), "prefixes under 3 characters scan too much");
        assertEquals(Optional.of(new AllOf(List.of(new Word("new"), new Word("york")))), of("\"new york\""));
        assertEquals(Optional.of(new Word("a")), of("a NOT b"));
        assertEquals(Optional.empty(), of("NOT b"));
        assertEquals(Optional.empty(), of("a OR NOT b"));
        assertEquals(Optional.of(new AnyOf(List.of(new Word("a"), new Word("b"), new Word("c")))), of("a OR (b OR c)"));
        assertEquals(Optional.of(new AllOf(List.of(new Word("a"), new Word("b")))), of("a (b NOT c) \"a b\""));
        assertEquals(Optional.of(new AllOf(List.of(new Word("x"), new Word("y")))),
                TextCandidates.allOf(List.of(TextQuery.parse("x"), TextQuery.parse("y NOT z"))));
    }

    /** The index may return extra candidates, never miss a match: matches ⇒ candidate. */
    @Test
    void everyMatchIsACandidate() {
        final List<String> vocabulary = List.of("alpha", "alphabet", "beta", "bet", "gamma", "delta", "al", "be");
        final Random random = new Random(42);
        int checked = 0;
        for (int round = 0; round < 3000; round++) {
            final String query = randomQuery(random, vocabulary, 3);
            final TextQuery parsed;
            try {
                parsed = TextQuery.parse(query);
            } catch (final TextQueryException e) {
                continue;
            }
            final Optional<TextCandidates> candidates = TextCandidates.of(parsed);
            for (int d = 0; d < 20; d++) {
                final List<String> words = new ArrayList<>();
                for (int i = random.nextInt(6); i > 0; i--) {
                    words.add(vocabulary.get(random.nextInt(vocabulary.size())));
                }
                final String text = String.join(" ", words);
                if (parsed.matches(text) && candidates.isPresent()) {
                    final Set<String> terms = TextAnalyzer.terms(text);
                    assertTrue(candidates.get().satisfiedBy(terms), query + " on \"" + text + "\"");
                    checked++;
                }
            }
        }
        assertTrue(checked > 1000, "too few matching cases: " + checked);
    }

    private static String randomQuery(final Random r, final List<String> vocabulary, final int depth) {
        final int pick = depth == 0 ? r.nextInt(3) : r.nextInt(7);
        final String word = vocabulary.get(r.nextInt(vocabulary.size()));
        return switch (pick) {
            case 0 -> word;
            case 1 -> word.substring(0, 1 + r.nextInt(word.length())) + "*";
            case 2 -> "\"" + word + " " + vocabulary.get(r.nextInt(vocabulary.size())) + "\"";
            case 3 -> "(" + randomQuery(r, vocabulary, depth - 1) + " AND " + randomQuery(r, vocabulary, depth - 1) + ")";
            case 4 -> "(" + randomQuery(r, vocabulary, depth - 1) + " OR " + randomQuery(r, vocabulary, depth - 1) + ")";
            case 5 -> "NOT " + randomQuery(r, vocabulary, depth - 1);
            default -> randomQuery(r, vocabulary, depth - 1) + " " + randomQuery(r, vocabulary, depth - 1);
        };
    }
}
