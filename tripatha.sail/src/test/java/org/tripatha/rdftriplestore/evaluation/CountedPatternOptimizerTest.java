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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.TreeSet;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;
import org.tripatha.store.StoreRdfConfiguration;

/**
 * Which patterns {@link CountedPatternOptimizer} counts (SCALE-STUDY.md O26): those on the right of
 * a join or OPTIONAL whose object the rest of the query never names, in any of the ways a name can
 * be used.
 */
public class CountedPatternOptimizerTest {
    private static final String STAR = "?x a <urn:C> ; <urn:knows> ?f";

    @Test
    public void objectsNamedNowhereElseAreCounted() {
        assertEquals(Set.of("f"), counted("SELECT ?x WHERE { " + STAR + " }"));
        assertEquals(Set.of("f", "l"), counted("SELECT ?x WHERE { " + STAR + " ; <urn:likes> ?l }"));
        assertEquals(Set.of("f"), counted("SELECT ?x WHERE { ?x a <urn:C> OPTIONAL { ?x <urn:knows> ?f } }"));
        assertEquals(Set.of("f"), counted("SELECT (COUNT(*) AS ?c) WHERE { " + STAR + " }"));
        assertEquals(Set.of("f"), counted("SELECT ?x (COUNT(?x) AS ?c) WHERE { " + STAR + " } GROUP BY ?x"));
        assertEquals(Set.of("f"), counted("SELECT DISTINCT ?x WHERE { " + STAR + " }"));
        assertEquals(Set.of("f"), counted("ASK { " + STAR + " }"));
        assertEquals(Set.of("f"), counted("SELECT ?x WHERE { ?x a <urn:C> . GRAPH <urn:g> { ?x <urn:knows> ?f } }"));
        assertEquals(Set.of("f"), counted("SELECT ?x ?g WHERE { ?x a <urn:C> . GRAPH ?g { ?x <urn:knows> ?f } }"));
        assertEquals(Set.of("z"), counted("SELECT ?x ?y WHERE { ?x a <urn:C> ; <urn:knows> ?y . ?y <urn:knows> ?z }"));
        assertEquals(1, counted("SELECT ?x WHERE { ?x a <urn:C> ; <urn:knows> [] }").size(), "a blank node is a variable");
        assertEquals(Set.of("f"), counted("SELECT ?x WHERE { " + STAR + " FILTER(?x != <urn:s1>) }"));
    }

    @Test
    public void objectsUsedAnywhereElseAreNot() {
        for (final String query : new String[] {
                "SELECT * WHERE { " + STAR + " }",
                "SELECT ?x ?f WHERE { " + STAR + " }",
                "SELECT ?x WHERE { " + STAR + " FILTER(?f != <urn:s1>) }",
                "SELECT ?x WHERE { " + STAR + " VALUES ?f { <urn:s1> } }",
                "SELECT ?x ?y WHERE { " + STAR + " BIND(?f AS ?y) }",
                "SELECT ?x WHERE { " + STAR + " FILTER EXISTS { ?f <urn:name> ?n } }",
                "SELECT ?x WHERE { " + STAR + " FILTER NOT EXISTS { ?y <urn:knows> ?f } }",
                "SELECT ?x WHERE { " + STAR + " } ORDER BY ?f",
                "SELECT ?f (COUNT(*) AS ?c) WHERE { " + STAR + " } GROUP BY ?f",
                "SELECT ?x (SAMPLE(?f) AS ?s) WHERE { " + STAR + " } GROUP BY ?x",
                "SELECT ?x WHERE { ?x a <urn:C> ; <urn:knows> ?x }",
                "SELECT ?x WHERE { ?x a <urn:C> ; ?f ?f }",
                "SELECT ?x WHERE { ?x a <urn:C> . GRAPH ?f { ?x <urn:knows> ?f } }",
                "SELECT ?x WHERE { " + STAR + " { SELECT ?f WHERE { ?f a <urn:C> } } }",
                "SELECT ?x WHERE { ?x a <urn:C> OPTIONAL { ?x <urn:knows> ?f FILTER(?f != ?x) } }",
                "CONSTRUCT { ?x <urn:k> ?f } WHERE { " + STAR + " }",
                "SELECT ?x WHERE { ?x a <urn:C> ; <urn:knows> \"x\" }"}) {
            assertEquals(Set.of(), counted(query), query);
        }
    }

    @Test
    public void somePlansCountNothing() {
        assertEquals(Set.of(), counted("SELECT (COUNT(DISTINCT *) AS ?c) WHERE { " + STAR + " }"), "rows told apart by ?f");
        assertEquals(Set.of(), counted("DESCRIBE ?x WHERE { " + STAR + " }"));
        assertEquals(Set.of(), counted("SELECT ?x WHERE { ?x a <urn:C> } "), "a pattern on its own drives");
        final StoreRdfConfiguration off = new StoreRdfConfiguration();
        off.setJoinCountUnused(false);
        assertEquals(Set.of(), counted("SELECT ?x WHERE { " + STAR + " }", off, null, EmptyBindingSet.getInstance()));
        final StoreRdfConfiguration limited = new StoreRdfConfiguration();
        limited.setLimit(5L);
        assertEquals(Set.of(), counted("SELECT ?x WHERE { " + STAR + " }", limited, null, EmptyBindingSet.getInstance()));
        final SimpleDataset dataset = new SimpleDataset();
        dataset.addDefaultGraph(SimpleValueFactory.getInstance().createIRI("urn:g"));
        assertEquals(Set.of(), counted("SELECT ?x WHERE { " + STAR + " }", new StoreRdfConfiguration(), dataset,
                EmptyBindingSet.getInstance()));
        final QueryBindingSet outside = new QueryBindingSet();
        outside.addBinding("f", SimpleValueFactory.getInstance().createIRI("urn:s1"));
        assertEquals(Set.of(), counted("SELECT ?x WHERE { " + STAR + " }", new StoreRdfConfiguration(), null, outside));
    }

    private static Set<String> counted(final String query) {
        return counted(query, new StoreRdfConfiguration(), null, EmptyBindingSet.getInstance());
    }

    /** The object variables of the patterns the optimizer marks in this query. */
    private static Set<String> counted(final String query, final StoreRdfConfiguration conf, final Dataset dataset,
            final BindingSet bindings) {
        final ParsedQuery parsed = QueryParserUtil.parseQuery(QueryLanguage.SPARQL, query, null);
        final TupleExpr expr = parsed.getTupleExpr();
        new CountedPatternOptimizer(conf).optimize(expr, dataset, bindings);
        final Set<String> out = new TreeSet<>();
        expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern node) {
                if (node instanceof CountedPattern) {
                    out.add(node.getObjectVar().getName());
                }
            }
        });
        return out;
    }
}
