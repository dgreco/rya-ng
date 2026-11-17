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
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MutableBindingSet;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.resolver.RyaToRdfConversions;
import org.tripatha.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.tripatha.store.trace.StoreTrace;

/**
 * Evaluates a {@link SubjectMergeJoin}: reads each pattern's statements in subject order, one
 * range of {@code po} each, and returns the subjects found in every list.
 *
 * <p>The rows are those of the patterns' join. A subject with {@code n1} statements in the first
 * list (several graphs, or one statement under several labels), {@code n2} in the second and so
 * on gives {@code n1 × n2 × …} rows, as the nested loop would. When the subject is already bound,
 * as on the right side of an OPTIONAL evaluated per row, the patterns are joined as before.
 */
final class SubjectMergeStep implements QueryEvaluationStep {
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

    /** The intersection of the lists, opened on the first read. */
    private final class Merge extends LookAheadIteration<BindingSet> {
        private final BindingSet bindings;
        private final List<CloseableIteration<RyaStatement>> lists = new ArrayList<>();
        private RyaStatement[] heads;
        private Value subject;
        private long repeats;

        Merge(final BindingSet bindings) {
            this.bindings = bindings;
        }

        @Override
        protected BindingSet getNextElement() {
            if (heads == null) {
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
            heads = new RyaStatement[patterns.size()];
            try (StoreTrace.Reason why = StoreTrace.reason(() -> "merge join: the subjects of " + patterns.size()
                    + " constant patterns on ?" + node.getSubjectName() + ", read in order and intersected")) {
                for (final StatementPattern sp : patterns) {
                    lists.add(source.inSubjectOrder((IRI) sp.getPredicateVar().getValue(), sp.getObjectVar().getValue()));
                }
            }
            for (int i = 0; i < heads.length; i++) {
                heads[i] = next(i);
            }
        }

        /**
         * Moves every list to the next subject they all have, and counts that subject's
         * statements in each, past which they are left.
         *
         * @return false when a list has run out
         */
        private boolean nextMatch() {
            String target = null;
            for (final RyaStatement head : heads) {
                if (head == null) {
                    return false;
                }
                final String s = head.getSubject().getData();
                if (target == null || compareCodePoints(s, target) > 0) {
                    target = s;
                }
            }
            boolean agreed;
            do {
                agreed = true;
                for (int i = 0; i < heads.length; i++) {
                    while (compareCodePoints(heads[i].getSubject().getData(), target) < 0) {
                        heads[i] = next(i);
                        if (heads[i] == null) {
                            return false;
                        }
                    }
                    final String s = heads[i].getSubject().getData();
                    if (!s.equals(target)) {
                        target = s;
                        agreed = false;
                    }
                }
            } while (!agreed);
            subject = RyaToRdfConversions.convertResource(heads[0].getSubject());
            long rows = 1;
            for (int i = 0; i < heads.length; i++) {
                long count = 0;
                while (heads[i] != null && heads[i].getSubject().getData().equals(target)) {
                    count++;
                    heads[i] = next(i);
                }
                rows = Math.multiplyExact(rows, count);
            }
            repeats = rows;
            return true;
        }

        private RyaStatement next(final int list) {
            final CloseableIteration<RyaStatement> it = lists.get(list);
            return it.hasNext() ? it.next() : null;
        }

        @Override
        protected void handleClose() {
            RuntimeException failure = null;
            for (final CloseableIteration<RyaStatement> it : lists) {
                try {
                    it.close();
                } catch (final RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * Compares two strings by code point, which is the order of their UTF-8 bytes and so of the
     * stored rows. {@link String#compareTo} compares UTF-16 units, which puts characters above
     * U+FFFF before U+E000 to U+FFFF.
     */
    static int compareCodePoints(final String a, final String b) {
        final int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x != y) {
                if (x >= '\uD800' && y >= '\uD800') {
                    // Surrogates (U+D800 to U+DFFF) stand for code points above every BMP character.
                    x = (char) (x >= '' ? x - 0x800 : x + 0x2000);
                    y = (char) (y >= '' ? y - 0x800 : y + 0x2000);
                }
                return x - y;
            }
        }
        return a.length() - b.length();
    }
}
