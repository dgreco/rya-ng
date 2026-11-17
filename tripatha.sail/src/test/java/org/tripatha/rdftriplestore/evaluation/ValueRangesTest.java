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
package org.tripatha.rdftriplestore.evaluation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.algebra.Compare.CompareOp;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.util.QueryEvaluationUtil;
import org.junit.jupiter.api.Test;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.domain.RyaValueRange;
import org.tripatha.api.resolver.RyaContext;
import org.tripatha.api.resolver.RyaToRdfConversions;
import org.tripatha.api.resolver.impl.IntRyaTypeResolver;

import com.google.common.primitives.Bytes;

/**
 * {@link ValueRanges} against RDF4J's own comparisons: every stored value a filter keeps must
 * lie in its type's range, whatever the constant's type, including the edges (NaN, infinities,
 * -0.0, integer limits, time zones).
 */
public class ValueRangesTest {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final CompareOp[] OPS = { CompareOp.LT, CompareOp.LE, CompareOp.GT, CompareOp.GE, CompareOp.EQ };

    /** A value as stored: its data bytes, and the literal a filter sees once it is read back. */
    private record Stored(IRI type, String lexical, byte[] data, Literal seen) {
        static Stored of(final IRI type, final String lexical) {
            try {
                final byte[][] bytes = RyaContext.getInstance().serializeType(new RyaType(type, lexical));
                final RyaType back = RyaContext.getInstance().deserialize(Bytes.concat(bytes[0], bytes[1]));
                return new Stored(type, lexical, bytes[0], RyaToRdfConversions.convertLiteral(back));
            } catch (final Exception e) {
                throw new AssertionError("can't store " + lexical + "^^" + type, e);
            }
        }
    }

    @Test
    public void everyNumberAFilterKeepsIsInItsTypesRange() {
        final List<Stored> values = numbers();
        final List<Literal> constants = numericConstants();
        check(values, constants);
    }

    @Test
    public void everyDateTimeAFilterKeepsIsInItsRange() {
        final List<Stored> values = new ArrayList<>();
        for (final String v : List.of("2020-01-01T00:00:00Z", "2020-01-01T00:00:00", "2020-01-01T00:00:00.001Z",
                "2019-12-31T23:59:59.999Z", "2020-01-01T13:30:00.123+05:30", "2019-12-31T10:00:00-14:00",
                "2020-01-01T14:00:00+14:00", "1000-06-01T00:00:00Z", "1500-03-01T12:00:00Z", "1970-01-01T00:00:00Z",
                "1969-12-31T23:59:59.999Z", "9999-12-31T23:59:59.999Z", "0001-01-01T00:00:00Z", "2024-02-29T23:00:00")) {
            values.add(Stored.of(XSD.DATETIME, v));
        }
        final Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            final long ms = 1_577_836_800_000L + (long) (random.nextGaussian() * 3_600_000L * 48);
            values.add(Stored.of(XSD.DATETIME, new java.util.Date(ms).toInstant().toString()));
        }
        final List<Literal> constants = new ArrayList<>();
        for (final String c : List.of("2020-01-01T00:00:00Z", "2020-01-01T00:00:00", "2020-01-01T00:00:00.0001Z",
                "2020-01-01T00:00:00.9999Z", "2019-12-31T14:00:00-10:00", "2020-01-01T10:00:00+14:00", "2020-01-01T13:59:59",
                "1000-06-01T00:00:00", "1500-03-01T12:00:00Z", "1970-01-01T00:00:00Z", "9999-12-31T23:59:59.999",
                "0001-01-01T00:00:00Z")) {
            constants.add(VF.createLiteral(c, XSD.DATETIME));
        }
        // These bound nothing: the comparisons are still checked against the full read.
        constants.add(VF.createLiteral("10000-01-01T00:00:00Z", XSD.DATETIME));
        constants.add(VF.createLiteral("not a date", XSD.DATETIME));
        constants.add(VF.createLiteral("2020-01-01", XSD.DATE));
        check(values, constants);
    }

    @Test
    public void boundsAreTightEnoughToNarrow() {
        final ValueRanges.Bounds above60 = bounds(CompareOp.GT, VF.createLiteral("60", XSD.INTEGER));
        final RyaValueRange ints = only(above60.ranges(Set.of(XSD.INT.stringValue())));
        assertArrayEquals(utf8(IntRyaTypeResolver.INT_STRING_TYPE_ENCODER.encode(59)), ints.getStartData());
        assertNull(ints.getStopData(), "no upper bound: to the last int");
        final RyaValueRange doubles = only(above60.ranges(Set.of(XSD.DOUBLE.stringValue())));
        assertNotNull(doubles.getStartData());
        assertNull(doubles.getStopData(), "no upper bound: past infinity, to NaN");
        final ValueRanges.Bounds below = bounds(CompareOp.LT, VF.createLiteral("60", XSD.INTEGER));
        assertNull(only(below.ranges(Set.of(XSD.DOUBLE.stringValue()))).getStartData(), "from -INF");
    }

    @Test
    public void onlyTypesThatSortLikeTheirValuesAreNarrowed() {
        final ValueRanges.Bounds b = bounds(CompareOp.GT, VF.createLiteral("60", XSD.INTEGER));
        assertEquals(2, b.ranges(Set.of(XSD.INT.stringValue(), XSD.DOUBLE.stringValue())).size());
        assertNull(b.ranges(Set.of(XSD.INT.stringValue(), XSD.DECIMAL.stringValue())), "xsd:decimal is stored as text");
        assertNull(b.ranges(Set.of(XSD.INT.stringValue(), XSD.STRING.stringValue())));
        assertNull(b.ranges(Set.of(XSD.DATE.stringValue())), "xsd:date is stored as text");
        assertNull(b.ranges(Set.of(XSD.DATETIME.stringValue())), "a number is not compared with a date-time");
        assertNull(b.ranges(Set.of("")), "an object without a datatype");
        assertNull(b.ranges(Set.of(XSD.BYTE.stringValue())), "bytes don't sort like their values: their range is all of them");
        assertNull(b.ranges(Set.of(XSD.INT.stringValue(), XSD.BYTE.stringValue())), "which costs a whole scan of the predicate");
        assertNull(bounds(CompareOp.GT, VF.createLiteral("-1.0E300", XSD.DOUBLE)).ranges(Set.of(XSD.INT.stringValue())),
                "a range of every int");
        assertTrue(bounds(CompareOp.GT, VF.createLiteral("200", XSD.INTEGER)).ranges(Set.of(XSD.BYTE.stringValue())).isEmpty(),
                "no byte is above 200");
    }

    @Test
    public void contradictoryBoundsReadNothingAndMixedOnesEverything() {
        final ValueRanges.Bounds none = bounds(CompareOp.GT, VF.createLiteral("10", XSD.INTEGER));
        none.add(CompareOp.LT, VF.createLiteral("5", XSD.INTEGER));
        assertTrue(none.ranges(Set.of(XSD.INT.stringValue(), XSD.DOUBLE.stringValue(), XSD.INTEGER.stringValue())).isEmpty());
        final ValueRanges.Bounds mixed = bounds(CompareOp.GT, VF.createLiteral("10", XSD.INTEGER));
        mixed.add(CompareOp.LT, VF.createLiteral("2020-01-01T00:00:00Z", XSD.DATETIME));
        assertNull(mixed.ranges(Set.of(XSD.INT.stringValue())));
        final ValueRanges.Bounds nothing = bounds(CompareOp.NE, VF.createLiteral("10", XSD.INTEGER));
        nothing.add(CompareOp.LT, VF.createLiteral("NaN", XSD.DOUBLE));
        nothing.add(CompareOp.GT, VF.createLiteral("abc", XSD.INTEGER));
        assertTrue(!nothing.bounded(), "!=, NaN and an ill-typed constant bound nothing");
    }

    // ------------------------------------------------------------------ helpers

    private static void check(final List<Stored> values, final List<Literal> constants) {
        int matches = 0;
        int narrowed = 0;
        for (final Stored v : values) {
            for (final Literal c : constants) {
                for (final CompareOp op : OPS) {
                    final ValueRanges.Bounds b = bounds(op, c);
                    if (keeps(v.seen(), op, c)) {
                        matches++;
                        narrowed += assertInRange(v, b, op + " " + c);
                    }
                }
            }
            // Betweens: a lower and an upper bound.
            for (final Literal lo : constants) {
                for (final Literal hi : constants) {
                    final ValueRanges.Bounds b = bounds(CompareOp.GE, lo);
                    b.add(CompareOp.LE, hi);
                    if (keeps(v.seen(), CompareOp.GE, lo) && keeps(v.seen(), CompareOp.LE, hi)) {
                        matches++;
                        narrowed += assertInRange(v, b, ">= " + lo + " && <= " + hi);
                    }
                }
            }
        }
        assertTrue(matches > 1000, "the cases exercise matches: " + matches);
        assertTrue(narrowed > matches / 2, "most matches were checked against a range: " + narrowed + " of " + matches);
    }

    /** @return 1 if the value was checked against a range, 0 if its pattern would read everything */
    private static int assertInRange(final Stored v, final ValueRanges.Bounds b, final String condition) {
        if (!b.bounded()) {
            return 0;
        }
        final List<RyaValueRange> ranges = b.ranges(Set.of(v.type().stringValue()));
        if (ranges == null) {
            return 0;
        }
        if (ranges.isEmpty()) {
            fail(v.seen() + " passes " + condition + ", but its type got no range");
        }
        final RyaValueRange range = only(ranges);
        if (!inRange(v.data(), range)) {
            fail(v.seen() + " (stored as " + v.lexical() + ") passes " + condition + " but is outside " + range);
        }
        return 1;
    }

    /** Whether the row of a value with this data lies in the range's rows. */
    private static boolean inRange(final byte[] data, final RyaValueRange range) {
        final byte[] row = Bytes.concat(data, new byte[] { 0, 's', 1, range.getMarker() });
        final byte[] start = range.getStartData() == null ? new byte[0] : range.getStartData();
        final byte[] stop = range.getStopData() == null ? new byte[] { (byte) 0xff }
                : Bytes.concat(range.getStopData(), new byte[] { 0, (byte) 0xff });
        return Arrays.compareUnsigned(row, start) >= 0 && Arrays.compareUnsigned(row, stop) <= 0;
    }

    /** Whether RDF4J's comparison {@code value op constant} is true, in strict or standard mode. */
    private static boolean keeps(final Literal value, final CompareOp op, final Literal constant) {
        for (final boolean strict : new boolean[] { true, false }) {
            try {
                final boolean result = switch (op) {
                    case LT -> QueryEvaluationUtil.compareLT(value, constant, strict);
                    case LE -> QueryEvaluationUtil.compareLE(value, constant, strict);
                    case GT -> QueryEvaluationUtil.compareGT(value, constant, strict);
                    case GE -> QueryEvaluationUtil.compareGE(value, constant, strict);
                    case EQ -> QueryEvaluationUtil.compareEQ(value, constant, strict);
                    default -> false;
                };
                if (result) {
                    return true;
                }
            } catch (final ValueExprEvaluationException e) {
                // an error: the filter rejects the row
            }
        }
        return false;
    }

    private static ValueRanges.Bounds bounds(final CompareOp op, final Literal constant) {
        final ValueRanges.Bounds b = new ValueRanges.Bounds();
        b.add(op, constant);
        return b;
    }

    private static RyaValueRange only(final List<RyaValueRange> ranges) {
        assertNotNull(ranges);
        assertEquals(1, ranges.size(), ranges.toString());
        return ranges.get(0);
    }

    private static List<Stored> numbers() {
        final Map<IRI, List<String>> lexicals = new LinkedHashMap<>();
        final List<String> ints = new ArrayList<>(List.of("-2147483648", "-2147483647", "-1000", "-61", "-60", "-1", "0", "1",
                "59", "60", "61", "100", "1000", "70000", "2147483646", "2147483647",
                // Rounded to the float 2^26 when compared with a float.
                "67108866", "-67108866"));
        final List<String> longs = new ArrayList<>(List.of("-9223372036854775808", "-9223372036854775807", "-9007199254740993",
                "-2147483649", "-60", "0", "60", "2147483648", "9007199254740991", "9007199254740992", "9007199254740993",
                "9223372036854775806", "9223372036854775807",
                // Rounded to the double 2^60 when compared with a double.
                "1152921504606847076", "-1152921504606847076"));
        final List<String> integers = new ArrayList<>(List.of("-100000000000000000000000000000", "-9223372036854775809", "-129",
                "-128", "-1", "0", "1", "60", "127", "128", "255", "256", "9223372036854775808", "18446744073709551615",
                "100000000000000000000000000000", "1" + "0".repeat(400)));
        final List<String> doubles = new ArrayList<>(List.of("-INF", "-1.7976931348623157E308", "-1.0E300", "-60.0", "-4.9E-324",
                "-0.0", "0.0", "4.9E-324", "2.2250738585072014E-308", "0.1", "0.30000000000000004", "59.99999999999999",
                "60.0", "60.00000000000001", "61.0", "9.007199254740992E15", "9.007199254740994E15", "1.0E300",
                "1.7976931348623157E308", "INF", "NaN"));
        final List<String> floats = new ArrayList<>(List.of("-INF", "-3.4028235E38", "-60.0", "-1.4E-45", "-0.0", "0.0", "1.4E-45",
                "1.17549435E-38", "0.1", "59.999996", "60.0", "60.000004", "61.0", "1.6777216E7", "1.6777218E7",
                "3.4028235E38", "INF", "NaN"));
        final Random random = new Random(42);
        for (int i = 0; i < 60; i++) {
            ints.add(Integer.toString(random.nextInt(400) - 200));
            longs.add(Long.toString(random.nextLong()));
            integers.add(new java.math.BigInteger(80, random).subtract(java.math.BigInteger.ONE.shiftLeft(79)).toString());
            doubles.add(Double.toString(random.nextGaussian() * 100));
            floats.add(Float.toString((float) (random.nextGaussian() * 100)));
        }
        lexicals.put(XSD.INT, ints);
        lexicals.put(XSD.SHORT, ints);
        lexicals.put(XSD.LONG, longs);
        lexicals.put(XSD.INTEGER, integers);
        lexicals.put(XSD.DOUBLE, doubles);
        lexicals.put(XSD.FLOAT, floats);
        final List<String> bytes = new ArrayList<>();
        for (int b = Byte.MIN_VALUE; b <= Byte.MAX_VALUE; b++) {
            bytes.add(Integer.toString(b));
        }
        lexicals.put(XSD.BYTE, bytes);
        final List<Stored> values = new ArrayList<>();
        lexicals.forEach((type, list) -> list.forEach(lexical -> values.add(Stored.of(type, lexical))));
        return values;
    }

    private static List<Literal> numericConstants() {
        final List<Literal> c = new ArrayList<>();
        for (final String v : List.of("60", "-61", "0", "-1", "127", "128", "-128", "-129", "2147483647", "2147483648",
                "-2147483649", "9007199254740993", "9223372036854775807", "9223372036854775808", "-9223372036854775809",
                "100000000000000000000000000000")) {
            c.add(VF.createLiteral(v, XSD.INTEGER));
        }
        for (final String v : List.of("60.5", "-0.5", "0.1", "59.999999999999999999", "60.000000000000000001", "-0.0")) {
            c.add(VF.createLiteral(v, XSD.DECIMAL));
        }
        for (final String v : List.of("6.0E1", "1.0E-1", "-0.0E0", "0.0E0", "1.0E300", "4.9E-324", "9.007199254740992E15",
                "1.152921504606847E18", "-1.152921504606847E18",
                "3.4028235E38", "NaN", "INF", "-INF")) {
            c.add(VF.createLiteral(v, XSD.DOUBLE));
        }
        for (final String v : List.of("0.1", "6.0E1", "59.999996", "-0.0", "1.4E-45", "1.6777217E7", "6.7108864E7",
                "-6.7108864E7", "NaN", "INF", "-INF")) {
            c.add(VF.createLiteral(v, XSD.FLOAT));
        }
        c.add(VF.createLiteral("60", XSD.INT));
        c.add(VF.createLiteral("-2147483648", XSD.INT));
        c.add(VF.createLiteral("9223372036854775807", XSD.LONG));
        c.add(VF.createLiteral("60", XSD.SHORT));
        c.add(VF.createLiteral("-1", XSD.BYTE));
        c.add(VF.createLiteral("18446744073709551615", XSD.UNSIGNED_LONG));
        c.add(VF.createLiteral("60", XSD.NON_NEGATIVE_INTEGER));
        // Ill-typed: they bound nothing.
        c.add(VF.createLiteral("abc", XSD.INTEGER));
        c.add(VF.createLiteral("1.5", XSD.INT));
        return c;
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
