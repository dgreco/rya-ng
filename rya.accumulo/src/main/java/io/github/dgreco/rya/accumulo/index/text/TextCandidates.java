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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the index can look up for a text query: a boolean expression of words and word prefixes
 * whose matches include every text that satisfies the query (and possibly more). Phrases become
 * the AND of their words and {@code NOT} parts are dropped; the {@code text:matches} filter, which
 * stays in the query, removes the extra candidates.
 * <p>
 * A query has no candidate expression (it can't use the index) when some text could satisfy it
 * without containing any particular word: a pure {@code NOT}, an {@code OR} with such a branch,
 * or a prefix shorter than {@value #MIN_PREFIX} characters.
 */
public sealed interface TextCandidates {
    /** Shortest prefix the index looks up; shorter ones would scan too much. */
    int MIN_PREFIX = 3;

    /** Texts containing {@code word}. */
    record Word(String word) implements TextCandidates {
    }

    /** Texts containing a word that starts with {@code prefix}. */
    record Start(String prefix) implements TextCandidates {
    }

    record AllOf(List<TextCandidates> operands) implements TextCandidates {
        public AllOf {
            operands = List.copyOf(operands);
        }
    }

    record AnyOf(List<TextCandidates> operands) implements TextCandidates {
        public AnyOf {
            operands = List.copyOf(operands);
        }
    }

    /** The candidates of a query, or empty when the index can't narrow it down. */
    static Optional<TextCandidates> of(final TextQuery query) {
        return Optional.ofNullable(candidates(query));
    }

    /** The candidates of several queries that must all match the same text. */
    static Optional<TextCandidates> allOf(final List<TextQuery> queries) {
        return of(queries.size() == 1 ? queries.get(0) : new TextQuery.And(queries));
    }

    /** Whether a text whose distinct words are {@code words} is a candidate. */
    default boolean satisfiedBy(final Set<String> words) {
        return switch (this) {
            case Word w -> words.contains(w.word());
            case Start s -> words.stream().anyMatch(x -> x.startsWith(s.prefix()));
            case AllOf a -> a.operands().stream().allMatch(o -> o.satisfiedBy(words));
            case AnyOf o -> o.operands().stream().anyMatch(x -> x.satisfiedBy(words));
        };
    }

    /** Null means "every text" (not indexable). */
    private static TextCandidates candidates(final TextQuery q) {
        return switch (q) {
            case TextQuery.Term t -> new Word(t.word());
            case TextQuery.Prefix p -> p.prefix().codePointCount(0, p.prefix().length()) < MIN_PREFIX
                    ? null : new Start(p.prefix());
            case TextQuery.Phrase ph -> conjunction(ph.words().stream().map(w -> (TextCandidates) new Word(w)).toList());
            case TextQuery.Not n -> null;
            case TextQuery.And a -> {
                final List<TextCandidates> kept = new ArrayList<>();
                for (final TextQuery operand : a.operands()) {
                    final TextCandidates c = candidates(operand);
                    if (c != null) {
                        kept.add(c);
                    }
                }
                yield kept.isEmpty() ? null : conjunction(kept);
            }
            case TextQuery.Or o -> {
                final List<TextCandidates> branches = new ArrayList<>();
                for (final TextQuery operand : o.operands()) {
                    final TextCandidates c = candidates(operand);
                    if (c == null) {
                        yield null;
                    }
                    branches.add(c);
                }
                yield disjunction(branches);
            }
        };
    }

    private static TextCandidates conjunction(final List<TextCandidates> operands) {
        final Set<TextCandidates> flat = new LinkedHashSet<>();
        for (final TextCandidates c : operands) {
            if (c instanceof AllOf a) {
                flat.addAll(a.operands());
            } else {
                flat.add(c);
            }
        }
        return flat.size() == 1 ? flat.iterator().next() : new AllOf(new ArrayList<>(flat));
    }

    private static TextCandidates disjunction(final List<TextCandidates> operands) {
        final Set<TextCandidates> flat = new LinkedHashSet<>();
        for (final TextCandidates c : operands) {
            if (c instanceof AnyOf o) {
                flat.addAll(o.operands());
            } else {
                flat.add(c);
            }
        }
        return flat.size() == 1 ? flat.iterator().next() : new AnyOf(new ArrayList<>(flat));
    }
}
