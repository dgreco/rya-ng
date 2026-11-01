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

import static io.github.dgreco.rya.store.trace.TraceText.bytes;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;

/** Readable forms of Accumulo keys, ranges and iterators, in {@code TraceText}'s style. */
public final class AccumuloTraceText {
    private AccumuloTraceText() {
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
}
