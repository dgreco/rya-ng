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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A parsed full-text query and its exact meaning.
 * <p>
 * Syntax: words, {@code "exact phrases"} and {@code prefix*} wildcards, combined with
 * {@code AND} (or just a space), {@code OR}, {@code NOT} and parentheses. Operators are
 * recognised only in upper case; {@code NOT} binds tighter than {@code AND}, which binds tighter
 * than {@code OR}. Words are compared through {@link TextAnalyzer}, so case and accents don't
 * matter. A word that the analyzer splits into several words (like {@code e-mail}) is a phrase.
 */
public sealed interface TextQuery {

    /** A word that must appear. */
    record Term(String word) implements TextQuery {
    }

    /** Some word must start with {@code prefix}. */
    record Prefix(String prefix) implements TextQuery {
    }

    /** The words must appear one after the other. */
    record Phrase(List<String> words) implements TextQuery {
        public Phrase {
            words = List.copyOf(words);
        }
    }

    record And(List<TextQuery> operands) implements TextQuery {
        public And {
            operands = List.copyOf(operands);
        }
    }

    record Or(List<TextQuery> operands) implements TextQuery {
        public Or {
            operands = List.copyOf(operands);
        }
    }

    record Not(TextQuery operand) implements TextQuery {
    }

    /**
     * Parses a query.
     *
     * @throws TextQueryException if the query is malformed or has nothing to search for
     */
    static TextQuery parse(final String query) {
        return new Parser(query).parse();
    }

    /** Whether {@code text} satisfies the query. */
    default boolean matches(final String text) {
        return matches(TextAnalyzer.tokens(text));
    }

    /** Whether a text with these words (from {@link TextAnalyzer#tokens}) satisfies the query. */
    default boolean matches(final List<String> tokens) {
        return Matcher.matches(this, tokens, new HashSet<>(tokens));
    }

    /** Evaluates a query against one text. */
    final class Matcher {
        private Matcher() {
        }

        static boolean matches(final TextQuery q, final List<String> tokens, final Set<String> words) {
            return switch (q) {
                case Term t -> words.contains(t.word());
                case Prefix p -> words.stream().anyMatch(w -> w.startsWith(p.prefix()));
                case Phrase ph -> containsSequence(tokens, ph.words());
                case And a -> a.operands().stream().allMatch(o -> matches(o, tokens, words));
                case Or o -> o.operands().stream().anyMatch(x -> matches(x, tokens, words));
                case Not n -> !matches(n.operand(), tokens, words);
            };
        }

        private static boolean containsSequence(final List<String> tokens, final List<String> phrase) {
            outer:
            for (int start = 0; start + phrase.size() <= tokens.size(); start++) {
                for (int i = 0; i < phrase.size(); i++) {
                    if (!tokens.get(start + i).equals(phrase.get(i))) {
                        continue outer;
                    }
                }
                return true;
            }
            return false;
        }
    }

    /** Recursive-descent parser: {@code or := and ("OR" and)*}, {@code and := unary ("AND"? unary)*}. */
    final class Parser {
        private enum Kind { WORD, PHRASE, OPEN, CLOSE, AND, OR, NOT, END }

        private record Token(Kind kind, String text) {
        }

        private final String source;
        private final List<Token> tokens;
        private int pos;

        Parser(final String source) {
            this.source = source;
            this.tokens = lex(source);
        }

        TextQuery parse() {
            final TextQuery q = or();
            if (peek().kind() != Kind.END) {
                throw error("unexpected '" + peek().text() + "'");
            }
            if (q == null) {
                throw error("nothing to search for");
            }
            return q;
        }

        private TextQuery or() {
            final List<TextQuery> operands = new ArrayList<>();
            add(operands, and());
            while (peek().kind() == Kind.OR) {
                next();
                final TextQuery right = and();
                if (right == null) {
                    throw error("OR needs a word on both sides");
                }
                if (operands.isEmpty()) {
                    throw error("OR needs a word on both sides");
                }
                add(operands, right);
            }
            return operands.isEmpty() ? null : operands.size() == 1 ? operands.get(0) : new Or(operands);
        }

        private TextQuery and() {
            final List<TextQuery> operands = new ArrayList<>();
            boolean pendingAnd = false;
            while (true) {
                final Kind kind = peek().kind();
                if (kind == Kind.AND) {
                    next();
                    if (operands.isEmpty() || pendingAnd) {
                        throw error("AND needs a word on both sides");
                    }
                    pendingAnd = true;
                    continue;
                }
                if (kind == Kind.END || kind == Kind.CLOSE || kind == Kind.OR) {
                    if (pendingAnd) {
                        throw error("AND needs a word on both sides");
                    }
                    break;
                }
                final TextQuery operand = unary();
                pendingAnd = false;
                add(operands, operand);
            }
            return operands.isEmpty() ? null : operands.size() == 1 ? operands.get(0) : new And(operands);
        }

        /** Returns null for a word the analyzer drops entirely (punctuation). */
        private TextQuery unary() {
            final Token t = next();
            switch (t.kind()) {
                case NOT -> {
                    final Kind following = peek().kind();
                    if (following == Kind.END || following == Kind.CLOSE || following == Kind.OR
                            || following == Kind.AND) {
                        throw error("NOT needs a word after it");
                    }
                    final TextQuery operand = unary();
                    if (operand == null) {
                        throw error("NOT needs a word after it");
                    }
                    return new Not(operand);
                }
                case OPEN -> {
                    final TextQuery inner = or();
                    if (next().kind() != Kind.CLOSE) {
                        throw error("missing ')'");
                    }
                    if (inner == null) {
                        throw error("empty parentheses");
                    }
                    return inner;
                }
                case PHRASE -> {
                    final TextQuery phrase = words(TextAnalyzer.tokens(t.text()));
                    if (phrase == null) {
                        throw error("empty phrase");
                    }
                    return phrase;
                }
                case WORD -> {
                    return word(t.text());
                }
                default -> throw error("unexpected '" + t.text() + "'");
            }
        }

        private TextQuery word(final String word) {
            if (word.endsWith("*")) {
                final String base = word.substring(0, word.length() - 1);
                if (base.contains("*")) {
                    throw error("'*' is allowed only at the end of a word: " + word);
                }
                final List<String> analyzed = TextAnalyzer.tokens(base);
                if (analyzed.size() != 1) {
                    throw error("a wildcard needs exactly one word before '*': " + word);
                }
                return new Prefix(analyzed.get(0));
            }
            if (word.contains("*")) {
                throw error("'*' is allowed only at the end of a word: " + word);
            }
            return words(TextAnalyzer.tokens(word));
        }

        private static TextQuery words(final List<String> analyzed) {
            if (analyzed.isEmpty()) {
                return null;
            }
            return analyzed.size() == 1 ? new Term(analyzed.get(0)) : new Phrase(analyzed);
        }

        private static void add(final List<TextQuery> operands, final TextQuery q) {
            if (q != null) {
                operands.add(q);
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            final Token t = tokens.get(pos);
            if (t.kind() != Kind.END) {
                pos++;
            }
            return t;
        }

        private TextQueryException error(final String message) {
            return new TextQueryException("Invalid text query \"" + source + "\": " + message);
        }

        private List<Token> lex(final String s) {
            final List<Token> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                final char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '(') {
                    out.add(new Token(Kind.OPEN, "("));
                    i++;
                } else if (c == ')') {
                    out.add(new Token(Kind.CLOSE, ")"));
                    i++;
                } else if (c == '"') {
                    final int end = s.indexOf('"', i + 1);
                    if (end < 0) {
                        throw error("missing closing quote");
                    }
                    out.add(new Token(Kind.PHRASE, s.substring(i + 1, end)));
                    i = end + 1;
                } else {
                    int end = i;
                    while (end < s.length()) {
                        final char d = s.charAt(end);
                        if (Character.isWhitespace(d) || d == '(' || d == ')' || d == '"') {
                            break;
                        }
                        end++;
                    }
                    final String word = s.substring(i, end);
                    out.add(switch (word) {
                        case "AND" -> new Token(Kind.AND, word);
                        case "OR" -> new Token(Kind.OR, word);
                        case "NOT" -> new Token(Kind.NOT, word);
                        default -> new Token(Kind.WORD, word);
                    });
                    i = end;
                }
            }
            out.add(new Token(Kind.END, "end of query"));
            return out;
        }
    }
}
