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
package org.tripatha.rdftriplestore.stats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;

import org.tripatha.store.trace.StoreTrace;
import org.tripatha.rdftriplestore.utils.FixedStatementPattern;

/**
 * Answers the {@code stats:} patterns from the statistics counters instead of the store
 * (SCALE-STUDY.md O8), each with a few counter reads where {@code COUNT(*)} would read every
 * statement it counts:
 * <ul>
 * <li>{@code ?graph stats:approxSize ?n}: each named graph with data, and its approximate number
 * of statements;</li>
 * <li>{@code ?class stats:approxInstances ?n}: each class, and its approximate number of
 * {@code rdf:type} statements, as stored (no inference);</li>
 * <li>{@code ?predicate stats:approxTriples ?n}: each predicate, and its approximate number of
 * statements.</li>
 * </ul>
 * A pattern is replaced with a {@link FixedStatementPattern} holding those statements, whose
 * objects are {@code xsd:long}s. A constant subject reads only its own counter. Bound subjects or
 * objects, and the rest of the query, work as for any pattern.
 */
public class ApproxCountOptimizer implements QueryOptimizer {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /** The counters, read with the query's authorizations. */
    public interface Counts {
        /** Each named graph with data, and its approximate size. */
        Map<String, Long> graphs();

        /** @param only the classes to count, or null for every class */
        Map<String, Long> classes(Set<String> only);

        /** @param only the predicates to count, or null for every one */
        Map<String, Long> predicates(Set<String> only);
    }

    private final Counts counts;

    /**
     * @param counts the counters, read only if the query asks for them; null when the store keeps
     *        no statistics
     */
    public ApproxCountOptimizer(final Counts counts) {
        this.counts = counts;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        final List<StatementPattern> targets = new ArrayList<>();
        tupleExpr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern sp) {
                final Var p = sp.getPredicateVar();
                if (p != null && p.hasValue() && RyaStats.COUNTED.contains(p.getValue())) {
                    targets.add(sp);
                }
            }
        });
        if (targets.isEmpty()) {
            return;
        }
        if (counts == null) {
            throw new QueryEvaluationException(targets.get(0).getPredicateVar().getValue()
                    + " needs the statistics, and this store was opened without them (tripatha.stats on the endpoint)");
        }
        // One read per predicate and subject, however often the query names them.
        final Map<String, List<Statement>> read = new HashMap<>();
        for (final StatementPattern sp : targets) {
            final IRI predicate = (IRI) sp.getPredicateVar().getValue();
            final Var s = sp.getSubjectVar();
            final Value subject = s != null && s.hasValue() ? s.getValue() : null;
            final List<Statement> statements = read.computeIfAbsent(predicate + " " + subject, k -> statements(predicate, subject));
            final FixedStatementPattern fixed = new FixedStatementPattern(sp.getScope(), clone(sp.getSubjectVar()),
                    clone(sp.getPredicateVar()), clone(sp.getObjectVar()), clone(sp.getContextVar()));
            fixed.statements.addAll(statements);
            sp.replaceWith(fixed);
        }
    }

    private List<Statement> statements(final IRI predicate, final Value subject) {
        final List<Statement> out = new ArrayList<>();
        if (subject != null && !(subject instanceof IRI)) {
            return out; // only IRIs are graphs, classes or predicates here
        }
        final Set<String> only = subject == null ? null : Set.of(subject.stringValue());
        final Map<String, Long> sizes;
        try (StoreTrace.Reason why = StoreTrace.reason(() -> "stats:" + predicate.getLocalName())) {
            if (RyaStats.APPROX_SIZE.equals(predicate)) {
                sizes = counts.graphs();
            } else if (RyaStats.APPROX_INSTANCES.equals(predicate)) {
                sizes = counts.classes(only);
            } else {
                sizes = counts.predicates(only);
            }
        }
        for (final Map.Entry<String, Long> e : sizes.entrySet()) {
            if (only != null && !only.contains(e.getKey())) {
                continue;
            }
            final IRI iri;
            try {
                iri = VF.createIRI(e.getKey());
            } catch (final IllegalArgumentException notAnIri) {
                continue; // a literal object of rdf:type, say: counted, but not a class
            }
            out.add(VF.createStatement(iri, predicate, VF.createLiteral(e.getValue())));
        }
        return out;
    }

    /** A Var may belong to one parent only, so the new node gets copies. */
    private static Var clone(final Var var) {
        return var == null ? null : var.clone();
    }
}
