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
package io.github.dgreco.rya.store.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.resolver.RyaContext;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;

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
    private static final byte[] EMPTY = new byte[0];
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

    /** The term or cell an index entry belongs to. */
    public static String term(final Cell cell) {
        final String row = new String(cell.row(), StandardCharsets.UTF_8);
        return row.substring(0, row.indexOf(SEP));
    }

    public static DocKey docKey(final Cell cell) {
        final String row = new String(cell.row(), StandardCharsets.UTF_8);
        final int a = row.indexOf(SEP);
        final int b = row.indexOf(SEP, a + 1);
        final int c = row.indexOf(SEP, b + 1);
        if (a < 0 || b < 0 || c < 0) {
            throw new IllegalArgumentException("Not an index row: " + row);
        }
        return new DocKey(row.substring(a + 1, b), row.substring(b + 1, c), row.substring(c + 1),
                new String(cell.family(), StandardCharsets.UTF_8), new String(cell.qualifier(), StandardCharsets.UTF_8));
    }

    /** Puts (or deletes) for a statement indexed under {@code terms}. */
    public static List<RowMutation> mutations(final Collection<String> terms, final RyaStatement st, final boolean delete) {
        if (terms.isEmpty()) {
            return List.of();
        }
        final DocKey doc = docKey(st);
        final byte[] cf = doc.graph().isEmpty() ? EMPTY : utf8(doc.graph());
        final byte[] cq = doc.qualifier().isEmpty() ? EMPTY : utf8(doc.qualifier());
        final byte[] cv = st.getColumnVisibility();
        final long ts = st.getTimestamp();
        final List<RowMutation> out = new ArrayList<>(terms.size());
        for (final String term : terms) {
            final RowMutation m = new RowMutation(utf8(row(term, doc)));
            if (delete) {
                m.delete(cf, cq, cv, ts);
            } else {
                m.put(cf, cq, cv, ts, EMPTY);
            }
            out.add(m);
        }
        return out;
    }

    /** Every entry of one exact term (or cell), optionally only for one predicate. */
    public static KeyRange termRange(final String term, final String predicate) {
        return KeyRange.prefix(utf8(predicate == null ? term + SEP : term + SEP + predicate + SEP));
    }

    /** Every entry whose term (or cell) starts with {@code prefix}. */
    public static KeyRange prefixRange(final String prefix) {
        return KeyRange.prefix(utf8(prefix));
    }

    /** The entry of one term for one stored statement (any visibility). */
    public static KeyRange entryRange(final String term, final DocKey doc) {
        return KeyRange.cell(utf8(row(term, doc)), utf8(doc.graph()), utf8(doc.qualifier()));
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
