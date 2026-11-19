package org.tripatha.rdftriplestore.evaluation;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.persist.RyaDAO;
import org.tripatha.api.query.strategy.TriplePatternStrategy;
import org.tripatha.api.resolver.RdfToRyaConversions;
import org.tripatha.api.resolver.RyaContext;
import org.tripatha.api.resolver.RyaToRdfConversions;
import org.tripatha.api.resolver.UnsupportedValueException;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.store.trace.StoreTrace;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;

/**
 * Answers many {@link LookupKey}s with as few scans as possible.
 *
 * <p>Keys are grouped by the table they resolve to and by the named graph they ask for; each
 * group is one {@code batchQuery} (a BatchScanner over all the group's ranges). Every result is
 * then given to each key of that group that it matches <em>exactly</em>: same subject and
 * predicate, same encoded object (data and type), and the key's graph condition. The ranges are
 * looser than that (they ignore the object's type, and may overlap), so this matching is what
 * keeps the answer for a key identical to a single lookup of that key.
 *
 * <p>Within a group each stored cell is counted once (cells differ by qualifier, visibility and
 * timestamp as well as by statement, as in a single lookup). When every key of a group has the
 * same shape and leaves the object free (an entity chain's links, {@code (s, p, ?)}), their
 * ranges are prefixes ending at a delimiter, and stored terms hold no {@code 00} byte, so no two
 * of them overlap: no cell can come twice, and the scan neither keeps track of the cells it has
 * seen nor encodes their objects again (SCALE-STUDY.md O17).
 *
 * <p>With {@code query.join.subjectranges} on (it is off by default), a subject asked for by two
 * or more keys in {@code spo} (the links of an entity chain, for example) is read as one range,
 * its whole subject, instead of one range per key: its statements are neighbouring rows, so that
 * saves seeks, and the exact matching keeps the answers the same. On the warm join benchmark it
 * read about four times the cells for no gain (SCALE-STUDY.md O21); it may pay where seeks are
 * block reads, on a cold cache.
 * If those ranges return more than {@value #SUBJECT_RANGE_CELLS} cells per subject read whole,
 * plus {@value #SUBJECT_RANGE_SLACK}, a subject with many statements is among them, and the
 * group is read again one range per key.
 */
public final class BatchLookup {
    private static final RyaContext TYPES = RyaContext.getInstance();
    /** Cells a subject range may return per subject, on average, before the batch is read range by range. */
    static final int SUBJECT_RANGE_CELLS = 64;
    /** Cells any batch of subject ranges may return on top of that. */
    static final int SUBJECT_RANGE_SLACK = 1024;
    private static final long OVER_LIMIT = -1;
    private static final long OVER_BUDGET = -2;

    private BatchLookup() {
    }

    /**
     * @return the statements for every key (possibly an empty list), or {@code null} if more than
     *         {@code limit} statements would have to be held; then nothing is cached and every key
     *         is looked up on its own.
     */
    public static <C extends RdfCloudTripleStoreConfiguration> Map<LookupKey, List<Statement>> fetch(final RyaDAO<C> dao,
            final C conf, final Collection<LookupKey> keys, final long limit) {
        final RyaTripleContext tripleContext = RyaTripleContext.getInstance(conf);
        final Map<GroupId, Group> groups = new LinkedHashMap<>();
        for (final LookupKey key : keys) {
            final Target target = Target.of(key);
            if (target == null) {
                continue; // not something a single lookup would be asked for, or a full scan
            }
            final TABLE_LAYOUT layout = layoutOf(tripleContext, target.pattern, conf);
            if (layout == null) {
                continue;
            }
            groups.computeIfAbsent(new GroupId(layout, target.namedGraph), g -> new Group()).add(key, target);
        }

        final Map<LookupKey, List<Statement>> results = new HashMap<>();
        long held = 0;
        final boolean subjectRanges = conf.isJoinSubjectRanges();
        for (final Map.Entry<GroupId, Group> e : groups.entrySet()) {
            final Group group = e.getValue();
            final List<RyaStatement> bySubject = subjectRanges && e.getKey().layout() == TABLE_LAYOUT.SPO
                    ? group.subjectRanges() : null;
            if (bySubject != null) {
                final long budget = (long) SUBJECT_RANGE_CELLS * group.collapsedSubjects + SUBJECT_RANGE_SLACK;
                final long matched = scan(dao, conf, group, bySubject, results, limit - held, budget);
                if (matched == OVER_LIMIT) {
                    return null;
                }
                if (matched != OVER_BUDGET) {
                    held += matched;
                    continue;
                }
                StoreTrace.note("batched lookup", () -> "subject ranges returned over " + budget
                        + " cells: reading this batch one range per lookup");
            }
            final long matched = scan(dao, conf, group, group.patterns, results, limit - held, Long.MAX_VALUE);
            if (matched == OVER_LIMIT) {
                return null;
            }
            held += matched;
        }
        return results;
    }

    /**
     * Reads a group's patterns with one batch scan, and gives each stored statement to the group's
     * keys it matches exactly, after clearing their lists.
     *
     * @return the statements given to keys; {@link #OVER_LIMIT} if more than {@code limit}; or
     *         {@link #OVER_BUDGET} if the scan returned more than {@code budget} cells
     */
    private static <C extends RdfCloudTripleStoreConfiguration> long scan(final RyaDAO<C> dao, final C conf, final Group group,
            final List<RyaStatement> patterns, final Map<LookupKey, List<Statement>> results, final long limit, final long budget) {
        for (final LookupKey key : group.keys) {
            results.put(key, new ArrayList<>());
        }
        long held = 0;
        long read = 0;
        // Subject ranges overlap the keys they stand for: only a group's own patterns can be disjoint.
        final boolean disjoint = patterns == group.patterns && group.index.size() == 1
                && !group.index.keySet().iterator().next().object();
        final boolean objects = !disjoint || group.index.keySet().stream().anyMatch(Shape::object);
        final Set<CellId> seen = disjoint ? null : new HashSet<>();
        try (CloseableIteration<RyaStatement> found = dao.getQueryEngine().batchQuery(patterns, conf)) {
            while (found.hasNext()) {
                final RyaStatement stored = found.next();
                if (++read > budget) {
                    return OVER_BUDGET;
                }
                final ByteBuffer object = objects ? encode(stored.getObject()) : null;
                if (objects && object == null || !disjoint && !seen.add(CellId.of(stored, object))) {
                    continue;
                }
                Statement converted = null;
                for (final Map.Entry<Shape, Map<Projection, List<LookupKey>>> byShape : group.index.entrySet()) {
                    final Shape shape = byShape.getKey();
                    if (shape.defaultGraphOnly && stored.getContext() != null) {
                        continue;
                    }
                    final List<LookupKey> matching = byShape.getValue().get(Projection.of(shape, stored, object));
                    if (matching == null) {
                        continue;
                    }
                    if (converted == null) {
                        converted = RyaToRdfConversions.convertStatement(stored);
                    }
                    for (final LookupKey key : matching) {
                        results.get(key).add(converted);
                        if (++held > limit) {
                            return OVER_LIMIT;
                        }
                    }
                }
            }
        }
        return held;
    }

    private static TABLE_LAYOUT layoutOf(final RyaTripleContext tripleContext, final RyaStatement pattern,
            final RdfCloudTripleStoreConfiguration conf) {
        try {
            final TriplePatternStrategy strategy = tripleContext.retrieveStrategy(pattern);
            if (strategy == null) {
                return null;
            }
            return strategy.defineRange(pattern.getSubject(), pattern.getPredicate(), pattern.getObject(),
                    pattern.getContext(), conf).getKey();
        } catch (final Exception e) {
            return null; // let the single lookup report the problem
        }
    }

    private static ByteBuffer encode(final RyaType value) {
        if (value == null) {
            return null;
        }
        try {
            final byte[][] parts = TYPES.serializeType(value);
            final byte[] bytes = new byte[parts[0].length + parts[1].length];
            System.arraycopy(parts[0], 0, bytes, 0, parts[0].length);
            System.arraycopy(parts[1], 0, bytes, parts[0].length, parts[1].length);
            return ByteBuffer.wrap(bytes);
        } catch (final Exception e) {
            return null;
        }
    }

    /** A key translated to what Tripatha would query. */
    private record Target(RyaStatement pattern, Shape shape, Projection projection, String namedGraph) {
        static Target of(final LookupKey key) {
            if (key.contexts().size() > 1) {
                return null;
            }
            final boolean hasContext = key.contexts().size() == 1;
            final Resource context = hasContext ? key.contexts().get(0) : null;
            final boolean defaultGraphOnly = hasContext && context == null;

            final RyaIRI s = key.subject() == null ? null : RdfToRyaConversions.convertResource(key.subject());
            final RyaIRI p = key.predicate() == null ? null : RdfToRyaConversions.convertIRI(key.predicate());
            final RyaType o;
            try {
                o = key.object() == null ? null : RdfToRyaConversions.convertValue(key.object());
            } catch (final UnsupportedValueException e) {
                return null; // let the single lookup report it
            }
            final RyaIRI c = context == null ? null : RdfToRyaConversions.convertResource(context);
            if (s == null && p == null && o == null) {
                return null; // a full scan; nothing to gain from batching
            }
            final ByteBuffer encodedObject = o == null ? null : encode(o);
            if (o != null && encodedObject == null) {
                return null;
            }
            final Shape shape = new Shape(s != null, p != null, o != null, defaultGraphOnly);
            final Projection projection = new Projection(s == null ? null : s.getData(), p == null ? null : p.getData(),
                    encodedObject);
            return new Target(new RyaStatement(s, p, o, c), shape, projection, c == null ? null : c.getData());
        }
    }

    /** Which parts a key binds, and whether it wants the default graph only. */
    private record Shape(boolean subject, boolean predicate, boolean object, boolean defaultGraphOnly) {
    }

    /** The bound parts of a key, or of a stored statement seen through a key's shape. */
    private record Projection(String subject, String predicate, ByteBuffer object) {
        static Projection of(final Shape shape, final RyaStatement stored, final ByteBuffer object) {
            return new Projection(shape.subject ? stored.getSubject().getData() : null,
                    shape.predicate ? stored.getPredicate().getData() : null,
                    shape.object ? object : null);
        }
    }

    /** One batch scan: a table, and the named graph it is restricted to (null for none). */
    private record GroupId(TABLE_LAYOUT layout, String namedGraph) {
    }

    /** The full identity of a stored cell. */
    private record CellId(String subject, String predicate, ByteBuffer object, String context, String qualifier,
            ByteBuffer visibility, Long timestamp) {
        static CellId of(final RyaStatement st, final ByteBuffer object) {
            final byte[] cv = st.getColumnVisibility();
            return new CellId(st.getSubject().getData(), st.getPredicate().getData(), object,
                    st.getContext() == null ? null : st.getContext().getData(), st.getQualifer(),
                    cv == null ? null : ByteBuffer.wrap(cv), st.getTimestamp());
        }
    }

    private static final class Group {
        final List<LookupKey> keys = new ArrayList<>();
        final List<RyaStatement> patterns = new ArrayList<>();
        /** How many subjects {@link #subjectRanges()} read whole. */
        int collapsedSubjects;
        final Map<Shape, Map<Projection, List<LookupKey>>> index = new LinkedHashMap<>();
        private final Set<RyaStatement> distinctPatterns = new HashSet<>();

        void add(final LookupKey key, final Target target) {
            keys.add(key);
            if (distinctPatterns.add(target.pattern)) {
                patterns.add(target.pattern);
            }
            index.computeIfAbsent(target.shape, s -> new HashMap<>())
                    .computeIfAbsent(target.projection, p -> new ArrayList<>()).add(key);
        }

        /**
         * The patterns with each subject that has two or more of them replaced by the subject's
         * whole range (same graph): its statements are neighbouring rows of {@code spo}, so one seek
         * reads them all, and the exact matching keeps only what each key asks for. {@code null} if
         * no subject has two patterns.
         */
        List<RyaStatement> subjectRanges() {
            final Map<String, List<RyaStatement>> bySubject = new LinkedHashMap<>();
            for (final RyaStatement pattern : patterns) {
                bySubject.computeIfAbsent(pattern.getSubject().getData(), k -> new ArrayList<>()).add(pattern);
            }
            if (bySubject.size() == patterns.size()) {
                return null;
            }
            final List<RyaStatement> ranges = new ArrayList<>(bySubject.size());
            collapsedSubjects = 0;
            for (final List<RyaStatement> same : bySubject.values()) {
                if (same.size() == 1) {
                    ranges.add(same.get(0));
                } else {
                    final RyaStatement first = same.get(0);
                    ranges.add(new RyaStatement(first.getSubject(), null, null, first.getContext()));
                    collapsedSubjects++;
                }
            }
            return ranges;
        }
    }

}
