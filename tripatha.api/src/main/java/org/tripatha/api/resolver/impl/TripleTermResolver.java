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
package org.tripatha.api.resolver.impl;

import static org.tripatha.api.RdfCloudTripleStoreConstants.TYPE_DELIM_BYTE;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.rdf4j.model.IRI;

import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaRange;
import org.tripatha.api.domain.RyaSchema;
import org.tripatha.api.domain.RyaTripleTerm;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.resolver.RyaContext;
import org.tripatha.api.resolver.RyaTypeResolver;
import org.tripatha.api.resolver.RyaTypeResolverException;

/**
 * Writes an RDF 1.2 triple term inline, as an object value:
 * <pre>
 * 02 esc(subject) 02 esc(predicate) 02 esc(object data ++ object type suffix)   01 0E
 * </pre>
 * The inner object is written by its own resolver, type suffix included, so a nested triple
 * term is this same layout applied again. {@code esc} replaces the four bytes that must not
 * appear raw: {@code 00 → 03 04}, {@code 01 → 03 05}, {@code 02 → 03 06}, {@code 03 → 03 03}.
 * The data part therefore has no 00 or 01, which the row layout reserves, and 02 only as the
 * separator. IRIs and ordinary text contain none of the four, so in practice only the inner
 * object's 01 type delimiter is escaped.
 *
 * <p>The leading 02 keeps every triple term in one range of the object order, and the inner
 * subject comes first, so the triple terms about one subject are a prefix range.
 */
public class TripleTermResolver implements RyaTypeResolver {
    public static final int TRIPLE_TERM_MARKER = 14;

    private static final byte SEPARATOR = 2;
    private static final byte ESCAPE = 3;
    /** Added to an escaped byte: 00, 01 and 02 become 04, 05 and 06. 03 stands for itself. */
    private static final int SHIFT = 4;
    private static final byte[] SUFFIX = {TYPE_DELIM_BYTE, TRIPLE_TERM_MARKER};

    /**
     * @return the data part of a triple term, as the string its bytes are the UTF-8 of.
     * @throws RyaTypeResolverException if the inner object can't be encoded.
     */
    public static String encode(final RyaIRI subject, final RyaIRI predicate, final RyaType object)
            throws RyaTypeResolverException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SEPARATOR);
        escape(subject.getData().getBytes(StandardCharsets.UTF_8), out);
        out.write(SEPARATOR);
        escape(predicate.getData().getBytes(StandardCharsets.UTF_8), out);
        out.write(SEPARATOR);
        escape(RyaContext.getInstance().serialize(object), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * The prefix shared by the data of every triple term with this inner subject, and with this
     * inner predicate too when it is given: what a range over them starts with.
     */
    public static String prefix(final RyaIRI subject, final RyaIRI predicate) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SEPARATOR);
        if (subject != null) {
            escape(subject.getData().getBytes(StandardCharsets.UTF_8), out);
            out.write(SEPARATOR);
            if (predicate != null) {
                escape(predicate.getData().getBytes(StandardCharsets.UTF_8), out);
                out.write(SEPARATOR);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void escape(final byte[] bytes, final ByteArrayOutputStream out) {
        for (final byte b : bytes) {
            if (b == ESCAPE) {
                out.write(ESCAPE);
                out.write(ESCAPE);
            } else if (b >= 0 && b < ESCAPE) {
                out.write(ESCAPE);
                out.write(b + SHIFT);
            } else {
                out.write(b);
            }
        }
    }

    private static byte[] unescape(final byte[] bytes, final int from, final int to) throws RyaTypeResolverException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
        for (int i = from; i < to; i++) {
            final byte b = bytes[i];
            if (b != ESCAPE) {
                out.write(b);
            } else if (++i < to && bytes[i] == ESCAPE) {
                out.write(ESCAPE);
            } else if (i < to && bytes[i] >= SHIFT && bytes[i] < SHIFT + ESCAPE) {
                out.write(bytes[i] - SHIFT);
            } else {
                throw new RyaTypeResolverException("Not a triple term: bad escape");
            }
        }
        return out.toByteArray();
    }

    @Override
    public byte[] serialize(final RyaType ryaType) throws RyaTypeResolverException {
        final byte[][] parts = serializeType(ryaType);
        final byte[] bytes = new byte[parts[0].length + parts[1].length];
        System.arraycopy(parts[0], 0, bytes, 0, parts[0].length);
        System.arraycopy(parts[1], 0, bytes, parts[0].length, parts[1].length);
        return bytes;
    }

    @Override
    public byte[][] serializeType(final RyaType ryaType) throws RyaTypeResolverException {
        final String data = ryaType.getData();
        if (data == null || data.isEmpty() || data.charAt(0) != SEPARATOR) {
            throw new RyaTypeResolverException("Not a triple term");
        }
        return new byte[][] {
            RyaTypeResolverImpl.checkNoReservedBytes(data.getBytes(StandardCharsets.UTF_8)), SUFFIX.clone()};
    }

    @Override
    public RyaType deserialize(final byte[] bytes) throws RyaTypeResolverException {
        if (!deserializable(bytes)) {
            throw new RyaTypeResolverException("Bytes not deserializable");
        }
        final int end = bytes.length - SUFFIX.length;
        final int second = indexOf(bytes, 1, end);
        final int third = second < 0 ? -1 : indexOf(bytes, second + 1, end);
        if (bytes[0] != SEPARATOR || third < 0 || indexOf(bytes, third + 1, end) >= 0) {
            throw new RyaTypeResolverException("Not a triple term");
        }
        final RyaIRI subject = new RyaIRI(new String(unescape(bytes, 1, second), StandardCharsets.UTF_8));
        final RyaIRI predicate = new RyaIRI(new String(unescape(bytes, second + 1, third), StandardCharsets.UTF_8));
        final RyaType object = RyaContext.getInstance().deserialize(unescape(bytes, third + 1, end));
        return new RyaTripleTerm(subject, predicate, object, new String(bytes, 0, end, StandardCharsets.UTF_8));
    }

    private static int indexOf(final byte[] bytes, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] == SEPARATOR) {
                return i;
            }
        }
        return -1;
    }

    /** A triple term has parts, so there is no empty instance to fill in: use {@link RyaTripleTerm}'s constructor. */
    @Override
    public RyaType newInstance() {
        throw new UnsupportedOperationException("A triple term is built from its parts");
    }

    @Override
    public boolean deserializable(final byte[] bytes) {
        return bytes != null && bytes.length > SUFFIX.length + 3 && bytes[bytes.length - 1] == TRIPLE_TERM_MARKER
                && bytes[bytes.length - 2] == TYPE_DELIM_BYTE;
    }

    @Override
    public IRI getRyaDataType() {
        return RyaSchema.TRIPLE_TERM;
    }

    @Override
    public byte getMarkerByte() {
        return TRIPLE_TERM_MARKER;
    }

    @Override
    public RyaRange transformRange(final RyaRange ryaRange) {
        return ryaRange;
    }
}
