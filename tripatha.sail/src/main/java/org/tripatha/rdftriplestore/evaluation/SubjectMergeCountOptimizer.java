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

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Count;
import org.eclipse.rdf4j.query.algebra.Group;
import org.eclipse.rdf4j.query.algebra.GroupElem;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;

/**
 * Turns the counts of a star into a {@link SubjectMergeCount}, for the store to count (SCALE-STUDY.md
 * O23): a group with no {@code GROUP BY} over a {@link SubjectMergeJoin}, or over a single star
 * pattern ({@code ?x a :C}, the size of a class), whose every element is {@code COUNT(*)},
 * {@code COUNT(?x)}, {@code COUNT(DISTINCT ?x)} or {@code COUNT(DISTINCT *)}, ?x being the star's
 * subject. The star binds only ?x, and on every row, so the first two count its rows and the last
 * two its subjects.
 *
 * <p>A merge exists only where {@link SubjectMergeOptimizer} found it safe. A single pattern takes
 * the same conditions, except that it needs neither counters nor the ratio, which choose what to
 * merge: no dataset (the index holds every graph), no {@code query.limit} (which caps the pattern's
 * rows, and so the count), not the hashed row layout, no result-size or time tracking.
 *
 * <p>Runs after {@link SubjectMergeOptimizer}, and only when the store intersects the entity index.
 */
public final class SubjectMergeCountOptimizer implements QueryOptimizer {
    private final RdfCloudTripleStoreConfiguration conf;
    private final boolean allowed;

    /**
     * @param conf the query's configuration; null counts merges only
     * @param allowed false when the query is evaluated with RDF4J's result-size or time tracking
     */
    public SubjectMergeCountOptimizer(final RdfCloudTripleStoreConfiguration conf, final boolean allowed) {
        this.conf = conf;
        this.allowed = allowed;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        final boolean onePattern = allowed && conf != null && dataset == null && conf.getLimit() == null
                && !Boolean.TRUE.equals(conf.isPrefixRowsWithHash());
        final List<Group> groups = new ArrayList<>();
        tupleExpr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Group node) {
                if (node.getGroupBindingNames().isEmpty() && (node.getArg() instanceof SubjectMergeJoin
                        || onePattern && node.getArg() instanceof StatementPattern sp && SubjectMergeOptimizer.isStarPattern(sp))) {
                    groups.add(node);
                }
                super.meet(node);
            }
        });
        for (final Group group : groups) {
            final List<SubjectMergeCount.Counted> counted = counted(group);
            if (counted != null) {
                final QueryModelNode parent = group.getParentNode();
                final SubjectMergeCount count = new SubjectMergeCount(counted);
                parent.replaceChildNode(group, count);
                count.setArg(group);
            }
        }
    }

    /** The group's counts, or null if one of its elements is anything else. */
    private static List<SubjectMergeCount.Counted> counted(final Group group) {
        final String subject = SubjectMergeCount.subjectName(group.getArg());
        final List<SubjectMergeCount.Counted> counted = new ArrayList<>();
        for (final GroupElem elem : group.getGroupElements()) {
            if (!(elem.getOperator() instanceof Count count)) {
                return null;
            }
            if (count.getArg() != null
                    && !(count.getArg() instanceof Var v && !v.hasValue() && v.getName().equals(subject))) {
                return null;
            }
            counted.add(new SubjectMergeCount.Counted(elem.getName(), count.isDistinct()));
        }
        return counted.isEmpty() ? null : counted;
    }
}
