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
 * its own. Its matches come back in chunks through a bounded queue, and the slices are emitted in subject
 * order, so the rows are those of one slice, in the same order. The scans are all opened on the
 * query thread, so the trace reports them; a slice's thread reads its scans and closes them.
 *
 * <p><b>The entity index</b> (O23): when it is READY, each pattern's list is read shard by shard
 * from it instead: every shard holds all the postings of its subjects, so the intersection of a
 * star is the union of the shards' intersections. The shards are cut into contiguous groups, one
 * per thread (up to {@code query.join.mergeslices}); each thread opens a shard's scans, under the
 * query's trace ({@link StoreTrace#join}), intersects them and closes them, shard after shard.
 * Rows then come in shard order, and in subject order within a shard.
 */
final class SubjectMergeStep implements QueryEvaluationStep {
    /** Threads intersecting slices: daemons, kept a minute when idle. */
    private static final ExecutorService SLICE_THREADS = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "tripatha-merge-slice");
        t.setDaemon(true);
        return t;
    });
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
                tasks = new ArrayList<>(slices.size());
                for (final Intersection slice : slices) {
                    final SliceTask task = new SliceTask(List.of(() -> slice), List.of(slice), null, null);
                    tasks.add(task);
                    SLICE_THREADS.execute(task);
                }
            }
        }

        /** The star read from the entity index: its shards in groups, each on a thread of its own. */
        private void openShards(final EntityIndex entity, final List<StatementPattern> patterns) {
            final List<byte[]> terms = new ArrayList<>(patterns.size());
            for (final StatementPattern sp : patterns) {
                terms.add(EntityIndex.term(RdfToRyaConversions.convertIRI((IRI) sp.getPredicateVar().getValue()),
                        RdfToRyaConversions.convertValue(sp.getObjectVar().getValue())));
                source.countLookup();
            }
            final List<byte[]> shards = entity.shardRows();
            final int threads = Math.max(1, Math.min(source.getConf().getJoinMergeSlices(), shards.size()));
            final Trace trace = StoreTrace.current();
            final String reason = "merge join: the subjects of " + patterns.size() + " constant patterns on ?"
                    + node.getSubjectName() + ", intersected shard by shard in the entity index (" + shards.size()
                    + " shards, " + threads + " threads)";
            slices = List.of();
            tasks = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                final List<Supplier<Intersection>> units = new ArrayList<>();
                for (final byte[] shard : shards.subList(t * shards.size() / threads, (t + 1) * shards.size() / threads)) {
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
                tasks.add(new SliceTask(units, List.of(), trace, reason));
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

    /**
     * The subjects every list of one slice has, in order, each with the product of its counts in
     * the lists. Used by one thread at a time.
     */
    static final class Intersection {
        private final List<CloseableIteration<byte[]>> lists;
        private byte[][] heads;
        private byte[] subject;
        private long repeats;

        Intersection(final List<CloseableIteration<byte[]>> lists) {
            this.lists = lists;
        }

        byte[] subject() {
            return subject;
        }

        long repeats() {
            return repeats;
        }

        /**
         * Moves every list to the next subject they all have, and counts that subject's
         * statements in each, past which they are left.
         *
         * @return false when a list has run out
         */
        boolean advance() {
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
        RuntimeException closeQuietly(RuntimeException failure) {
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
     * Units of work (slices, or shards of the entity index) intersected one after the other on a
     * thread of its own. Whoever claims the task first owns its scans: its thread when it starts,
     * or the query thread when the merge is closed before it started.
     */
    private static final class SliceTask implements Runnable {
        private final List<Supplier<Intersection>> units;
        /** Units opened on the query thread, closed by whoever owns them. */
        private final List<Intersection> preopened;
        private final Trace trace;
        private final String reason;
        private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUED);
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
         */
        SliceTask(final List<Supplier<Intersection>> units, final List<Intersection> preopened, final Trace trace,
                final String reason) {
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
            Intersection current = null;
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
                for (final Intersection unit : preopened) {
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
