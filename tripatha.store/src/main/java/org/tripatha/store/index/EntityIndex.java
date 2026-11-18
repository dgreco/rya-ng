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
package org.tripatha.store.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.resolver.RyaContext;
import org.tripatha.store.spi.RowMutation;

import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;

/**
 * The entity index (SCALE-STUDY.md O23): every statement's subject, filed under its predicate
 * and object in one of a fixed number of shards, so that a star's constant patterns
 * ({@code ?x p1 o1 . ?x p2 o2}) can be intersected shard by shard, and in the servers.
 *
 * <pre>
 * row        the shard: murmur3(subject) mod shards, in decimal, zero-padded to a fixed width
 * family     the term: predicate 01 object, the object as po stores it (data, 01, type marker);
 *            01 because no backend takes 00 in a family, and no predicate holds 01
 * qualifier  subject 01 graph 01 qualifier (graph and qualifier empty when there are none)
 * visibility the statement's label
 * timestamp  the statement's timestamp (puts and deletes alike, as in spo)
 * value      empty
 * </pre>
 *
 * <p>One posting per stored statement, as in {@code spo}: the graph and the statement's qualifier
 * are in the key, so the same triple in two graphs, or under two labels, is two postings, and a
 * star counts it as the nested loop does. All of a subject's postings are in one shard, sorted by
 * subject within each term.
 *
 * <p>Unlike the text and geo indexes, whose hits are checked against {@code spo}, the subjects
 * this index gives are answers: a posting must exist exactly when its statement does. So its
 * family is the term, not the graph, and dropping a graph deletes the postings whose qualifier
 * names it ({@link #graphQualifierRegex}); a store with the index on has storage version 1.2.0,
 * so that older code, which would write statements without postings, refuses to write to it.
 *
 * <p>The shard count is fixed when the index is built (it is part of its definition). A shard
 * is one row, which neither Accumulo nor HBase splits between servers, so the count must grow
 * with the store: about one per 200 M triples of its expected size.
 */
public final class EntityIndex implements SecondaryIndex {
    /**
     * The class of the iterator that intersects the shards in Accumulo's servers, in the jar of
     * {@code tripatha.accumulo.iterators}; named here, not linked, so nothing else needs the jar.
     */
    public static final String ACCUMULO_ITERATOR = "org.tripatha.accumulo.iterators.EntityIntersectingIterator";

    /** A subject every term of a star has in one shard, and the rows it gives: the product of its postings per term. */
    public record Match(byte[] subject, long count) {
    }

    /** The layout's version: changing the key layout means a rebuild. */
    public static final String VERSION = "entity/1";
    private static final String SHARDS = ";shards=";
    private static final byte SEP = 1;
    private static final byte[] EMPTY = new byte[0];

    private final int shards;
    private final int width;

    /** @param shards how many shards, at least 1 */
    public EntityIndex(final int shards) {
        if (shards < 1 || shards > 1_000_000) {
            throw new IllegalArgumentException("The entity index needs 1 to 1,000,000 shards, not " + shards);
        }
        this.shards = shards;
        this.width = Integer.toString(shards - 1).length();
    }

    /** The shard count a stored definition records, or -1 if it isn't one of this index's. */
    public static int shardsOf(final String definition) {
        if (definition == null || !definition.startsWith(VERSION + SHARDS)) {
            return -1;
        }
        try {
            return Integer.parseInt(definition.substring((VERSION + SHARDS).length()));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public IndexType type() {
        return IndexType.ENTITY;
    }

    @Override
    public String definition() {
        return VERSION + SHARDS + shards;
    }

    public int shards() {
        return shards;
    }

    /** Not used: postings are built by {@link #puts} and {@link #deletes} directly. */
    @Override
    public Collection<String> terms(final RyaStatement statement) {
        return List.of();
    }

    @Override
    public List<RowMutation> puts(final RyaStatement statement) {
        return List.of(mutation(statement, false));
    }

    @Override
    public List<RowMutation> deletes(final RyaStatement statement) {
        return List.of(mutation(statement, true));
    }

    private RowMutation mutation(final RyaStatement st, final boolean delete) {
        final byte[] subject = utf8(st.getSubject().getData());
        final RowMutation m = new RowMutation(shardRow(subject));
        final byte[] cf = term(st.getPredicate(), st.getObject());
        final byte[] cq = qualifier(subject, st.getContext(), st.getQualifer());
        if (delete) {
            m.delete(cf, cq, st.getColumnVisibility(), st.getTimestamp());
        } else {
            m.put(cf, cq, st.getColumnVisibility(), st.getTimestamp(), EMPTY);
        }
        return m;
    }

    /**
     * Refuses a statement whose graph or qualifier holds U+0001, which separates them in a
     * posting's qualifier: dropping a graph finds its postings by that separator.
     */
    public static void checkIndexable(final RyaStatement st) {
        if ((st.getContext() != null && st.getContext().getData().indexOf('\u0001') >= 0)
                || (st.getQualifer() != null && st.getQualifer().indexOf('\u0001') >= 0)) {
            throw new IllegalArgumentException(
                    "With the entity index on, a graph IRI or statement qualifier may not contain the character U+0001");
        }
    }

    /** The shard row of a subject (its UTF-8 bytes). */
    public byte[] shardRow(final byte[] subject) {
        final int shard = (Hashing.murmur3_32_fixed().hashBytes(subject).asInt() & 0x7fffffff) % shards;
        return utf8(pad(shard));
    }

    /** Every shard row, in order. */
    public List<byte[]> shardRows() {
        final List<byte[]> rows = new ArrayList<>(shards);
        for (int i = 0; i < shards; i++) {
            rows.add(utf8(pad(i)));
        }
        return rows;
    }

    private String pad(final int shard) {
        final String digits = Integer.toString(shard);
        return "0".repeat(width - digits.length()) + digits;
    }

    /** The term of a predicate and an object: {@code predicate 01 data 01 marker}, the object as po holds it. */
    public static byte[] term(final RyaIRI predicate, final RyaType object) {
        try {
            final byte[][] value = RyaContext.getInstance().serializeType(object);
            return Bytes.concat(utf8(predicate.getData()), new byte[] { SEP }, value[0], value[1]);
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot encode " + object, e);
        }
    }

    private static byte[] qualifier(final byte[] subject, final RyaIRI graph, final String qualifier) {
        return Bytes.concat(subject, new byte[] { SEP }, graph == null ? EMPTY : utf8(graph.getData()), new byte[] { SEP },
                qualifier == null ? EMPTY : utf8(qualifier));
    }

    /** The subject of a posting: its qualifier up to the first 01. */
    public static byte[] subject(final byte[] qualifier) {
        int end = 0;
        while (end < qualifier.length && qualifier[end] != SEP) {
            end++;
        }
        return java.util.Arrays.copyOf(qualifier, end);
    }

    /**
     * A regular expression matching the qualifiers of the postings of one graph, null for the
     * default graph: what {@code dropGraph} deletes, since the family is the term.
     */
    public static String graphQualifierRegex(final RyaIRI graph) {
        // Character classes rather than a dot: HBase embeds the expression between 00 separators.
        return "[^\\x01]*\\x01" + (graph == null ? "" : Pattern.quote(graph.getData())) + "\\x01[^\\x00]*";
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
