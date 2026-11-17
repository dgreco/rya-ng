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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.base.CoreDatatype;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.Compare.CompareOp;
import org.tripatha.api.domain.RyaValueRange;
import org.tripatha.api.resolver.RyaContext;
import org.tripatha.api.resolver.RyaTypeResolver;
import org.tripatha.api.resolver.impl.ByteRyaTypeResolver;
import org.tripatha.api.resolver.impl.DateTimeRyaTypeResolver;
import org.tripatha.api.resolver.impl.DoubleRyaTypeResolver;
import org.tripatha.api.resolver.impl.FloatRyaTypeResolver;
import org.tripatha.api.resolver.impl.IntRyaTypeResolver;
import org.tripatha.api.resolver.impl.IntegerRyaTypeResolver;
import org.tripatha.api.resolver.impl.LongRyaTypeResolver;
import org.tripatha.api.resolver.impl.ShortRyaTypeResolver;

/**
 * The ranges of stored values that comparisons of a pattern's object with constants allow
 * (SCALE-STUDY.md O14): one range per datatype the predicate's objects use.
 *
 * <p>A range only has to hold every value the comparisons can keep, never only those: the filter
 * stays in the query and decides every row. So the bounds are widened on purpose, and a
 * comparison this class can't reason about is left out, which only widens the ranges:
 * <ul>
 *   <li><b>Numbers.</b> RDF4J compares two numbers in a common type, rounding them to a float or
 *       a double when one of them is. Each bound is widened by more than that rounding (2^-20 of
 *       the constant, plus 2^-120), then floored or ceiled into each type's own values.</li>
 *   <li><b>NaN.</b> RDF4J orders doubles and floats with {@code Double.compare}, which puts NaN
 *       above every number: {@code ?v > 60} keeps a NaN. So a double or float range without an
 *       upper bound runs to the type's last stored data, past infinity. A constant that is NaN,
 *       infinite or ill-typed bounds nothing.</li>
 *   <li><b>Date-times</b> are stored as UTC milliseconds, and read back with a {@code Z}. A
 *       constant without a time zone is read as UTC: XSD compares a value with a zone and one
 *       without only beyond 14 hours, on the side that reading gives, and the comparisons in
 *       between are errors, which a filter rejects. The bounds are widened by a second, for
 *       fractions below a millisecond. A constant outside the years 1 to 9999 bounds nothing.</li>
 * </ul>
 *
 * <p>Only datatypes whose stored data sorts like their values are narrowed: {@code xsd:int},
 * {@code xsd:short}, {@code xsd:long}, {@code xsd:integer}, {@code xsd:double},
 * {@code xsd:float} (numbers) and {@code xsd:dateTime}. {@code xsd:byte} is stored as the
 * two hex digits of its unsigned byte, so its negative values sort after the others: its range
 * is all of it. Any other type of the predicate's objects ({@code xsd:decimal},
 * {@code xsd:date}, strings, IRIs, ...), or a type the constants can't be compared with, means
 * no ranges at all.
 *
 * <p>Each range is a scan of its own, and the types share the predicate's rows: a range also
 * reads, and drops, the rows of other types whose data falls in it. So a range open at both ends
 * would cost a whole scan of the predicate, and then there are no ranges either.
 */
final class ValueRanges {
    private static final BigDecimal RELATIVE = new BigDecimal(Math.scalb(1.0, -20));
    private static final BigDecimal ABSOLUTE = new BigDecimal(Math.scalb(1.0, -120));
    private static final long TIME_MARGIN_MS = 1000;

    /** The datatypes whose data sorts like their values, and how each one's bounds are encoded. */
    enum Kind {
        INT(IntRyaTypeResolver.class, true),
        SHORT(ShortRyaTypeResolver.class, true),
        LONG(LongRyaTypeResolver.class, true),
        INTEGER(IntegerRyaTypeResolver.class, true),
        BYTE(ByteRyaTypeResolver.class, true),
        DOUBLE(DoubleRyaTypeResolver.class, true),
        FLOAT(FloatRyaTypeResolver.class, true),
        DATETIME(DateTimeRyaTypeResolver.class, false);

        private final Class<? extends RyaTypeResolver> resolver;
        private final boolean numeric;

        Kind(final Class<? extends RyaTypeResolver> resolver, final boolean numeric) {
            this.resolver = resolver;
            this.numeric = numeric;
        }

        /** The kind of a datatype whose values the stock resolver stores, or null. */
        static Kind of(final IRI datatype) {
            final RyaTypeResolver r = RyaContext.getInstance().retrieveResolver(datatype);
            for (final Kind kind : values()) {
                if (r.getClass() == kind.resolver && datatype.equals(r.getRyaDataType())) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** What the comparisons on one variable require of it. */
    static final class Bounds {
        private BigDecimal low;
        private BigDecimal high;
        private Long lowMs;
        private Long highMs;
        private boolean numbers;
        private boolean times;

        /**
         * Adds the comparison {@code ?v op constant}, the variable on the left. One that bounds
         * nothing (another operator, or a constant that isn't a finite number or a date-time of
         * the years 1 to 9999) is left out.
         */
        void add(final CompareOp op, final Value constant) {
            final boolean lower = op == CompareOp.GT || op == CompareOp.GE || op == CompareOp.EQ;
            final boolean upper = op == CompareOp.LT || op == CompareOp.LE || op == CompareOp.EQ;
            if (!(lower || upper) || !(constant instanceof Literal literal)) {
                return;
            }
            final CoreDatatype.XSD type = literal.getCoreDatatype().asXSDDatatypeOrNull();
            if (type == null) {
                return;
            }
            if (type.isNumericDatatype()) {
                final BigDecimal c = number(literal, type);
                if (c != null) {
                    numbers = true;
                    if (lower) {
                        low = low == null ? c : low.max(c);
                    }
                    if (upper) {
                        high = high == null ? c : high.min(c);
                    }
                }
            } else if (type == CoreDatatype.XSD.DATETIME) {
                final long[] around = millis(literal);
                if (around != null) {
                    times = true;
                    if (lower) {
                        lowMs = lowMs == null ? around[0] : Math.max(lowMs, around[0]);
                    }
                    if (upper) {
                        highMs = highMs == null ? around[1] : Math.min(highMs, around[1]);
                    }
                }
            }
        }

        /** Whether any comparison bounds the variable. */
        boolean bounded() {
            return numbers || times;
        }

        /**
         * The ranges for objects of these datatypes.
         *
         * @return one range per datatype that can hold a match (none when the bounds exclude
         *         every value), or null when some datatype can't be narrowed: the pattern must
         *         read everything
         */
        List<RyaValueRange> ranges(final Collection<String> datatypes) {
            if (!bounded() || (numbers && times)) {
                return null;
            }
            final List<RyaValueRange> ranges = new ArrayList<>();
            for (final String datatype : datatypes) {
                final IRI iri;
                try {
                    iri = SimpleValueFactory.getInstance().createIRI(datatype);
                } catch (final IllegalArgumentException e) {
                    return null;
                }
                final Kind kind = Kind.of(iri);
                if (kind == null || kind.numeric != numbers) {
                    return null;
                }
                final byte marker = RyaContext.getInstance().retrieveResolver(iri).getMarkerByte();
                final RyaValueRange range = switch (kind) {
                    case INT, SHORT -> integers(iri, marker, BigInteger.valueOf(Integer.MIN_VALUE),
                            BigInteger.valueOf(Integer.MAX_VALUE), b -> IntRyaTypeResolver.INT_STRING_TYPE_ENCODER.encode(b.intValue()));
                    case LONG -> integers(iri, marker, BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE),
                            b -> LongRyaTypeResolver.LONG_STRING_TYPE_ENCODER.encode(b.longValue()));
                    case INTEGER -> integers(iri, marker, null, null, IntegerRyaTypeResolver.INTEGER_STRING_TYPE_ENCODER::encode);
                    case BYTE -> {
                        final RyaValueRange some = integers(iri, marker, BigInteger.valueOf(Byte.MIN_VALUE),
                                BigInteger.valueOf(Byte.MAX_VALUE), b -> "");
                        // Not stored in value order: all of it, or nothing.
                        yield some == null ? null : new RyaValueRange(iri, marker, null, null, "all");
                    }
                    case DOUBLE -> doubles(iri, marker);
                    case FLOAT -> floats(iri, marker);
                    case DATETIME -> dateTimes(iri, marker);
                };
                if (range != null) {
                    if (range.getStartData() == null && range.getStopData() == null) {
                        return null; // all of the predicate's rows anyway
                    }
                    ranges.add(range);
                }
            }
            return ranges;
        }

        private BigDecimal widenedLow() {
            return low == null ? null : low.subtract(low.abs().multiply(RELATIVE)).subtract(ABSOLUTE);
        }

        private BigDecimal widenedHigh() {
            return high == null ? null : high.add(high.abs().multiply(RELATIVE)).add(ABSOLUTE);
        }

        /**
         * The range of an integer type whose values lie between {@code min} and {@code max}
         * (null: unbounded), or null when no value of it can match.
         */
        private RyaValueRange integers(final IRI type, final byte marker, final BigInteger min, final BigInteger max,
                final Function<BigInteger, String> encoder) {
            final BigDecimal l = widenedLow();
            final BigDecimal h = widenedHigh();
            BigInteger from = l == null ? null : l.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
            BigInteger to = h == null ? null : h.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
            if ((from != null && max != null && from.compareTo(max) > 0) || (to != null && min != null && to.compareTo(min) < 0)
                    || (from != null && to != null && from.compareTo(to) > 0)) {
                return null;
            }
            if (from != null && min != null && from.compareTo(min) <= 0) {
                from = null;
            }
            if (to != null && max != null && to.compareTo(max) >= 0) {
                to = null;
            }
            return new RyaValueRange(type, marker, from == null ? null : utf8(encoder.apply(from)),
                    to == null ? null : utf8(encoder.apply(to)), text(from, to));
        }

        private RyaValueRange doubles(final IRI type, final byte marker) {
            final BigDecimal l = widenedLow();
            final BigDecimal h = widenedHigh();
            final Double from = l == null ? null : Math.nextDown(l.doubleValue());
            final Double to = h == null ? null : Math.nextUp(h.doubleValue());
            if (from != null && to != null && from > to) {
                return null;
            }
            // No upper bound: up to the last data of the type, NaN included.
            return new RyaValueRange(type, marker,
                    from == null || from == Double.NEGATIVE_INFINITY ? null : utf8(DoubleRyaTypeResolver.DOUBLE_TYPE_ENCODER.encode(from)),
                    to == null ? null : utf8(DoubleRyaTypeResolver.DOUBLE_TYPE_ENCODER.encode(to)), text(from, to));
        }

        private RyaValueRange floats(final IRI type, final byte marker) {
            final BigDecimal l = widenedLow();
            final BigDecimal h = widenedHigh();
            final Float from = l == null ? null : Math.nextDown(l.floatValue());
            final Float to = h == null ? null : Math.nextUp(h.floatValue());
            if (from != null && to != null && from > to) {
                return null;
            }
            return new RyaValueRange(type, marker,
                    from == null || from == Float.NEGATIVE_INFINITY ? null : utf8(FloatRyaTypeResolver.FLOAT_TYPE_ENCODER.encode(from)),
                    to == null ? null : utf8(FloatRyaTypeResolver.FLOAT_TYPE_ENCODER.encode(to)), text(from, to));
        }

        private RyaValueRange dateTimes(final IRI type, final byte marker) {
            if (lowMs != null && highMs != null && lowMs > highMs) {
                return null;
            }
            return new RyaValueRange(type, marker,
                    lowMs == null ? null : utf8(DateTimeRyaTypeResolver.DATE_STRING_TYPE_ENCODER.encode(new Date(lowMs))),
                    highMs == null ? null : utf8(DateTimeRyaTypeResolver.DATE_STRING_TYPE_ENCODER.encode(new Date(highMs))),
                    (lowMs == null ? "" : DateTimeRyaTypeResolver.UTC_XMLDATETIME_FORMATTER.print(lowMs)) + " … "
                            + (highMs == null ? "" : DateTimeRyaTypeResolver.UTC_XMLDATETIME_FORMATTER.print(highMs)));
        }
    }

    private ValueRanges() {
    }

    /** The exact value of a numeric constant as RDF4J reads it, or null if it is not a finite number. */
    private static BigDecimal number(final Literal literal, final CoreDatatype.XSD type) {
        try {
            if (type == CoreDatatype.XSD.DOUBLE || type == CoreDatatype.XSD.FLOAT) {
                final double d = literal.doubleValue();
                return Double.isFinite(d) ? new BigDecimal(d) : null;
            }
            return literal.decimalValue();
        } catch (final RuntimeException e) {
            return null; // ill-typed: RDF4J's comparison fails, so it bounds nothing here
        }
    }

    /**
     * The milliseconds a date-time constant may stand for, widened: {@code [low, high]}, or null
     * when it bounds nothing.
     */
    private static long[] millis(final Literal literal) {
        try {
            final XMLGregorianCalendar cal = (XMLGregorianCalendar) literal.calendarValue().clone();
            if (cal.getYear() == DatatypeConstants.FIELD_UNDEFINED || cal.getEonAndYear() == null
                    || cal.getYear() < 1 || cal.getYear() > 9999) {
                return null;
            }
            if (cal.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
                cal.setTimezone(0);
            }
            final long ms = cal.toGregorianCalendar().getTimeInMillis();
            return new long[] { ms - TIME_MARGIN_MS, ms + TIME_MARGIN_MS };
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(final Object from, final Object to) {
        return (from == null ? "" : from.toString()) + " … " + (to == null ? "" : to.toString());
    }
}
