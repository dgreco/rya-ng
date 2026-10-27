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
package io.github.dgreco.rya.accumulo.trace;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;

import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;

/**
 * Readable forms of keys, ranges and patterns. Printable text is shown as is; the separator
 * bytes and anything else unprintable as {@code ⟨xx⟩} in hex, so {@code alice ⟨00⟩ knows ⟨00⟩}
 * shows where a row's parts end.
 */
public final class TraceText {
    /** Longest text kept for one key part; longer ones are cut with "…". */
    static final int MAX_PART = 200;

    private TraceText() {
    }

    /** UTF-8 text with unprintable bytes as {@code ⟨xx⟩}. */
    public static String bytes(final byte[] bytes) {
        return bytes(bytes, 0, bytes.length);
    }

    static String bytes(final byte[] b, final int from, final int to) {
        final StringBuilder out = new StringBuilder();
        int i = from;
        while (i < to) {
            if (out.length() >= MAX_PART) {
                out.append('…');
                break;
            }
            final int x = b[i] & 0xff;
            if (x >= 0x20 && x < 0x7f) {
                out.append((char) x);
                i++;
                continue;
            }
            final int len = x >= 0xf0 && x <= 0xf4 ? 4 : x >= 0xe0 ? 3 : x >= 0xc2 && x <= 0xdf ? 2 : 0;
            if (len > 0 && x < 0xf8 && i + len <= to && continuation(b, i + 1, i + len)) {
                final String s = new String(b, i, len, StandardCharsets.UTF_8);
                if (s.codePointCount(0, s.length()) == 1 && s.codePointAt(0) != 0xfffd && !Character.isISOControl(s.codePointAt(0))) {
                    out.append(s);
                    i += len;
                    continue;
                }
            }
            out.append('⟨').append(Character.forDigit(x >> 4, 16)).append(Character.forDigit(x & 0xf, 16)).append('⟩');
            i++;
        }
        return out.toString();
    }

    private static boolean continuation(final byte[] b, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if ((b[i] & 0xc0) != 0x80) {
                return false;
            }
        }
        return true;
    }

    public static String text(final Text t) {
        return bytes(t.getBytes(), 0, t.getLength());
    }

    /**
     * {@code [start, end)}: rows, with the column of a start or end key that has one. Accumulo
     * stores an inclusive end row as "that row followed by 00, exclusive"; it is shown as written,
     * {@code [start, end]}.
     */
    public static String range(final Range r) {
        final Key end = r.getEndKey();
        String endText = key(end, "+∞");
        boolean endInclusive = r.isEndKeyInclusive();
        if (end != null && !endInclusive && end.getColumnFamilyData().length() == 0
                && end.getColumnQualifierData().length() == 0 && end.getRowData().length() > 0
                && end.getRowData().byteAt(end.getRowData().length() - 1) == 0) {
            final byte[] row = end.getRowData().toArray();
            endText = bytes(row, 0, row.length - 1);
            endInclusive = true;
        }
        return (r.isStartKeyInclusive() ? "[" : "(") + key(r.getStartKey(), "-∞") + ", " + endText + (endInclusive ? "]" : ")");
    }

    private static String key(final Key k, final String infinite) {
        if (k == null) {
            return infinite;
        }
        final String row = text(k.getRow());
        final String cf = text(k.getColumnFamily());
        final String cq = text(k.getColumnQualifier());
        if (cf.isEmpty() && cq.isEmpty()) {
            return row;
        }
        return row + " · " + cf + (cq.isEmpty() ? "" : ":" + cq);
    }

    /** Priority, name, class and options of a scan-time iterator. */
    public static String iterator(final IteratorSetting s) {
        final String cls = s.getIteratorClass().substring(s.getIteratorClass().lastIndexOf('.') + 1);
        final StringBuilder out = new StringBuilder().append(s.getPriority()).append(' ').append(s.getName()).append(" (")
                .append(cls).append(')');
        final Map<String, String> options = new TreeMap<>(s.getOptions());
        if (!options.isEmpty()) {
            out.append(' ');
            boolean first = true;
            for (final Map.Entry<String, String> o : options.entrySet()) {
                out.append(first ? "" : ", ").append(o.getKey()).append('=')
                        .append(bytes(o.getValue().getBytes(StandardCharsets.UTF_8)));
                first = false;
            }
        }
        return out.toString();
    }

    /** A statement pattern: {@code <s> <p> "o"^^type ⟨graph⟩}, with {@code ?} for an open position. */
    public static String pattern(final RyaStatement st) {
        return term(st.getSubject()) + ' ' + term(st.getPredicate()) + ' ' + term(st.getObject())
                + (st.getContext() == null ? "" : " in " + term(st.getContext()));
    }

    /** One position of a pattern. */
    public static String term(final RyaType t) {
        if (t == null) {
            return "?";
        }
        final String data = String.valueOf(t.getData());
        if (t.getDataType() == null || "http://www.w3.org/2001/XMLSchema#anyURI".equals(t.getDataType().stringValue())) {
            return '<' + data + '>';
        }
        if (t.getLanguage() != null) {
            return '"' + data + "\"@" + t.getLanguage();
        }
        final String type = t.getDataType().stringValue();
        final String shortType = type.startsWith("http://www.w3.org/2001/XMLSchema#") ? "xsd:" + type.substring(33) : '<' + type + '>';
        return '"' + data + "\"^^" + shortType;
    }
}
