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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import io.github.dgreco.rya.api.domain.RyaType;

/**
 * Typed literals in exactly the lexical form the store gives back. Statistics counters are keyed
 * on the lexical form, so a query constant copied from results must hit the same counter row as
 * the generated value; tests check every form survives a store round trip unchanged.
 */
public final class LiteralForms {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private LiteralForms() {
    }

    public static RyaType integer(final long value) {
        return new RyaType(XSD.INTEGER, Long.toString(value));
    }

    public static RyaType integer32(final int value) {
        return new RyaType(XSD.INT, Integer.toString(value));
    }

    public static RyaType longValue(final long value) {
        return new RyaType(XSD.LONG, Long.toString(value));
    }

    /** Rounded to two decimals. */
    public static RyaType decimal2(final double value) {
        return new RyaType(XSD.DOUBLE, Double.toString(Math.round(value * 100) / 100.0));
    }

    public static RyaType floatValue(final float value) {
        return new RyaType(XSD.FLOAT, Float.toString(value));
    }

    public static RyaType bool(final boolean value) {
        return new RyaType(XSD.BOOLEAN, Boolean.toString(value));
    }

    /** Milliseconds since the epoch, as UTC with milliseconds. */
    public static RyaType dateTime(final long millis) {
        return new RyaType(XSD.DATETIME, DATE_TIME.format(Instant.ofEpochMilli(millis)));
    }

    public static RyaType date(final LocalDate date) {
        return new RyaType(XSD.DATE, date.toString());
    }

    public static RyaType string(final String value) {
        return new RyaType(XSD.STRING, value);
    }

    public static RyaType text(final String value, final String language) {
        return new RyaType(RDF.LANGSTRING, value, language);
    }

    public static RyaType wkt(final String wkt) {
        return new RyaType(GEO.WKT_LITERAL, wkt);
    }

    /** A coordinate with at most six decimals, never in exponent form (WKT must not use it). */
    public static String coordinate(final double value) {
        final long scaled = Math.round(value * 1_000_000);
        final StringBuilder b = new StringBuilder(14);
        if (scaled < 0) {
            b.append('-');
        }
        final long abs = Math.abs(scaled);
        b.append(abs / 1_000_000);
        final long fraction = abs % 1_000_000;
        if (fraction != 0) {
            final StringBuilder digits = new StringBuilder(Long.toString(fraction));
            while (digits.length() < 6) {
                digits.insert(0, '0');
            }
            int end = digits.length();
            while (digits.charAt(end - 1) == '0') {
                end--;
            }
            b.append('.').append(digits, 0, end);
        }
        return b.toString();
    }
}
