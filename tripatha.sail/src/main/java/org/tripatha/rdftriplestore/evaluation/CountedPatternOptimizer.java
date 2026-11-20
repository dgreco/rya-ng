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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment;
import org.eclipse.rdf4j.query.algebra.Count;
import org.eclipse.rdf4j.query.algebra.DescribeOperator;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Group;
import org.eclipse.rdf4j.query.algebra.GroupElem;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.ProjectionElem;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;

/**
 * Marks the statement patterns whose object variable no other part of the query uses
 * (SCALE-STUDY.md O26), as {@link CountedPattern}s: their lookups are counted instead of read.
 * Runs last, after the joins have their final shape. A pattern is marked when:
 * <ul>
 * <li>it is a plain store pattern (not inferred, indexed or read as a range) on the right of a
 *     join or an OPTIONAL, on its own or under filters, so its rows come from the left;</li>
 * <li>its object is a variable named nowhere else: no other pattern, filter, {@code BIND},
 *     {@code VALUES}, projection, grouping, ordering or subquery, nor its own subject, predicate
 *     or graph, nor an outside binding;</li>
 * <li>the query doesn't count distinct whole rows ({@code COUNT(DISTINCT *)} tells rows apart by
 *     every variable), describe resources or call a service, and has no dataset or
 *     {@code query.limit}.</li>
 * </ul>
 * Names are counted over the whole query, every scope included: a name used in a subquery keeps a
 * pattern outside it from being counted, which is only ever conservative.
 */
final class CountedPatternOptimizer implements QueryOptimizer {
    private final RdfCloudTripleStoreConfiguration conf;

    CountedPatternOptimizer(final RdfCloudTripleStoreConfiguration conf) {
        this.conf = conf;
    }

    @Override
    public void optimize(final TupleExpr expr, final Dataset dataset, final BindingSet bindings) {
        if (conf == null || !conf.isJoinCountUnused() || conf.getLimit() != null || dataset != null) {
            return;
        }
        final Uses uses = new Uses();
        expr.visit(uses);
        if (uses.everyVariable) {
            return;
        }
        if (bindings != null) {
            for (final String name : bindings.getBindingNames()) {
                uses.add(name);
                uses.add(name);
            }
        }
        final List<StatementPattern> marked = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Join node) {
                consider(node.getRightArg());
                super.meet(node);
            }

            @Override
            public void meet(final LeftJoin node) {
                consider(node.getRightArg());
                super.meet(node);
            }

            private void consider(final TupleExpr right) {
                TupleExpr arg = right;
                while (arg instanceof Filter filter) {
                    arg = filter.getArg();
                }
                if (arg != null && arg.getClass() == StatementPattern.class && countable((StatementPattern) arg, uses)) {
                    marked.add((StatementPattern) arg);
                }
            }
        });
        for (final StatementPattern sp : marked) {
            sp.replaceWith(new CountedPattern(sp));
        }
    }

    private static boolean countable(final StatementPattern sp, final Uses uses) {
        final Var object = sp.getObjectVar();
        return !sp.isVariableScopeChange() && object != null && !object.hasValue() && uses.count(object.getName()) == 1;
    }

    /** How many times the query names each variable, and whether something reads every variable. */
    private static final class Uses extends AbstractQueryModelVisitor<RuntimeException> {
        private final Map<String, Integer> counts = new HashMap<>();
        private boolean everyVariable;

        void add(final String name) {
            if (name != null) {
                counts.merge(name, 1, Integer::sum);
            }
        }

        int count(final String name) {
            return counts.getOrDefault(name, 0);
        }

        @Override
        public void meet(final Var node) {
            add(node.getName());
        }

        @Override
        public void meet(final ProjectionElem node) {
            add(node.getName());
            node.getProjectionAlias().ifPresent(this::add);
            super.meet(node);
        }

        @Override
        public void meet(final ExtensionElem node) {
            add(node.getName());
            super.meet(node);
        }

        @Override
        public void meet(final Group node) {
            node.getGroupBindingNames().forEach(this::add);
            super.meet(node);
        }

        @Override
        public void meet(final GroupElem node) {
            add(node.getName());
            super.meet(node);
        }

        @Override
        public void meet(final BindingSetAssignment node) {
            node.getBindingNames().forEach(this::add);
            super.meet(node);
        }

        @Override
        public void meet(final Count node) {
            if (node.isDistinct() && node.getArg() == null) {
                everyVariable = true; // COUNT(DISTINCT *)
            }
            super.meet(node);
        }

        @Override
        public void meet(final Service node) {
            everyVariable = true;
            super.meet(node);
        }

        @Override
        public void meet(final DescribeOperator node) {
            everyVariable = true;
            super.meet(node);
        }
    }
}
