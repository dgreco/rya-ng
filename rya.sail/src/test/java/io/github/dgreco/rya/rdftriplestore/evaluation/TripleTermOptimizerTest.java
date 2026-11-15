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
package io.github.dgreco.rya.rdftriplestore.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment;
import org.eclipse.rdf4j.query.algebra.EmptySet;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TripleRef;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.jupiter.api.Test;

public class TripleTermOptimizerTest {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static TupleExpr parse(final String where) {
        return new SPARQLParser().parseQuery("SELECT * WHERE { " + where + " }", null).getTupleExpr();
    }

    /** The join arguments and other leaves, in evaluation order: T for a triple term reference, P for a pattern, B for a constant row. */
    private static String leaves(final TupleExpr expr) {
        final StringBuilder b = new StringBuilder();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final TripleRef node) {
                b.append('T');
            }

            @Override
            public void meet(final StatementPattern node) {
                b.append('P');
            }

            @Override
            public void meet(final BindingSetAssignment node) {
                b.append('B');
            }

            @Override
            public void meet(final EmptySet node) {
                b.append('0');
            }
        });
        return b.toString();
    }

    private static TupleExpr fold(final String where) {
        final TupleExpr expr = parse(where);
        TripleTermOptimizer.fold(VF).optimize(expr, null, EmptyBindingSet.getInstance());
        return expr;
    }

    private static TupleExpr order(final String where) {
        final TupleExpr expr = parse(where);
        TripleTermOptimizer.order().optimize(expr, null, EmptyBindingSet.getInstance());
        return expr;
    }

    @Test
    public void aConstantTermBecomesARow() {
        final TupleExpr expr = fold("?r <urn:reifies> <<( <urn:a> <urn:b> <urn:c> )>>");
        assertEquals("BP", leaves(expr));
        final List<BindingSet> rows = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final BindingSetAssignment node) {
                node.getBindingSets().forEach(rows::add);
            }
        });
        assertEquals(1, rows.size());
        assertEquals(VF.createTripleTerm(VF.createIRI("urn:a"), VF.createIRI("urn:b"), VF.createIRI("urn:c")),
                rows.get(0).iterator().next().getValue());
    }

    @Test
    public void foldingAnInnerTermFoldsTheOneAroundIt() {
        assertEquals("BBP", leaves(fold("?r <urn:reifies> <<( <urn:a> <urn:b> <<( <urn:x> <urn:y> \"z\" )>> )>>")));
        // The inner term has a variable, so neither is constant.
        assertEquals("TTP", leaves(fold("?r <urn:reifies> <<( <urn:a> <urn:b> <<( <urn:x> <urn:y> ?z )>> )>>")));
        assertEquals("TP", leaves(fold("?r <urn:reifies> <<( <urn:a> ?p <urn:c> )>>")));
    }

    @Test
    public void aTermWithALiteralSubjectMatchesNothing() {
        final TupleExpr expr = parse("?r <urn:reifies> <<( ?s <urn:b> <urn:c> )>>");
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final TripleRef node) {
                node.getSubjectVar().replaceWith(org.eclipse.rdf4j.query.algebra.Var.of("s", VF.createLiteral("lit")));
            }
        });
        TripleTermOptimizer.fold(VF).optimize(expr, null, EmptyBindingSet.getInstance());
        assertEquals("0P", leaves(expr));
    }

    @Test
    public void aReferenceFollowsThePatternThatBindsItsTerm() {
        assertEquals("PT", leaves(order("?r <urn:reifies> <<( <urn:a> ?p ?o )>>")));
        assertEquals("PTP", leaves(order("?r <urn:reifies> <<( <urn:a> ?p ?o )>> . ?r <urn:since> ?d")));
        // The outer term's reference binds the inner term, so the inner one follows it.
        final TupleExpr nested = order("?r <urn:reifies> <<( <urn:a> <urn:b> <<( <urn:x> <urn:y> ?z )>> )>>");
        assertEquals("PTT", leaves(nested));
        final List<String> objects = new ArrayList<>();
        nested.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final TripleRef node) {
                objects.add(node.getObjectVar().getName());
            }
        });
        assertTrue(objects.get(0).startsWith("_anon"), "the outer term comes first: " + objects);
        assertEquals("z", objects.get(1));
        // Reified triples and annotations.
        assertEquals("PTP", leaves(order("<< <urn:a> <urn:b> ?o >> <urn:since> ?d")));
        // Inside an OPTIONAL, within its own join.
        assertEquals("PPT", leaves(order("?a <urn:knows> ?b OPTIONAL { ?r <urn:reifies> <<( ?a <urn:knows> ?b )>> }")));
    }

    @Test
    public void aReferenceNothingBindsStaysFirst() {
        assertEquals("TP", leaves(order("BIND(<<( ?s <urn:b> ?o )>> AS ?t) ?s <urn:name> ?n")));
    }

    @Test
    public void aQueryWithoutTripleTermsIsLeftAlone() {
        final TupleExpr expr = parse("?a <urn:knows> ?b . ?b <urn:knows> ?c . ?c <urn:name> ?n");
        final String before = expr.toString();
        final List<Join> joinsBefore = joins(expr);
        TripleTermOptimizer.fold(VF).optimize(expr, null, EmptyBindingSet.getInstance());
        TripleTermOptimizer.order().optimize(expr, null, EmptyBindingSet.getInstance());
        assertEquals(before, expr.toString());
        final List<Join> joinsAfter = joins(expr);
        for (int i = 0; i < joinsBefore.size(); i++) {
            assertTrue(joinsBefore.get(i) == joinsAfter.get(i), "the join nodes are the same objects");
        }
    }

    private static List<Join> joins(final TupleExpr expr) {
        final List<Join> joins = new ArrayList<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Join node) {
                joins.add(node);
                super.meet(node);
            }
        });
        return joins;
    }
}
