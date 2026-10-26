package io.github.dgreco.rya.rdftriplestore.evaluation;

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

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.api.query.strategy.TriplePatternStrategy;
import io.github.dgreco.rya.api.resolver.RdfToRyaConversions;
import io.github.dgreco.rya.api.resolver.RyaContext;
import io.github.dgreco.rya.api.resolver.RyaToRdfConversions;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
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
 * timestamp as well as by statement, as in a single lookup).
 */
public final class BatchLookup {
    private static final RyaContext TYPES = RyaContext.getInstance();

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
        for (final Group group : groups.values()) {
            for (final LookupKey key : group.keys) {
                results.put(key, new ArrayList<>());
            }
            final Set<CellId> seen = new HashSet<>();
            try (CloseableIteration<RyaStatement> found = dao.getQueryEngine().batchQuery(group.patterns, conf)) {
                while (found.hasNext()) {
                    final RyaStatement stored = found.next();
                    final ByteBuffer object = encode(stored.getObject());
                    if (object == null || !seen.add(CellId.of(stored, object))) {
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
                                return null;
                            }
                        }
                    }
                }
            }
        }
        return results;
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

    /** A key translated to what Rya would query. */
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
            final RyaType o = key.object() == null ? null : RdfToRyaConversions.convertValue(key.object());
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
    }

}
