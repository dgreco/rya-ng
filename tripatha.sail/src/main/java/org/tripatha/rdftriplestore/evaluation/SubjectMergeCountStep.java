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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.SingletonIteration;
import org.eclipse.rdf4j.model.base.CoreDatatype;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MutableBindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.spi.IntersectionCount;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.Trace;

/**
 * Evaluates a {@link SubjectMergeCount}: the store counts the star's matches in every shard of the
 * entity index (in its servers on Accumulo), a single pattern's being its postings. The group's one
 * row is built from the counts, as RDF4J's {@code GroupIterator} builds it: the parent bindings,
 * and each count an {@code xsd:integer}. The shards are counted in groups, one per thread
 * ({@code query.join.mergeslices}), as a merge reads them: one call for all of them ran on one
 * thread, 3 times slower than the merge.
 * When the store can't count, or the subject is already bound, the group is evaluated as it is.
 */
final class SubjectMergeCountStep implements QueryEvaluationStep {
    private final SubjectMergeCount node;
    private final StoreTripleSource<?> source;
    private final QueryEvaluationStep group;
    private final QueryEvaluationContext context;

    SubjectMergeCountStep(final SubjectMergeCount node, final StoreTripleSource<?> source, final QueryEvaluationStep group,
            final QueryEvaluationContext context) {
        this.node = node;
        this.source = source;
        this.group = group;
        this.context = context;
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(final BindingSet bindings) {
        final List<StatementPattern> patterns = node.getPatterns();
        final String subject = node.getSubjectName();
        final EntityIndex entity = source.entityIndex();
        if (entity == null || !source.entityIntersectsInServers() || bindings.hasBinding(subject)) {
            return group.evaluate(bindings);
        }
        final List<byte[]> shards = entity.shardRows();
        final List<byte[]> terms = SubjectMergeStep.entityTerms(patterns);
        final int threads = Math.max(1, Math.min(source.getConf().getJoinMergeSlices(), shards.size()));
        final String reason = "count: the subjects of " + (patterns.size() == 1 ? "1 constant pattern" : patterns.size()
                + " constant patterns") + " on ?" + subject + ", counted by the store in the entity index ("
                + shards.size() + " shards, " + threads + " threads)";
        final IntersectionCount counts = threads == 1 ? countOnThisThread(shards, terms, reason) : count(shards, terms, threads, reason);
        final MutableBindingSet row = bindings.isEmpty() ? context.createBindingSet() : context.createBindingSet(bindings);
        for (final SubjectMergeCount.Counted counted : node.getCounted()) {
            final long n = counted.distinct() ? counts.subjects() : counts.rows();
            context.setBinding(counted.name())
                    .accept(SimpleValueFactory.getInstance().createLiteral(Long.toString(n), CoreDatatype.XSD.INTEGER), row);
        }
        return new SingletonIteration<>(row);
    }

    private IntersectionCount countOnThisThread(final List<byte[]> shards, final List<byte[]> terms, final String reason) {
        try (StoreTrace.Reason why = StoreTrace.reason(() -> reason)) {
            return source.entityCount(shards, terms);
        }
    }

    /** Each group of shards counted on a thread of its own, under the query's trace. */
    private IntersectionCount count(final List<byte[]> shards, final List<byte[]> terms, final int threads,
            final String reason) {
        final Trace trace = StoreTrace.current();
        final List<Future<IntersectionCount>> parts = new ArrayList<>(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final List<byte[]> group = shards.subList(t * shards.size() / threads, (t + 1) * shards.size() / threads);
                parts.add(SubjectMergeStep.SLICE_THREADS.submit(() -> {
                    try (StoreTrace.Scope joined = StoreTrace.join(trace, reason)) {
                        return source.entityCount(group, terms);
                    }
                }));
            }
            IntersectionCount total = IntersectionCount.NONE;
            for (final Future<IntersectionCount> part : parts) {
                total = total.plus(part.get());
            }
            return total;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueryEvaluationException("Interrupted while counting", e);
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new QueryEvaluationException(e.getCause());
        } finally {
            for (final Future<IntersectionCount> part : parts) {
                part.cancel(true);
            }
        }
    }
}
