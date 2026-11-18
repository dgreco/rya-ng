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
package org.tripatha.rdftriplestore.evaluation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MutableBindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.resolver.RdfToRyaConversions;
import org.tripatha.api.resolver.RyaToRdfConversions;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.Trace;

/**
 * Evaluates a {@link SubjectMergeJoin}: reads each pattern's subjects in order, one range of
 * {@code po} each, and returns the subjects found in every list.
 *
 * <p>The rows are those of the patterns' join. A subject with {@code n1} statements in the first
 * list (several graphs, or one statement under several labels), {@code n2} in the second and so
 * on gives {@code n1 × n2 × …} rows, as the nested loop would. When the subject is already bound,
 * as on the right side of an OPTIONAL evaluated per row, the patterns are joined as before.
 *
 * <p><b>Subjects only</b> (SCALE-STUDY.md O24): the lists give each cell's subject as its UTF-8
 * bytes, without decoding statements, and they are compared as unsigned bytes, which is code
 * point order and so the stored order. Only matching subjects become values.
 *
 * <p><b>Slices</b> (O24): up to {@code query.join.mergeslices} slices of the subject space, cut at
 * the store's split points ({@code RyaStoreDAO.subjectBoundaries}). Every list is read once per
 * slice, as the range of its subjects in that slice, and each slice is intersected on a thread of
 * its own. Its matches come back in chunks, each in subject order. When lookups follow (the merge
 * is a join's or an OPTIONAL's left side), the slices are emitted in subject order, each through a
 * bounded queue of its own: the lookups then read nearby rows, and they are slower than the slices.
 * Otherwise every slice feeds one queue and the chunks come as they are ready: emitted in order, a
 * later slice could get only its queue ahead, and at 100M triples 4 slices were 11 % faster than
 * one (SCALE-STUDY.md 4.11). The scans are all opened on the query thread, so the trace reports
 * them; a slice's thread reads its scans and closes them.
 *
 * <p><b>The entity index</b> (O23): when it is READY, each pattern's list is read shard by shard
 * from it instead: every shard holds all the postings of its subjects, so the intersection of a
 * star is the union of the shards' intersections. The shards are cut into contiguous groups, one
 * per thread (up to {@code query.join.mergeslices}); each thread opens a shard's scans, under the
 * query's trace ({@link StoreTrace#join}), intersects them and closes them, shard after shard.
 * Rows then come in shard order, and in subject order within a shard.
 *
 * <p><b>By the store</b> (O23): when it can (Accumulo with Tripatha's iterator jar on its servers,
 * RocksDB with native seeks), each thread's group of shards is intersected by the store instead,
 * and only the matching subjects, with their counts, come back ({@code Store.intersect}).
 */
final class SubjectMergeStep implements QueryEvaluationStep {
    /** Threads intersecting slices: daemons, kept a minute when idle. */
    static final ExecutorService SLICE_THREADS = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "tripatha-merge-slice");
        t.setDaemon(true);
        return t;
    });
    /**
     * When lookups follow a merge read from the entity index, its matches are emitted in runs
     * sorted by subject: a first run of 1,024 (so a LIMIT's first rows come early), then runs of
     * up to 262,144. Shards are hash partitions, so matches come in no useful order, and a window
     * of lookups taken from a long sorted run reads few tablets and nearby rows. Shorter runs
     * scatter the lookups: on Accumulo, runs doubling from 1,024 made class-value-mid 2× slower.
     */
    private static final int FIRST_RUN = 1024;
    private static final int MAX_RUN = 1 << 18;
    /** Chunks of matches a slice's thread may hold ahead of the query thread. */
    private static final int QUEUED = 8;
    /**
     * Matches are handed over in chunks, from 32 (so a LIMIT's first rows come early) doubling
     * to 1,024: one at a time, a query thread waiting on an empty queue costs a park and an unpark
     * per match on both threads.
     */
    private static final int FIRST_CHUNK = 32;
    private static final int MAX_CHUNK = 1024;

    private final SubjectMergeJoin node;
    private final StoreTripleSource<?> source;
    private final QueryEvaluationStep nestedLoop;
    private final QueryEvaluationContext context;
    private final Predicate<BindingSet> subjectBound;
    private final BiConsumer<Value, MutableBindingSet> setSubject;

    /**
     * @param nestedLoop the plain join of the node's patterns, for rows that bind the subject
     */
    SubjectMergeStep(final SubjectMergeJoin node, final StoreTripleSource<?> source, final QueryEvaluationStep nestedLoop,
            final QueryEvaluationContext context) {
        this.node = node;
        this.source = source;
        this.nestedLoop = nestedLoop;
        this.context = context;
        this.subjectBound = context.hasBinding(node.getSubjectName());
        this.setSubject = context.setBinding(node.getSubjectName());
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(final BindingSet bindings) {
        if (subjectBound.test(bindings)) {
            return nestedLoop.evaluate(bindings);
        }
        return new Merge(bindings);
    }

    /** The rows of the intersection, opened on the first read. */
    private final class Merge extends LookAheadIteration<BindingSet> {
        private final BindingSet bindings;
        private List<Intersection> slices;
        /** One per slice when they run on threads of their own; null for one slice. */
        private List<SliceTask> tasks;
        private int current;
        private Value subject;
        private long repeats;
        /**
         * Matches from every thread as they come: the entity index's, or the slices of po when no
         * lookups follow; null when each slice has its own queue, or there is one slice.
         */
        private FanIn shared;
        /** Whether to emit those matches in sorted runs, and the current run. */
        private boolean sortMatches;
        private java.util.Iterator<Match> run;
        private int runSize = FIRST_RUN;

        Merge(final BindingSet bindings) {
            this.bindings = bindings;
        }

        @Override
        protected BindingSet getNextElement() {
            if (slices == null) {
                open();
            }
            while (repeats == 0) {
                if (!nextMatch()) {
                    return null;
                }
            }
            repeats--;
            final MutableBindingSet row = context.createBindingSet(bindings);
            setSubject.accept(subject, row);
            return row;
        }

        private void open() {
            final List<StatementPattern> patterns = node.getPatterns();
            final EntityIndex entity = source.entityIndex();
            if (entity != null) {
                openShards(entity, patterns);
                return;
            }
            final int wanted = Math.max(1, source.getConf().getJoinMergeSlices());
            final List<byte[]> bounds = wanted > 1 ? source.subjectBoundaries(wanted) : List.of();
            slices = new ArrayList<>(bounds.size() + 1);
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "merge join: the subjects of " + patterns.size()
                    + " constant patterns on ?" + node.getSubjectName() + ", read in order and intersected"
                    + (bounds.isEmpty() ? "" : ", in " + (bounds.size() + 1) + " slices"))) {
                for (int i = 0; i <= bounds.size(); i++) {
                    final byte[] from = i == 0 ? null : bounds.get(i - 1);
                    final byte[] to = i == bounds.size() ? null : bounds.get(i);
                    final List<CloseableIteration<byte[]>> lists = new ArrayList<>(patterns.size());
                    slices.add(new Intersection(lists));
                    for (final StatementPattern sp : patterns) {
                        lists.add(source.subjectsInOrder((IRI) sp.getPredicateVar().getValue(), sp.getObjectVar().getValue(),
                                from, to));
                    }
                }
            } catch (final RuntimeException e) {
                for (final Intersection slice : slices) {
                    slice.closeQuietly(e);
                }
                slices = List.of();
                throw e;
            }
            if (slices.size() > 1) {
                // Lookups that follow read nearby rows in subject order, and they are slower than the
                // slices anyway. Otherwise the slices share one queue, so they all run: emitted in
                // order, a later slice gets only its queue's few thousand matches ahead.
                final BlockingQueue<Object> together = lookupsFollow(node) ? null : new ArrayBlockingQueue<>(QUEUED * slices.size());
                if (together != null) {
                    shared = new FanIn(together, slices.size());
                }
                tasks = new ArrayList<>(slices.size());
                for (final Intersection slice : slices) {
                    final SliceTask task = new SliceTask(List.of(() -> slice), List.of(slice), null, null,
                            together != null ? together : new ArrayBlockingQueue<>(QUEUED));
                    tasks.add(task);
                    SLICE_THREADS.execute(task);
                }
            }
        }

        /** The star read from the entity index: its shards in groups, each on a thread of its own. */
        private void openShards(final EntityIndex entity, final List<StatementPattern> patterns) {
            final List<byte[]> terms = entityTerms(patterns);
            for (int i = 0; i < patterns.size(); i++) {
                source.countLookup();
            }
            final List<byte[]> shards = entity.shardRows();
            final int threads = Math.max(1, Math.min(source.getConf().getJoinMergeSlices(), shards.size()));
            final boolean byStore = source.entityIntersectsInServers();
            final Trace trace = StoreTrace.current();
            final String reason = "merge join: the subjects of " + patterns.size() + " constant patterns on ?"
                    + node.getSubjectName() + (byStore ? ", intersected by the store" : ", intersected shard by shard")
                    + " in the entity index (" + shards.size() + " shards, " + threads + " threads)";
            slices = List.of();
            sortMatches = lookupsFollow(node);
            // Shards are hash partitions: their order doesn't matter, so every thread feeds one queue.
            final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUED * threads);
            shared = new FanIn(queue, threads);
            tasks = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                final List<byte[]> group = shards.subList(t * shards.size() / threads, (t + 1) * shards.size() / threads);
                final List<Supplier<Unit>> units = new ArrayList<>();
                if (byStore) {
                    // The whole group in one call: the store intersects its shards (in its servers, or with seeks).
                    units.add(() -> new Matches(source.entityMatches(group, terms)));
                    tasks.add(new SliceTask(units, List.of(), trace, reason, queue));
                    continue;
                }
                for (final byte[] shard : group) {
                    units.add(() -> {
                        final List<CloseableIteration<byte[]>> lists = new ArrayList<>(terms.size());
                        final Intersection intersection = new Intersection(lists);
                        try {
                            for (final byte[] term : terms) {
                                lists.add(source.entitySubjects(shard, term));
                            }
                        } catch (final RuntimeException e) {
                            intersection.closeQuietly(e);
                            throw e;
                        }
                        return intersection;
                    });
                }
                tasks.add(new SliceTask(units, List.of(), trace, reason, queue));
            }
            for (final SliceTask task : tasks) {
                SLICE_THREADS.execute(task);
            }
        }

        /** Moves to the next subject every list has, and how many rows it gives; false when none is left. */
        private boolean nextMatch() {
            if (tasks == null) {
                final Intersection only = slices.isEmpty() ? null : slices.get(0);
                if (only == null || !only.advance()) {
                    return false;
                }
                subject = value(only.subject());
                repeats = only.repeats();
                return true;
            }
            if (shared != null) {
                final Match match = sortMatches ? nextSorted() : shared.take();
                if (match == null) {
                    return false;
                }
                subject = value(match.subject());
                repeats = match.repeats();
                return true;
            }
            while (current < tasks.size()) {
                final Match match = tasks.get(current).take();
                if (match == null) {
                    current++;
                    continue;
                }
                subject = value(match.subject());
                repeats = match.repeats();
                return true;
            }
            return false;
        }

        /**
         * Whether lookups follow the merge: it is a join's or an OPTIONAL's left side, maybe
         * under filters and BINDs. Then they get subject order.
         */
        private static boolean lookupsFollow(final SubjectMergeJoin merge) {
            org.eclipse.rdf4j.query.algebra.QueryModelNode child = merge;
            org.eclipse.rdf4j.query.algebra.QueryModelNode parent = merge.getParentNode();
            while (parent instanceof org.eclipse.rdf4j.query.algebra.Filter
                    || parent instanceof org.eclipse.rdf4j.query.algebra.Extension) {
                child = parent;
                parent = parent.getParentNode();
            }
            return (parent instanceof org.eclipse.rdf4j.query.algebra.Join join && join.getLeftArg() == child)
                    || (parent instanceof org.eclipse.rdf4j.query.algebra.LeftJoin left && left.getLeftArg() == child);
        }

        /** The next match of the current sorted run, reading the next run when it is done; null at the end. */
        private Match nextSorted() {
            if (run == null || !run.hasNext()) {
                final List<Match> batch = new ArrayList<>(runSize);
                Match match;
                while (batch.size() < runSize && (match = shared.take()) != null) {
                    batch.add(match);
                }
                if (batch.isEmpty()) {
                    return null;
                }
                batch.sort((a, b) -> Arrays.compareUnsigned(a.subject(), b.subject()));
                run = batch.iterator();
                runSize = MAX_RUN;
            }
            return run.next();
        }

        @Override
        protected void handleClose() {
            final List<Intersection> opened = slices;
            if (opened == null) {
                return;
            }
            if (tasks == null) {
                RuntimeException failure = null;
                for (final Intersection slice : opened) {
                    failure = slice.closeQuietly(failure);
                }
                if (failure != null) {
                    throw failure;
                }
                return;
            }
            for (final SliceTask task : tasks) {
                task.stop();
            }
        }
    }

    /** The entity index's terms of a star's constant patterns. */
    static List<byte[]> entityTerms(final List<StatementPattern> patterns) {
        final List<byte[]> terms = new ArrayList<>(patterns.size());
        for (final StatementPattern sp : patterns) {
            terms.add(EntityIndex.term(RdfToRyaConversions.convertIRI((IRI) sp.getPredicateVar().getValue()),
                    RdfToRyaConversions.convertValue(sp.getObjectVar().getValue())));
        }
        return terms;
    }

    private static Value value(final byte[] subject) {
        return RyaToRdfConversions.convertResource(new RyaIRI(new String(subject, StandardCharsets.UTF_8)));
    }

    /** A subject every list of a slice has, and how many rows it gives. */
    private record Match(byte[] subject, long repeats) {
    }

    /** A slice's thread failed: its exception, for the query thread. */
    private record Failure(Throwable cause) {
    }

    /** The end of a slice's matches. */
    private static final Object END = new Object();

    /** A source of matches: subjects with the rows each gives. Used by one thread at a time. */
    interface Unit {
        /** @return false when there is no match left */
        boolean advance();

        byte[] subject();

        long repeats();

        /** Closes what the unit reads; returns the first failure, added to {@code failure} when there is one. */
        RuntimeException closeQuietly(RuntimeException failure);
    }

    /** The matches a store intersected ({@code Store.intersect}). */
    static final class Matches implements Unit {
        private final CloseableIteration<EntityIndex.Match> matches;
        private EntityIndex.Match current;

        Matches(final CloseableIteration<EntityIndex.Match> matches) {
            this.matches = matches;
        }

        @Override
        public boolean advance() {
            if (!matches.hasNext()) {
                return false;
            }
            current = matches.next();
            return true;
        }

        @Override
        public byte[] subject() {
            return current.subject();
        }

        @Override
        public long repeats() {
            return current.count();
        }

        @Override
        public RuntimeException closeQuietly(final RuntimeException failure) {
            try {
                matches.close();
                return failure;
            } catch (final RuntimeException e) {
                if (failure == null) {
                    return e;
                }
                failure.addSuppressed(e);
                return failure;
            }
        }
    }

    /**
     * The subjects every list of one slice has, in order, each with the product of its counts in
     * the lists. Used by one thread at a time.
     */
    static final class Intersection implements Unit {
        private final List<CloseableIteration<byte[]>> lists;
        private byte[][] heads;
        private byte[] subject;
        private long repeats;

        Intersection(final List<CloseableIteration<byte[]>> lists) {
            this.lists = lists;
        }

        @Override
        public byte[] subject() {
            return subject;
        }

        @Override
        public long repeats() {
            return repeats;
        }

        /**
         * Moves every list to the next subject they all have, and counts that subject's
         * statements in each, past which they are left.
         *
         * @return false when a list has run out
         */
        @Override
        public boolean advance() {
            if (heads == null) {
                heads = new byte[lists.size()][];
                for (int i = 0; i < heads.length; i++) {
                    heads[i] = next(i);
                }
            }
            byte[] target = null;
            for (final byte[] head : heads) {
                if (head == null) {
                    return false;
                }
                if (target == null || Arrays.compareUnsigned(head, target) > 0) {
                    target = head;
                }
            }
            boolean agreed;
            do {
                agreed = true;
                for (int i = 0; i < heads.length; i++) {
                    while (Arrays.compareUnsigned(heads[i], target) < 0) {
                        heads[i] = next(i);
                        if (heads[i] == null) {
                            return false;
                        }
                    }
                    if (!Arrays.equals(heads[i], target)) {
                        target = heads[i];
                        agreed = false;
                    }
                }
            } while (!agreed);
            long rows = 1;
            for (int i = 0; i < heads.length; i++) {
                long count = 0;
                while (heads[i] != null && Arrays.equals(heads[i], target)) {
                    count++;
                    heads[i] = next(i);
                }
                rows = Math.multiplyExact(rows, count);
            }
            subject = target;
            repeats = rows;
            return true;
        }

        private byte[] next(final int list) {
            final CloseableIteration<byte[]> it = lists.get(list);
            return it.hasNext() ? it.next() : null;
        }

        /** Closes every list; returns the first failure, added to {@code failure} when there is one. */
        @Override
        public RuntimeException closeQuietly(RuntimeException failure) {
            for (final CloseableIteration<byte[]> it : lists) {
                try {
                    it.close();
                } catch (final RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else if (failure != e) {
                        failure.addSuppressed(e);
                    }
                }
            }
            return failure;
        }
    }

    /**
     * The chunks of several tasks that share one queue, in the order they come, for the query
     * thread: each task ends with its own {@code END}.
     */
    private static final class FanIn {
        private final BlockingQueue<Object> queue;
        private int running;
        private List<Match> chunk = List.of();
        private int read;

        FanIn(final BlockingQueue<Object> queue, final int tasks) {
            this.queue = queue;
            this.running = tasks;
        }

        /** @return the next match of any task, or null when every task has ended */
        @SuppressWarnings("unchecked")
        Match take() {
            while (true) {
                if (read < chunk.size()) {
                    return chunk.get(read++);
                }
                if (running == 0) {
                    return null;
                }
                final Object item;
                try {
                    item = queue.take();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new QueryEvaluationException("Interrupted while merging", e);
                }
                if (item == END) {
                    running--;
                } else if (item instanceof List<?> next) {
                    chunk = (List<Match>) next;
                    read = 0;
                } else if (item instanceof Failure f) {
                    running = 0;
                    if (f.cause() instanceof RuntimeException r) {
                        throw r;
                    }
                    if (f.cause() instanceof Error err) {
                        throw err;
                    }
                    throw new QueryEvaluationException(f.cause());
                } else {
                    throw new IllegalStateException("Unexpected item: " + item);
                }
            }
        }
    }

    /**
     * Units of work (slices, or shards of the entity index) intersected one after the other on a
     * thread of its own. Whoever claims the task first owns its scans: its thread when it starts,
     * or the query thread when the merge is closed before it started.
     */
    private static final class SliceTask implements Runnable {
        private final List<Supplier<Unit>> units;
        /** Units opened on the query thread, closed by whoever owns them. */
        private final List<? extends Unit> preopened;
        private final Trace trace;
        private final String reason;
        private final BlockingQueue<Object> queue;
        /** The chunk the query thread is reading, and where in it. */
        private List<Match> chunk = List.of();
        private int read;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final CountDownLatch done = new CountDownLatch(1);

        /**
         * @param preopened the units already opened (closed here if never reached)
         * @param trace the trace the units' scans report to, or null when they are opened traced already
         * @param reason the reason those scans give
         * @param queue where its chunks go: its own, or one it shares with tasks read through a {@link FanIn}
         */
        SliceTask(final List<Supplier<Unit>> units, final List<? extends Unit> preopened, final Trace trace,
                final String reason, final BlockingQueue<Object> queue) {
            this.queue = queue;
            this.units = units;
            this.preopened = preopened;
            this.trace = trace;
            this.reason = reason;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            Unit current = null;
            int next = 0;
            try (StoreTrace.Scope joined = StoreTrace.join(trace, reason)) {
                int size = FIRST_CHUNK;
                List<Match> matches = new ArrayList<>(size);
                while (!stopped.get() && next < units.size()) {
                    current = units.get(next++).get();
                    while (!stopped.get() && current.advance()) {
                        matches.add(new Match(current.subject(), current.repeats()));
                        if (matches.size() >= size) {
                            put(matches);
                            size = Math.min(MAX_CHUNK, size * 2);
                            matches = new ArrayList<>(size);
                        }
                    }
                    final RuntimeException failure = current.closeQuietly(null);
                    current = null;
                    if (failure != null) {
                        throw failure;
                    }
                }
                if (!matches.isEmpty()) {
                    put(matches);
                }
                put(END);
            } catch (final Throwable t) {
                put(new Failure(t));
            } finally {
                if (current != null) {
                    current.closeQuietly(null);
                }
                for (int i = next; i < preopened.size(); i++) {
                    preopened.get(i).closeQuietly(null);
                }
                done.countDown();
            }
        }

        private void put(final Object item) {
            try {
                while (!stopped.get()) {
                    if (queue.offer(item, 50, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** @return the slice's next match, or null at its end */
        @SuppressWarnings("unchecked")
        Match take() {
            if (read < chunk.size()) {
                return chunk.get(read++);
            }
            final Object item;
            try {
                item = queue.take();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QueryEvaluationException("Interrupted while merging", e);
            }
            if (item == END) {
                return null;
            }
            if (item instanceof List<?> next) {
                chunk = (List<Match>) next;
                read = 1;
                return chunk.get(0);
            }
            if (item instanceof Failure f) {
                if (f.cause() instanceof RuntimeException r) {
                    throw r;
                }
                if (f.cause() instanceof Error err) {
                    throw err;
                }
                throw new QueryEvaluationException(f.cause());
            }
            throw new IllegalStateException("Unexpected item: " + item);
        }

        /** Stops the task and waits until its scans are closed. */
        void stop() {
            stopped.set(true);
            if (claimed.compareAndSet(false, true)) {
                // Never started: the scans opened for it are ours to close.
                RuntimeException failure = null;
                for (final Unit unit : preopened) {
                    failure = unit.closeQuietly(failure);
                }
                done.countDown();
                if (failure != null) {
                    throw failure;
                }
                return;
            }
            try {
                done.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
