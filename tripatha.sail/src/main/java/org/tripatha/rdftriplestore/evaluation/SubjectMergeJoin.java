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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.rdf4j.query.algebra.AbstractQueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;

/**
 * The join of a star's constant patterns, {@code ?x p1 o1 . ?x p2 o2 …}, evaluated by reading
 * each pattern's subjects in order and intersecting them (SCALE-STUDY.md O22). Each pattern is
 * one range of {@code po}, whose rows for a predicate and object are sorted by subject.
 *
 * <p>A merge of one list is a long pattern on its own (O7a): its subjects are read in slices,
 * without decoding statements.
 *
 * <p>Built by {@link SubjectMergeOptimizer} and evaluated by {@link SubjectMergeStep}. It binds
 * only the subject variable, and binds it on every row. Its rows are those of the join of its
 * patterns, in subject order instead of the order of a nested loop.
 */
public final class SubjectMergeJoin extends AbstractQueryModelNode implements TupleExpr {
    private final String subject;
    private List<StatementPattern> patterns;

    /**
     * @param subject the patterns' subject variable
     * @param patterns one or more patterns, each with that subject, a constant predicate and a
     *        constant object; they become this node's children
     */
    public SubjectMergeJoin(final String subject, final List<StatementPattern> patterns) {
        this.subject = Objects.requireNonNull(subject);
        this.patterns = new ArrayList<>(patterns);
        for (final StatementPattern sp : this.patterns) {
            sp.setParentNode(this);
        }
    }

    /** @return the name of the subject variable, the only one this node binds. */
    public String getSubjectName() {
        return subject;
    }

    public List<StatementPattern> getPatterns() {
        return Collections.unmodifiableList(patterns);
    }

    @Override
    public Set<String> getBindingNames() {
        return new LinkedHashSet<>(Set.of(subject));
    }

    @Override
    public Set<String> getAssuredBindingNames() {
        return getBindingNames();
    }

    @Override
    public <X extends Exception> void visit(final QueryModelVisitor<X> visitor) throws X {
        visitor.meetOther(this);
    }

    @Override
    public <X extends Exception> void visitChildren(final QueryModelVisitor<X> visitor) throws X {
        for (final StatementPattern sp : patterns) {
            sp.visit(visitor);
        }
    }

    @Override
    public void replaceChildNode(final QueryModelNode current, final QueryModelNode replacement) {
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i) == current) {
                if (!(replacement instanceof StatementPattern sp)) {
                    throw new IllegalArgumentException("A merge join holds statement patterns only: " + replacement);
                }
                patterns.set(i, sp);
                sp.setParentNode(this);
                return;
            }
        }
        throw new IllegalArgumentException("Node is not a child node: " + current);
    }

    @Override
    public String getSignature() {
        return super.getSignature() + " (?" + subject + ", " + patterns.size() + (patterns.size() == 1 ? " list" : " lists")
                + " read in subject order)";
    }

    @Override
    public SubjectMergeJoin clone() {
        final SubjectMergeJoin copy = (SubjectMergeJoin) super.clone();
        copy.patterns = new ArrayList<>(patterns.size());
        for (final StatementPattern sp : patterns) {
            final StatementPattern cloned = sp.clone();
            cloned.setParentNode(copy);
            copy.patterns.add(cloned);
        }
        return copy;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof SubjectMergeJoin o && subject.equals(o.subject) && patterns.equals(o.patterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, patterns);
    }
}
