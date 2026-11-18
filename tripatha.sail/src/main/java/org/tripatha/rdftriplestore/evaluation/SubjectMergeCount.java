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

import java.util.List;
import java.util.Objects;

import org.eclipse.rdf4j.query.algebra.Group;
import org.eclipse.rdf4j.query.algebra.QueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.UnaryTupleOperator;

/**
 * Counts of a star, {@code SELECT (COUNT(*) AS ?n) WHERE { ?x p1 o1 ; p2 o2 }}, that the store can
 * give without returning the matches (SCALE-STUDY.md O23): its argument is the {@link Group} it
 * stands for, whose own argument is a {@link SubjectMergeJoin}, and which is evaluated as it is
 * whenever the store can't count.
 *
 * <p>Built by {@link SubjectMergeCountOptimizer}, evaluated by {@code RyaEvaluationStrategy}.
 */
public final class SubjectMergeCount extends UnaryTupleOperator {
    /**
     * One of the group's counts.
     *
     * @param name the group element's name
     * @param distinct true for the distinct subjects ({@code COUNT(DISTINCT ?x)}, {@code COUNT(DISTINCT *)}),
     *        false for the rows ({@code COUNT(*)}, {@code COUNT(?x)})
     */
    public record Counted(String name, boolean distinct) {
    }

    private final List<Counted> counted;

    public SubjectMergeCount(final List<Counted> counted) {
        this.counted = List.copyOf(counted);
    }

    /** The group this stands for. */
    public Group getGroup() {
        return (Group) getArg();
    }

    /** The star whose matches are counted. */
    public SubjectMergeJoin getMerge() {
        return (SubjectMergeJoin) getGroup().getArg();
    }

    public List<Counted> getCounted() {
        return counted;
    }

    @Override
    public <X extends Exception> void visit(final QueryModelVisitor<X> visitor) throws X {
        visitor.meetOther(this);
    }

    @Override
    public String getSignature() {
        return super.getSignature() + " " + counted;
    }

    @Override
    public SubjectMergeCount clone() {
        return (SubjectMergeCount) super.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof SubjectMergeCount o && super.equals(o) && counted.equals(o.counted);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), counted);
    }
}
