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
package io.github.dgreco.rya.accumulo.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;

import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.resolver.RyaContext;

/**
 * Key layout shared by the text and geo index tables:
 * <pre>
 * row        term-or-cell 0x00 predicate 0x00 subject 0x00 objectHash
 * family     graph IRI (empty for the default graph), as in spo
 * qualifier  spo qualifier (empty if none)
 * visibility the statement's visibility label
 * timestamp  the statement's timestamp (puts and deletes alike, as in spo)
 * value      empty
 * </pre>
 * Rows hold the subject, so no row grows without bound and tablets can split; the literal itself
 * is never copied (only its hash). Every hit is resolved against spo, so an index entry left
 * behind can cost time but never return a statement that isn't there.
 */
public final class IndexKeys {
    public static final char SEP = '\u0000';
    private static final Text EMPTY = new Text();
    private static final Value EMPTY_VALUE = new Value(new byte[0]);
    private static final HexFormat HEX = HexFormat.of();

    private IndexKeys() {
    }

    /** First 8 bytes of SHA-256 of the object's Rya encoding (value and type), as 16 hex digits. */
    public static String objectHash(final RyaType object) {
        try {
            final byte[][] parts = RyaContext.getInstance().serializeType(object);
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(parts[0]);
            sha.update(parts[1]);
            final byte[] digest = sha.digest();
            return HEX.formatHex(digest, 0, 8);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot encode " + object, e);
        }
    }

    public static DocKey docKey(final RyaStatement st) {
        return new DocKey(st.getPredicate().getData(), st.getSubject().getData(), objectHash(st.getObject()),
                st.getContext() == null ? "" : st.getContext().getData(),
                st.getQualifer() == null ? "" : st.getQualifer());
    }

    public static String row(final String termOrCell, final DocKey doc) {
        return termOrCell + SEP + doc.predicate() + SEP + doc.subject() + SEP + doc.objectHash();
    }

    /** The term or cell an index key belongs to. */
    public static String term(final Key key) {
        final String row = key.getRow().toString();
        return row.substring(0, row.indexOf(SEP));
    }

    public static DocKey docKey(final Key key) {
        final String row = key.getRow().toString();
        final int a = row.indexOf(SEP);
        final int b = row.indexOf(SEP, a + 1);
        final int c = row.indexOf(SEP, b + 1);
        if (a < 0 || b < 0 || c < 0) {
            throw new IllegalArgumentException("Not an index row: " + row);
        }
        return new DocKey(row.substring(a + 1, b), row.substring(b + 1, c), row.substring(c + 1),
                key.getColumnFamily().toString(), key.getColumnQualifier().toString());
    }

    /** Puts (or deletes) for a statement indexed under {@code terms}. */
    public static List<Mutation> mutations(final Collection<String> terms, final RyaStatement st, final boolean delete) {
        if (terms.isEmpty()) {
            return List.of();
        }
        final DocKey doc = docKey(st);
        final Text cf = doc.graph().isEmpty() ? EMPTY : new Text(doc.graph());
        final Text cq = doc.qualifier().isEmpty() ? EMPTY : new Text(doc.qualifier());
        final byte[] label = st.getColumnVisibility();
        final ColumnVisibility cv = label == null ? new ColumnVisibility() : new ColumnVisibility(label);
        final long ts = st.getTimestamp();
        final List<Mutation> out = new ArrayList<>(terms.size());
        for (final String term : terms) {
            final Mutation m = new Mutation(new Text(row(term, doc).getBytes(StandardCharsets.UTF_8)));
            if (delete) {
                m.putDelete(cf, cq, cv, ts);
            } else {
                m.put(cf, cq, cv, ts, EMPTY_VALUE);
            }
            out.add(m);
        }
        return out;
    }

    /** Every entry of one exact term (or cell), optionally only for one predicate. */
    public static Range termRange(final String term, final String predicate) {
        return Range.prefix(text(predicate == null ? term + SEP : term + SEP + predicate + SEP));
    }

    /** Every entry whose term (or cell) starts with {@code prefix}. */
    public static Range prefixRange(final String prefix) {
        return Range.prefix(text(prefix));
    }

    /** The entry of one term for one stored statement (any visibility). */
    public static Range entryRange(final String term, final DocKey doc) {
        final Text row = text(row(term, doc));
        return Range.exact(row, new Text(doc.graph()), new Text(doc.qualifier()));
    }

    private static Text text(final String s) {
        return new Text(s.getBytes(StandardCharsets.UTF_8));
    }
}
