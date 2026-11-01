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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class VisibilityExpressionTest {

    @Test
    void emptyMeansUnlabelled() {
        assertTrue(VisibilityExpression.parse((String) null).isEmpty());
        assertTrue(VisibilityExpression.parse("").isVisibleTo(Set.of()));
        assertEquals("", VisibilityExpression.parse("").toHBase());
    }

    @Test
    void evaluatesLikeAccumulo() {
        final VisibilityExpression e = VisibilityExpression.parse("A&(B|C)");
        assertTrue(e.isVisibleTo(Set.of("A", "C")));
        assertFalse(e.isVisibleTo(Set.of("A")));
        assertFalse(e.isVisibleTo(Set.of("B", "C")));
        assertEquals(Set.of("A", "B", "C"), e.labels());
    }

    @Test
    void rendersFullyParenthesizedForHBase() {
        assertEquals("A&(B|C)", VisibilityExpression.parse("A&(B|C)").toHBase());
        assertEquals("(A&B)|C", VisibilityExpression.parse("(A&B)|C").toHBase());
        assertEquals("A&B&C", VisibilityExpression.parse("A&(B&C)").toHBase());
        assertEquals("A", VisibilityExpression.parse("((A))").toHBase());
        assertEquals("\"a b\"|x.y/z:1-2_3", VisibilityExpression.parse("\"a b\"|x.y/z:1-2_3").toHBase());
        assertEquals("\"q\\\"t\"", VisibilityExpression.parse("\"q\\\"t\"").toHBase());
    }

    @Test
    void rejectsWhatAccumuloRejects() {
        for (final String bad : new String[] {"A&B|C", "A&", "&A", "()", "(A", "A)", "A B", "A(B)", "\"a\"b", "\"\"",
                "\"open", "\"bad\\x\"", "A||B", "|"}) {
            assertThrows(IllegalArgumentException.class, () -> VisibilityExpression.parse(bad), bad);
        }
    }
}
