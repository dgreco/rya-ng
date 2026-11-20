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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
import org.tripatha.store.trace.Trace;
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
 * timestamp as well as by statement, as in a single lookup). But a key's range is its bound
 * parts in the table's order, each followed by a delimiter (on {@code spo}, {@code s 00 p 00} for
 * {@code (s, p, ?)}, {@code s 00 p 00 o 01} for {@code (s, p, o)}), and stored terms hold no
 * delimiter byte. So two ranges overlap only when one key's parts are a strict prefix of
 * another's, such as {@code (s, p, ?)} and {@code (s, p, o)}; equal parts give the same range,
 * which a scan reads once. A group with no such pair is disjoint: no cell can come twice, and the
 * scan doesn't keep track of the cells it has seen, nor encode their objects for that when no key
 * binds one (SCALE-STUDY.md O17). Only groups whose keys bind different numbers of parts are
 * checked for prefixes.
 *
 * <p>Such a group's cells also come, for each key, from that key's own range only, so its ranges
 * can be read by several threads (O16, {@code query.join.lookupthreads}): the group is cut into
 * chunks of at least {@code query.join.lookupthreadkeys} patterns (one per range, so no two chunks
 * read the same range), the query thread reads the first and
 * pool threads the others, each one reading, decoding and matching its chunk into a map of its
 * own, under the query's trace. The query thread then waits for every chunk, whatever
 * happened to the others, so no scan outlives the call, and merges the maps: each key's list
 * comes from one chunk, in the order a single scan would give it. The limit counts the whole
 * group's statements across the threads.
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
    /** Threads reading a batch's chunks: daemons, kept a minute when idle. */
    private static final ExecutorService LOOKUP_THREADS = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "tripatha-lookup");
        t.setDaemon(true);
        return t;
    });

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
        // A key asked for twice is answered once: its statements would otherwise be given to it twice.
        for (final LookupKey key : new LinkedHashSet<>(keys)) {
            final Target target = Target.of(key);
            if (target == null) {
                continue; // not something a single lookup would be asked for, or a full scan
            }
            final TABLE_LAYOUT layout = layoutOf(tripleContext, target.pattern, conf);
            if (layout == null) {
                continue;
            }
            groups.computeIfAbsent(new GroupId(layout, target.namedGraph), g -> new Group(g.layout())).add(key, target);
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
            final int chunks = group.disjoint() ? Math.min(conf.getJoinLookupThreads(),
                    group.patterns.size() / conf.getJoinLookupThreadKeys()) : 1;
            final long matched = chunks > 1 ? scanInChunks(dao, conf, group, chunks, results, limit - held)
                    : scan(dao, conf, group, group.patterns, results, limit - held, Long.MAX_VALUE);
            if (matched == OVER_LIMIT) {
                return null;
            }
            held += matched;
        }
        return results;
    }

    /**
     * How many statements a lookup of each key would return, counted by the store instead of read
     * (SCALE-STUDY.md O26): for the patterns whose object is used nowhere else
     * ({@link CountedPattern}). A key is counted only when it binds its subject, leaves its object
     * free and doesn't ask for the default graph alone; the others are left out of the map, and
     * their lookups made as usual.
     *
     * <p>Keys are grouped by table, graph and how many parts they bind, so the ranges of one call
     * can't overlap (two keys binding as many parts never do). Each range is counted once, its count
     * given to every key that shares it.
     */
    public static <C extends RdfCloudTripleStoreConfiguration> Map<LookupKey, Long> count(final RyaDAO<C> dao, final C conf,
            final Collection<LookupKey> keys) {
        final Map<List<Object>, List<RyaStatement>> patterns = new LinkedHashMap<>();
        final Map<List<Object>, Map<List<Object>, List<LookupKey>>> byPath = new LinkedHashMap<>();
        for (final LookupKey key : new LinkedHashSet<>(keys)) {
            final Target target = Target.of(key);
            if (target == null || !target.shape().subject() || target.shape().object() || target.shape().defaultGraphOnly()) {
                continue;
            }
            // A bound subject and a free object: an spo range, its subject then its predicate.
            final TABLE_LAYOUT layout = TABLE_LAYOUT.SPO;
            final List<Object> path = target.projection().predicate() == null ? List.of(target.projection().subject())
                    : List.of(target.projection().subject(), target.projection().predicate());
            // Its patterns share one context, which the count's spec takes (null for any graph).
            final List<Object> group = java.util.Arrays.asList(layout, target.namedGraph(), path.size());
            final Map<List<Object>, List<LookupKey>> paths = byPath.computeIfAbsent(group, g -> new LinkedHashMap<>());
            if (!paths.containsKey(path)) {
                patterns.computeIfAbsent(group, g -> new ArrayList<>()).add(target.pattern);
            }
            paths.computeIfAbsent(path, p -> new ArrayList<>()).add(key);
        }
        final Map<LookupKey, Long> counts = new HashMap<>();
        for (final Map.Entry<List<Object>, List<RyaStatement>> e : patterns.entrySet()) {
            // Each pattern is a range of its own, so the group can be counted in chunks on several
            // threads, as a disjoint group is read (O16).
            final int chunks = Math.min(conf.getJoinLookupThreads(), e.getValue().size() / conf.getJoinLookupThreadKeys());
            final long[] found = chunks > 1 ? countInChunks(dao, conf, e.getValue(), chunks)
                    : dao.getQueryEngine().countBatch(e.getValue(), conf);
            if (found == null) {
                continue; // an engine that can't count: the lookups are made as usual
            }
            int i = 0;
            for (final List<LookupKey> sharing : byPath.get(e.getKey()).values()) {
                for (final LookupKey key : sharing) {
                    counts.put(key, found[i]);
                }
                i++;
            }
        }
        return counts;
    }

    /** What {@link #fetchAndCount} got: the statements (null when over the limit) and the counts. */
    public record Fetched(Map<LookupKey, List<Statement>> statements, Map<LookupKey, Long> counts) {
    }

    /**
     * Fetches some lookups' statements and counts others' at the same time (SCALE-STUDY.md O26):
     * the count runs on a lookup thread, under the query's trace, while this thread reads, so a
     * window waits for the longer of the two instead of both. A store's count of its own is one
     * more round trip, and on the Accumulo test cluster one after the other made a chain of two
     * counted links 91 % slower. Both are done before this returns or throws.
     */
    public static <C extends RdfCloudTripleStoreConfiguration> Fetched fetchAndCount(final RyaDAO<C> dao, final C conf,
            final Collection<LookupKey> keys, final Collection<LookupKey> counted, final long limit) {
        if (counted.isEmpty()) {
            return new Fetched(keys.isEmpty() ? Map.of() : fetch(dao, conf, keys, limit), Map.of());
        }
        if (keys.isEmpty()) {
            return new Fetched(Map.of(), count(dao, conf, counted));
        }
        final Trace trace = StoreTrace.current();
        final String why = trace == null ? "" : trace.reason();
        final String reason = (why.isEmpty() ? "" : why + " › ") + "counted on a thread of its own";
        final java.util.concurrent.FutureTask<Map<LookupKey, Long>> counting = new java.util.concurrent.FutureTask<>(() -> {
            try (StoreTrace.Scope joined = StoreTrace.join(trace, reason)) {
                return count(dao, conf, counted);
            }
        });
        try {
            LOOKUP_THREADS.execute(counting);
        } catch (final RuntimeException e) {
            counting.run(); // not started: count here
        }
        Map<LookupKey, List<Statement>> statements = null;
        Throwable failed = null;
        try {
            statements = fetch(dao, conf, keys, limit);
        } catch (final RuntimeException | Error e) {
            failed = e;
        }
        Map<LookupKey, Long> counts = null;
        boolean interrupted = false;
        while (true) {
            try {
                counts = counting.get();
                break;
            } catch (final InterruptedException e) {
                interrupted = true;
            } catch (final java.util.concurrent.ExecutionException e) {
                if (failed == null) {
                    failed = e.getCause();
                }
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failed instanceof RuntimeException e) {
            throw e;
        }
        if (failed instanceof Error e) {
            throw e;
        }
        if (failed != null) {
            throw new IllegalStateException(failed);
        }
        return new Fetched(statements, counts);
    }

    /**
     * Counts patterns in {@code chunks} parts at once, the first on this thread, each under the
     * query's trace; every part is finished before this returns or throws.
     *
     * @return the counts in the patterns' order, or null if the engine can't count
     */
    private static <C extends RdfCloudTripleStoreConfiguration> long[] countInChunks(final RyaDAO<C> dao, final C conf,
            final List<RyaStatement> patterns, final int chunks) {
        final List<List<RyaStatement>> cut = new ArrayList<>(chunks);
        for (int c = 0; c < chunks; c++) {
            cut.add(patterns.subList(c * patterns.size() / chunks, (c + 1) * patterns.size() / chunks));
        }
        final long[][] parts = new long[chunks][];
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(chunks - 1);
        final Trace trace = StoreTrace.current();
        final String why = trace == null ? "" : trace.reason();
        for (int c = 1; c < chunks; c++) {
            final int part = c;
            final String reason = (why.isEmpty() ? "" : why + " › ") + "part " + (c + 1) + " of " + chunks + ", on a thread of its own";
            try {
                LOOKUP_THREADS.execute(() -> {
                    try (StoreTrace.Scope joined = StoreTrace.join(trace, reason)) {
                        parts[part] = dao.getQueryEngine().countBatch(cut.get(part), conf);
                    } catch (final Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            } catch (final RuntimeException e) {
                failure.compareAndSet(null, e);
                done.countDown();
            }
        }
        try {
            parts[0] = dao.getQueryEngine().countBatch(cut.get(0), conf);
        } catch (final RuntimeException | Error e) {
            failure.compareAndSet(null, e);
        } finally {
            awaitUninterruptibly(done, stop);
        }
        final Throwable failed = failure.get();
        if (failed instanceof RuntimeException e) {
            throw e;
        }
        if (failed instanceof Error e) {
            throw e;
        }
        if (failed != null) {
            throw new IllegalStateException(failed);
        }
        final long[] out = new long[patterns.size()];
        int at = 0;
        for (final long[] part : parts) {
            if (part == null) {
                return null;
            }
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
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
        final boolean disjoint = patterns == group.patterns && group.disjoint();
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

    /**
     * Reads a disjoint group's patterns in {@code chunks} parts at once, the first on this thread,
     * and merges what each part matched. Every part is finished before this returns or throws.
     *
     * @return the statements given to keys, or {@link #OVER_LIMIT} if more than {@code limit}
     */
    private static <C extends RdfCloudTripleStoreConfiguration> long scanInChunks(final RyaDAO<C> dao, final C conf,
            final Group group, final int requested, final Map<LookupKey, List<Statement>> results, final long limit) {
        // Patterns sharing a range stay together, so fewer chunks than asked may have patterns.
        final List<List<RyaStatement>> cut = group.chunks(requested);
        final int chunks = cut.size();
        final List<Map<LookupKey, List<Statement>>> parts = new ArrayList<>(chunks);
        final AtomicLong held = new AtomicLong();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(chunks - 1);
        final Trace trace = StoreTrace.current();
        // The chunks' scans say what this thread is doing (an entity prefetch's window, say), and which part they read.
        final String why = trace == null ? "" : trace.reason();
        for (int c = 0; c < chunks; c++) {
            parts.add(new HashMap<>());
        }
        for (int c = 1; c < chunks; c++) {
            final List<RyaStatement> chunk = cut.get(c);
            final Map<LookupKey, List<Statement>> part = parts.get(c);
            final String reason = (why.isEmpty() ? "" : why + " › ") + "part " + (c + 1) + " of " + chunks + ", on a thread of its own";
            try {
                LOOKUP_THREADS.execute(() -> {
                    try (StoreTrace.Scope joined = StoreTrace.join(trace, reason)) {
                        scanPart(dao, conf, group, chunk, part, held, limit, stop);
                    } catch (final Throwable t) {
                        failure.compareAndSet(null, t);
                        stop.set(true);
                    } finally {
                        done.countDown();
                    }
                });
            } catch (final RuntimeException e) {
                // Not started: count it as done, and stop the others.
                failure.compareAndSet(null, e);
                stop.set(true);
                done.countDown();
            }
        }
        try {
            scanPart(dao, conf, group, cut.get(0), parts.get(0), held, limit, stop);
        } catch (final RuntimeException | Error e) {
            failure.compareAndSet(null, e);
            stop.set(true);
        } finally {
            awaitUninterruptibly(done, stop);
        }
        final Throwable failed = failure.get();
        if (failed instanceof RuntimeException e) {
            throw e;
        }
        if (failed instanceof Error e) {
            throw e;
        }
        if (failed != null) {
            throw new IllegalStateException(failed);
        }
        if (held.get() > limit) {
            return OVER_LIMIT;
        }
        for (final LookupKey key : group.keys) {
            results.put(key, new ArrayList<>());
        }
        for (final Map<LookupKey, List<Statement>> part : parts) {
            part.forEach((key, statements) -> results.get(key).addAll(statements));
        }
        return held.get();
    }

    /**
     * One chunk of a disjoint group: its cells matched to the group's keys into {@code part}, no
     * cell check needed. Stops when another chunk failed, or when the group holds over {@code limit}.
     */
    private static <C extends RdfCloudTripleStoreConfiguration> void scanPart(final RyaDAO<C> dao, final C conf, final Group group,
            final List<RyaStatement> patterns, final Map<LookupKey, List<Statement>> part, final AtomicLong held, final long limit,
            final AtomicBoolean stop) {
        final boolean objects = group.index.keySet().stream().anyMatch(Shape::object);
        try (CloseableIteration<RyaStatement> found = dao.getQueryEngine().batchQuery(patterns, conf)) {
            while (!stop.get() && found.hasNext()) {
                final RyaStatement stored = found.next();
                final ByteBuffer object = objects ? encode(stored.getObject()) : null;
                if (objects && object == null) {
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
                        part.computeIfAbsent(key, k -> new ArrayList<>()).add(converted);
                        if (held.incrementAndGet() > limit) {
                            stop.set(true);
                            return;
                        }
                    }
                }
            }
        }
    }

    /** Waits for every chunk; an interrupt asks them to stop, but they are still waited for, and the interrupt kept. */
    private static void awaitUninterruptibly(final CountDownLatch done, final AtomicBoolean stop) {
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (final InterruptedException e) {
                interrupted = true;
                stop.set(true);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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
        final byte[][] parts = serialized(value);
        return parts == null ? null : joined(parts);
    }

    /** A value's data and type, as the rows hold them; null if it has none or can't be encoded. */
    private static byte[][] serialized(final RyaType value) {
        if (value == null) {
            return null;
        }
        try {
            return TYPES.serializeType(value);
        } catch (final Exception e) {
            return null;
        }
    }

    private static ByteBuffer joined(final byte[][] parts) {
        final byte[] bytes = new byte[parts[0].length + parts[1].length];
        System.arraycopy(parts[0], 0, bytes, 0, parts[0].length);
        System.arraycopy(parts[1], 0, bytes, parts[0].length, parts[1].length);
        return ByteBuffer.wrap(bytes);
    }

    /** A key translated to what Tripatha would query; {@code objectData} is its object's data, without the type, as its range holds it. */
    private record Target(RyaStatement pattern, Shape shape, Projection projection, String namedGraph, ByteBuffer objectData) {
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
            final byte[][] parts = serialized(o);
            if (o != null && parts == null) {
                return null;
            }
            final Shape shape = new Shape(s != null, p != null, o != null, defaultGraphOnly);
            final Projection projection = new Projection(s == null ? null : s.getData(), p == null ? null : p.getData(),
                    parts == null ? null : joined(parts));
            return new Target(new RyaStatement(s, p, o, c), shape, projection, c == null ? null : c.getData(),
                    parts == null ? null : ByteBuffer.wrap(parts[0]));
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
        final TABLE_LAYOUT layout;
        final List<LookupKey> keys = new ArrayList<>();
        final List<RyaStatement> patterns = new ArrayList<>();
        private Boolean disjoint;
        /**
         * Each pattern's bound parts in the table's order: what its range holds, and so its
         * identity. A group keeps one pattern per range, whatever else tells keys apart (an
         * object's type, the default graph or any graph, the creation time a RyaStatement keeps),
         * so no scan, and no two chunks, read a range twice.
         */
        private final List<List<Object>> paths = new ArrayList<>();
        private final Set<List<Object>> distinctPaths = new HashSet<>();

        Group(final TABLE_LAYOUT layout) {
            this.layout = layout;
        }
        /** How many subjects {@link #subjectRanges()} read whole. */
        int collapsedSubjects;
        final Map<Shape, Map<Projection, List<LookupKey>>> index = new LinkedHashMap<>();

        /**
         * Whether no key's range overlaps another's: no key's parts, in the table's order, are a
         * strict prefix of another key's. Keys binding as many parts can't be, so only a group with
         * keys of different lengths is looked at pattern by pattern.
         */
        boolean disjoint() {
            if (disjoint == null) {
                disjoint = computeDisjoint();
            }
            return disjoint;
        }

        private boolean computeDisjoint() {
            final Set<Integer> lengths = new HashSet<>();
            for (final Shape shape : index.keySet()) {
                final int length = length(shape);
                if (length < 0) {
                    return false;
                }
                lengths.add(length);
            }
            if (lengths.size() <= 1) {
                return true;
            }
            for (final List<Object> path : distinctPaths) {
                for (int i = 1; i < path.size(); i++) {
                    if (distinctPaths.contains(path.subList(0, i))) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** How many parts a shape binds in the table's order before the first free one; -1 for a layout not looked at. */
        private int length(final Shape shape) {
            final boolean[] order = switch (layout) {
                case SPO -> new boolean[] {shape.subject(), shape.predicate(), shape.object()};
                case PO -> new boolean[] {shape.predicate(), shape.object(), shape.subject()};
                case OSP -> new boolean[] {shape.object(), shape.subject(), shape.predicate()};
                default -> null;
            };
            if (order == null) {
                return -1;
            }
            int n = 0;
            while (n < order.length && order[n]) {
                n++;
            }
            return n;
        }

        /** A pattern's bound parts in the table's order, up to the first free one: what its range holds. */
        private List<Object> path(final Target t) {
            final Object s = t.projection().subject();
            final Object p = t.projection().predicate();
            final Object o = t.objectData();
            final Object[] order = switch (layout) {
                case SPO -> new Object[] {s, p, o};
                case PO -> new Object[] {p, o, s};
                default -> new Object[] {o, s, p};
            };
            final List<Object> path = new ArrayList<>(3);
            for (final Object part : order) {
                if (part == null) {
                    break;
                }
                path.add(part);
            }
            return path;
        }

        /**
         * The patterns cut into at most {@code n} chunks, in order, none of them empty. Each range
         * has one pattern, so no range is read by two chunks.
         */
        List<List<RyaStatement>> chunks(final int n) {
            final List<List<RyaStatement>> out = new ArrayList<>(n);
            final int size = patterns.size();
            for (int c = 0; c < n; c++) {
                out.add(patterns.subList(c * size / n, (c + 1) * size / n));
            }
            out.removeIf(List::isEmpty);
            return out;
        }

        void add(final LookupKey key, final Target target) {
            keys.add(key);
            final List<Object> path = path(target);
            if (distinctPaths.add(path)) {
                patterns.add(target.pattern);
                paths.add(path);
                disjoint = null;
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
