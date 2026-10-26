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
package io.github.dgreco.rya.loader.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.index.text.TextAnalyzer;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.resolver.RyaContext;
import io.github.dgreco.rya.loader.gen.Vocabulary.Language;

/** The generator's building blocks. */
class GeneratorPartsTest {

    @Test
    void splitMix64MatchesTheReferenceImplementation() {
        final SplitMix64 r = new SplitMix64(1234567);
        assertEquals(6457827717110365317L, r.nextLong());
        assertEquals(3203168211198807973L, r.nextLong());
        assertEquals(-8629252141511181193L, r.nextLong());
    }

    @Test
    void classLayoutRoundTripsAndKeepsShares() {
        final ClassLayout layout = new ClassLayout(GenConfig.defaultWeights());
        final Map<EntityClass, Integer> seen = new EnumMap<>(EntityClass.class);
        for (long i = 0; i < 100_000; i++) {
            final EntityClass c = layout.classOf(i);
            seen.merge(c, 1, Integer::sum);
            assertEquals(i, layout.indexOf(c, layout.rankOf(i)));
        }
        long total = 0;
        for (final EntityClass c : EntityClass.values()) {
            assertEquals(c.defaultWeight() * 100, seen.get(c), c.name());
            assertEquals(seen.get(c).longValue(), layout.count(c, 100_000));
            total += layout.count(c, 123_457);
        }
        assertEquals(123_457, total);
    }

    @Test
    void powerLawStaysInRangeAndIsSkewed() {
        final SplitMix64 r = new SplitMix64(9);
        final long n = 1_000_000;
        long top = 0;
        for (int i = 0; i < 100_000; i++) {
            final long rank = Distributions.powerLaw(r, n, 0.8);
            assertTrue(rank >= 0 && rank < n);
            if (rank < n / 100) {
                top++;
            }
        }
        assertTrue(top > 20_000, "the top 1% should get far more than 1% of draws: " + top);
        assertEquals(0, Distributions.powerLaw(r, 1, 0.8));
    }

    @Test
    void everyWordIsSearchable() {
        final Vocabulary v = Vocabulary.get();
        for (final Language l : Language.values()) {
            for (int rank = 0; rank < v.size(l); rank += 7) {
                final String w = v.word(l, rank);
                assertFalse(TextAnalyzer.terms(w).isEmpty(), l + " " + w);
                assertTrue(w.indexOf('\u0000') < 0 && w.indexOf('\u0001') < 0);
            }
        }
        assertEquals(v.word(Language.DE, 42), Vocabulary.get().word(Language.DE, 42));
    }

    /** The store gives back exactly the lexical form written, so counters keyed on it match queries. */
    @Test
    void literalsSurviveAStoreRoundTrip() throws Exception {
        final RyaType[] samples = {
            LiteralForms.integer(-42), LiteralForms.integer(9_000_000_000L), LiteralForms.integer32(17),
            LiteralForms.longValue(123_456_789_012L), LiteralForms.decimal2(12.345), LiteralForms.decimal2(1.0E7),
            LiteralForms.decimal2(-0.5), LiteralForms.floatValue(812.0f), LiteralForms.bool(true),
            LiteralForms.dateTime(1_615_000_000_123L), LiteralForms.dateTime(0),
            LiteralForms.date(java.time.LocalDate.of(1999, 12, 31)), LiteralForms.string("plain"),
            LiteralForms.text("Café", "fr"), LiteralForms.wkt("POINT(12.5 -41.25)")};
        for (final RyaType t : samples) {
            final byte[][] parts = RyaContext.getInstance().serializeType(t);
            final byte[] joined = new byte[parts[0].length + parts[1].length];
            System.arraycopy(parts[0], 0, joined, 0, parts[0].length);
            System.arraycopy(parts[1], 0, joined, parts[0].length, parts[1].length);
            final RyaType back = RyaContext.getInstance().deserialize(joined);
            assertEquals(t.getData(), back.getData(), t.getDataType() + " " + t.getData());
            assertEquals(t.getDataType(), back.getDataType());
        }
    }

    @Test
    void coordinatesNeverUseExponents() {
        assertEquals("0.0001", LiteralForms.coordinate(0.0001));
        assertEquals("-179.5", LiteralForms.coordinate(-179.5));
        assertEquals("12", LiteralForms.coordinate(12.0000001));
        assertEquals("-0.000001", LiteralForms.coordinate(-0.000001));
    }
}
