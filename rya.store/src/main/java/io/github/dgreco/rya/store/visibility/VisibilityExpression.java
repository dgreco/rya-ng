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
package io.github.dgreco.rya.store.visibility;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A visibility label expression in Accumulo's syntax, which is rya-ng's syntax on every backend:
 * labels joined with {@code &} (and) or {@code |} (or), never both without parentheses.
 * Unquoted labels use {@code [A-Za-z0-9_\-:./]}; any other label is written in double quotes,
 * with {@code \"} and {@code \\} as its only escapes. The empty expression means "unlabelled".
 * <p>
 * The parser accepts exactly the expressions Accumulo's {@code ColumnVisibility} accepts.
 */
public final class VisibilityExpression {

    /** A node of the expression. */
    public sealed interface Node permits Label, And, Or {
    }

    /** One label, unescaped. */
    public record Label(String name) implements Node {
    }

    /** All operands must hold. */
    public record And(List<Node> operands) implements Node {
    }

    /** One operand must hold. */
    public record Or(List<Node> operands) implements Node {
    }

    private static final VisibilityExpression EMPTY = new VisibilityExpression(new byte[0], null);

    private final byte[] expression;
    private final Node root;

    private VisibilityExpression(final byte[] expression, final Node root) {
        this.expression = expression;
        this.root = root;
    }

    /**
     * Parses an expression; null or empty means unlabelled.
     *
     * @throws IllegalArgumentException if the expression is malformed
     */
    public static VisibilityExpression parse(final byte[] expression) {
        if (expression == null || expression.length == 0) {
            return EMPTY;
        }
        final Parser p = new Parser(expression);
        final Node root = p.expression();
        if (p.index != expression.length) {
            throw p.error(p.peek() == ')' ? "parenthesis mismatch" : "expression needs & or |");
        }
        return new VisibilityExpression(expression.clone(), root);
    }

    /** Parses an expression; null or empty means unlabelled. */
    public static VisibilityExpression parse(final String expression) {
        return parse(expression == null ? null : expression.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether this is the empty expression (an unlabelled cell). */
    public boolean isEmpty() {
        return root == null;
    }

    /** The expression's tree; null when empty. */
    public Node root() {
        return root;
    }

    /** The expression as written. */
    public byte[] bytes() {
        return expression.clone();
    }

    /** Every label the expression names. */
    public Set<String> labels() {
        final Set<String> out = new LinkedHashSet<>();
        collect(root, out);
        return out;
    }

    private static void collect(final Node n, final Set<String> out) {
        switch (n) {
            case null -> {
            }
            case Label l -> out.add(l.name());
            case And a -> a.operands().forEach(o -> collect(o, out));
            case Or o -> o.operands().forEach(x -> collect(x, out));
        }
    }

    /** Whether a reader holding these labels may see a cell with this expression. */
    public boolean isVisibleTo(final Collection<String> held) {
        return root == null || eval(root, held);
    }

    private static boolean eval(final Node n, final Collection<String> held) {
        return switch (n) {
            case Label l -> held.contains(l.name());
            case And a -> a.operands().stream().allMatch(o -> eval(o, held));
            case Or o -> o.operands().stream().anyMatch(x -> eval(x, held));
        };
    }

    /**
     * The expression in HBase's syntax: every compound operand in parentheses (HBase evaluates
     * {@code &} and {@code |} left to right), labels outside {@code [A-Za-z0-9_\-:./]} quoted.
     * Empty for the empty expression.
     */
    public String toHBase() {
        return root == null ? "" : render(root, false);
    }

    private static String render(final Node n, final boolean nested) {
        return switch (n) {
            case Label l -> quoteIfNeeded(l.name());
            case And a -> group(a.operands(), "&", nested);
            case Or o -> group(o.operands(), "|", nested);
        };
    }

    private static String group(final List<Node> operands, final String op, final boolean nested) {
        final StringBuilder b = new StringBuilder();
        if (nested) {
            b.append('(');
        }
        for (int i = 0; i < operands.size(); i++) {
            if (i > 0) {
                b.append(op);
            }
            b.append(render(operands.get(i), true));
        }
        if (nested) {
            b.append(')');
        }
        return b.toString();
    }

    /** A label as an expression term: as is when its characters allow, else quoted. */
    public static String quoteIfNeeded(final String label) {
        final byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
        boolean plain = bytes.length > 0;
        for (final byte c : bytes) {
            plain &= isLabelChar(c);
        }
        if (plain) {
            return label;
        }
        return '"' + label.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** The characters a label may use without quotes (Accumulo's and HBase's set). */
    public static boolean isLabelChar(final byte c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == ':' || c == '.' || c == '/';
    }

    @Override
    public String toString() {
        return new String(expression, StandardCharsets.UTF_8);
    }

    /** Recursive descent over Accumulo's grammar. */
    private static final class Parser {
        private final byte[] in;
        private int index;

        Parser(final byte[] in) {
            this.in = in;
        }

        int peek() {
            return index < in.length ? in[index] : -1;
        }

        IllegalArgumentException error(final String what) {
            return new IllegalArgumentException(what + " at " + index + " in visibility expression \""
                    + new String(in, StandardCharsets.UTF_8) + "\"");
        }

        /** term ((&amp; term)+ | (| term)+)? */
        Node expression() {
            final Node first = term();
            final int op = peek();
            if (op != '&' && op != '|') {
                return first;
            }
            final List<Node> operands = new ArrayList<>();
            add(operands, first, op);
            while (peek() == '&' || peek() == '|') {
                if (peek() != op) {
                    throw error("cannot mix & and |");
                }
                index++;
                add(operands, term(), op);
            }
            return op == '&' ? new And(List.copyOf(operands)) : new Or(List.copyOf(operands));
        }

        /** Flattens a parenthesized operand of the same operator, as Accumulo does. */
        private static void add(final List<Node> operands, final Node n, final int op) {
            if (op == '&' && n instanceof And a) {
                operands.addAll(a.operands());
            } else if (op == '|' && n instanceof Or o) {
                operands.addAll(o.operands());
            } else {
                operands.add(n);
            }
        }

        /** label | quoted label | ( expression ) */
        Node term() {
            final int c = peek();
            if (c == '(') {
                index++;
                if (peek() == ')') {
                    throw error("empty expression not allowed");
                }
                final Node inner = expression();
                if (peek() != ')') {
                    throw error(peek() == -1 ? "parenthesis mismatch" : "expression needs & or |");
                }
                index++;
                return inner;
            }
            if (c == '"') {
                return quoted();
            }
            final int start = index;
            while (index < in.length && isLabelChar(in[index])) {
                index++;
            }
            if (index == start) {
                throw error(c == -1 || c == '&' || c == '|' || c == ')' ? "empty term" : "bad character (" + c + ")");
            }
            return new Label(new String(in, start, index - start, StandardCharsets.UTF_8));
        }

        Node quoted() {
            final int start = index;
            index++;
            final ByteArrayOutputStream label = new ByteArrayOutputStream();
            while (index < in.length && in[index] != '"') {
                if (in[index] == '\\') {
                    index++;
                    if (index == in.length || (in[index] != '\\' && in[index] != '"')) {
                        throw error("invalid escaping within quotes");
                    }
                }
                label.write(in[index]);
                index++;
            }
            if (index == in.length) {
                index = start;
                throw error("unclosed quote");
            }
            if (label.size() == 0) {
                index = start;
                throw error("empty term");
            }
            index++;
            return new Label(label.toString(StandardCharsets.UTF_8));
        }
    }
}
