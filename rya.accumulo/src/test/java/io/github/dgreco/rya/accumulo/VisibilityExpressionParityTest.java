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
package io.github.dgreco.rya.accumulo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.security.VisibilityEvaluator;
import org.apache.accumulo.core.security.VisibilityParseException;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.store.visibility.VisibilityExpression;

/** rya-ng's neutral parser accepts and evaluates exactly what Accumulo's does. */
class VisibilityExpressionParityTest {
    private static final String[] PIECES = {"A", "B", "C", "x.y", "a-b", "\"q r\"", "\"s\\\"t\"", "&", "|", "(", ")", "\"",
            " ", "!", "\\"};
    private static final String[] LABELS = {"A", "B", "C", "x.y", "a-b", "q r", "s\"t"};

    @Test
    void randomExpressionsAgreeWithAccumulo() throws Exception {
        final Random random = new Random(42);
        int valid = 0;
        for (int i = 0; i < 20_000; i++) {
            final String expr = i % 2 == 0 ? noise(random) : wellFormed(random, 3);
            final byte[] bytes = expr.getBytes(StandardCharsets.UTF_8);
            ColumnVisibility cv = null;
            try {
                cv = new ColumnVisibility(bytes);
            } catch (final IllegalArgumentException e) {
                // invalid for Accumulo
            }
            VisibilityExpression ours = null;
            try {
                ours = VisibilityExpression.parse(bytes);
            } catch (final IllegalArgumentException e) {
                // invalid for us
            }
            assertEquals(cv != null, ours != null, "validity of [" + expr + "]");
            if (cv == null) {
                continue;
            }
            valid++;
            final List<String> held = new ArrayList<>();
            for (final String l : LABELS) {
                if (random.nextBoolean()) {
                    held.add(l);
                }
            }
            final VisibilityEvaluator evaluator = new VisibilityEvaluator(new Authorizations(held.toArray(new String[0])));
            boolean accumulo;
            try {
                accumulo = evaluator.evaluate(cv);
            } catch (final VisibilityParseException e) {
                throw new AssertionError(e);
            }
            assertEquals(accumulo, ours.isVisibleTo(held), "evaluation of [" + expr + "] with " + held);
            // The HBase rendering parses back to an equivalent expression.
            if (!ours.isEmpty()) {
                assertEquals(accumulo, VisibilityExpression.parse(ours.toHBase()).isVisibleTo(held), "rendering " + ours.toHBase());
            }
        }
        assertEquals(true, valid > 5_000, "too few valid expressions: " + valid);
    }

    private static String noise(final Random random) {
        final StringBuilder b = new StringBuilder();
        final int n = 1 + random.nextInt(7);
        for (int i = 0; i < n; i++) {
            b.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return b.toString();
    }

    private static String wellFormed(final Random random, final int depth) {
        if (depth == 0 || random.nextInt(3) == 0) {
            final String l = PIECES[random.nextInt(7)];
            return random.nextInt(8) == 0 ? "(" + l + ")" : l;
        }
        final String op = random.nextBoolean() ? "&" : "|";
        final int n = 2 + random.nextInt(2);
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append(op);
            }
            final String sub = wellFormed(random, depth - 1);
            final boolean compound = sub.contains("&") || sub.contains("|");
            b.append(compound || random.nextInt(6) == 0 ? "(" + sub + ")" : sub);
        }
        return b.toString();
    }
}
