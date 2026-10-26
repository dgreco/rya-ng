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
package io.github.dgreco.rya.rdftriplestore.evaluation.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.StandardQueryOptimizerPipeline;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.index.IndexCatalog;
import io.github.dgreco.rya.accumulo.index.IndexQuery;
import io.github.dgreco.rya.accumulo.index.IndexType;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates;

class IndexSearchOptimizerTest {
    private static final String PREFIXES = """
            PREFIX text: <urn:rya-ng:text#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
            PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
            """;
    private static final String ROME = "\"POLYGON((12.3 41.7, 12.7 41.7, 12.7 42.1, 12.3 42.1, 12.3 41.7))\"^^geo:wktLiteral";
    private static final String POINT = "\"POINT(12.49 41.89)\"^^geo:wktLiteral";

    private record Catalog(boolean text, boolean geo, Set<String> textPredicates) implements IndexCatalog {
        @Override
        public boolean isReady(final IndexType type) {
            return type == IndexType.TEXT ? text : geo;
        }

        @Override
        public String table(final IndexType type) {
            return type.id();
        }
    }

    private static final IndexCatalog BOTH = new Catalog(true, true, Set.of());

    private static TupleExpr parse(final String query) {
        final TupleExpr expr = new QueryRoot(new SPARQLParser().parseQuery(PREFIXES + query, null).getTupleExpr());
        // The optimizers that run before the index rewrite in the real pipeline.
        StandardQueryOptimizerPipeline.CONJUNCTIVE_CONSTRAINT_SPLITTER.optimize(expr, null, EmptyBindingSet.getInstance());
        StandardQueryOptimizerPipeline.UNION_SCOPE_CHANGE_OPTIMIZER.optimize(expr, null, EmptyBindingSet.getInstance());
        StandardQueryOptimizerPipeline.QUERY_MODEL_NORMALIZER.optimize(expr, null, EmptyBindingSet.getInstance());
        return expr;
    }

    private static List<IndexedStatementPattern> indexed(final String query, final IndexCatalog catalog,
            final AccumuloRdfConfiguration conf) {
        final TupleExpr expr = parse(query);
        final int calls = functionCalls(expr);
        new IndexSearchOptimizer(catalog, conf).optimize(expr, null, EmptyBindingSet.getInstance());
        assertEquals(calls, functionCalls(expr), "the filters stay in the query");
        final List<IndexedStatementPattern> found = new ArrayList<>();
        expr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern sp) {
                if (sp instanceof IndexedStatementPattern isp) {
                    found.add(isp);
                }
            }
        });
        return found;
    }

    private static List<IndexedStatementPattern> indexed(final String query) {
        return indexed(query, BOTH, new AccumuloRdfConfiguration());
    }

    private static int functionCalls(final TupleExpr expr) {
        final int[] n = new int[1];
        expr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final FunctionCall call) {
                n[0]++;
                super.meet(call);
            }
        });
        return n[0];
    }

    private static void assertIndexed(final int expected, final String where) {
        assertEquals(expected, indexed("SELECT * WHERE { " + where + " }").size(), where);
    }

    @Test
    void textFiltersOnPatternObjects() {
        final List<IndexedStatementPattern> found =
                indexed("SELECT * WHERE { ?d rdfs:label ?l FILTER(text:matches(?l, \"alice bob*\")) }");
        assertEquals(1, found.size());
        assertInstanceOf(IndexQuery.Text.class, found.get(0).getIndexQuery());
        assertEquals("l", found.get(0).getObjectVar().getName());
        assertIndexed(1, "?d ?p ?l FILTER(text:matches(?l, \"x\"))");
        assertIndexed(1, "?d a rdfs:Class . ?d rdfs:label ?l FILTER(text:matches(?l, \"x\"))");
        assertIndexed(2, "{ ?d rdfs:label ?l } UNION { ?d rdfs:comment ?l } FILTER(text:matches(?l, \"x\"))");
        assertIndexed(1, "GRAPH ?g { ?d rdfs:label ?l } FILTER(text:matches(?l, \"x\"))");
        assertIndexed(1, "?d rdfs:label ?l OPTIONAL { ?d rdfs:comment ?c } FILTER(text:matches(?l, \"x\"))");
        assertIndexed(1, "?d rdfs:label ?l MINUS { ?d a rdfs:Class } FILTER(text:matches(?l, \"x\"))");
    }

    @Test
    void severalTextFiltersOnOneVariableAreCombined() {
        final List<IndexedStatementPattern> found = indexed(
                "SELECT * WHERE { ?d rdfs:label ?l FILTER(text:matches(?l, \"a\") && text:matches(?l, \"b\")) }");
        assertEquals(1, found.size());
        assertEquals(new IndexQuery.Text(new TextCandidates.AllOf(List.of(new TextCandidates.Word("a"),
                new TextCandidates.Word("b")))), found.get(0).getIndexQuery());
    }

    @Test
    void patternsTheFilterDoesNotCoverAreLeftAlone() {
        assertIndexed(0, "?d rdfs:label ?l FILTER(text:matches(?l, \"NOT alice\"))");
        assertIndexed(0, "?d rdfs:label ?l FILTER(text:matches(?l, \"al*\"))");
        assertIndexed(0, "?d a rdfs:Class OPTIONAL { ?d rdfs:label ?l } FILTER(text:matches(?l, \"x\"))");
        assertIndexed(0, "{ SELECT ?l WHERE { ?d rdfs:label ?l } } FILTER(text:matches(?l, \"x\"))");
        assertIndexed(0, "?d rdfs:label ?x BIND(?x AS ?l) FILTER(text:matches(?l, \"x\"))");
        assertIndexed(0, "?l rdfs:label ?l FILTER(text:matches(?l, \"x\"))");
        assertIndexed(0, "<urn:a> rdfs:label ?l FILTER(text:matches(?l, \"x\"))");
        assertIndexed(0, "?d rdfs:label ?l FILTER(text:matches(?l, \"x\") || ?l = \"y\")");
        assertIndexed(0, "?d rdfs:label ?l FILTER(!text:matches(?l, \"x\"))");
        assertIndexed(0, "?d rdfs:label ?l FILTER(text:matches(?other, \"x\"))");
        assertIndexed(0, "?d rdfs:label ?l FILTER(text:matches(?l, ?q))");
    }

    @Test
    void textPredicateListAndSwitches() {
        final IndexCatalog labelsOnly = new Catalog(true, false, Set.of("http://www.w3.org/2000/01/rdf-schema#label"));
        final AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
        assertEquals(1, indexed("SELECT * { ?d rdfs:label ?l FILTER(text:matches(?l, \"x\")) }", labelsOnly, conf).size());
        assertEquals(0, indexed("SELECT * { ?d rdfs:comment ?l FILTER(text:matches(?l, \"x\")) }", labelsOnly, conf).size());
        assertEquals(0, indexed("SELECT * { ?d ?p ?l FILTER(text:matches(?l, \"x\")) }", labelsOnly, conf).size());

        final String q = "SELECT * { ?d rdfs:label ?l FILTER(text:matches(?l, \"x\")) }";
        assertEquals(0, indexed(q, new Catalog(false, true, Set.of()), conf).size(), "text index not ready");
        final AccumuloRdfConfiguration off = new AccumuloRdfConfiguration();
        off.setUseIndex(false);
        assertEquals(0, indexed(q, BOTH, off).size());
        final AccumuloRdfConfiguration limited = new AccumuloRdfConfiguration();
        limited.setLimit(5L);
        assertEquals(0, indexed(q, BOTH, limited).size());
    }

    @Test
    void geoRelationsThatImplyIntersection() {
        for (final String fn : List.of("sfWithin", "sfIntersects", "sfContains", "sfOverlaps", "sfTouches", "sfCrosses",
                "sfEquals", "ehInside", "ehCoveredBy", "ehMeet", "rcc8ntpp", "rcc8ec", "rcc8po")) {
            assertIndexed(1, "?g geo:asWKT ?w FILTER(geof:" + fn + "(?w, " + ROME + "))");
            assertIndexed(1, "?g geo:asWKT ?w FILTER(geof:" + fn + "(" + ROME + ", ?w))");
        }
        for (final String fn : List.of("sfDisjoint", "ehDisjoint", "rcc8dc")) {
            assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:" + fn + "(?w, " + ROME + "))");
        }
        assertIndexed(1, "?g geo:asWKT ?w FILTER(geof:relate(?w, " + ROME + ", \"T********\"))");
        assertIndexed(1, "?g geo:asWKT ?w FILTER(geof:relate(?w, " + ROME + ", \"F***T****\"))");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:relate(?w, " + ROME + ", \"FF*FF****\"))");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:sfWithin(?w, \"not wkt\"^^geo:wktLiteral))");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:sfWithin(?w, \"POINT(1 2)\"))");
    }

    @Test
    void geoDistances() {
        assertIndexed(1, "?g geo:asWKT ?w FILTER(geof:distance(?w, " + POINT + ", uom:metre) < 1000)");
        assertIndexed(1, "?g geo:asWKT ?w FILTER(geof:distance(" + POINT + ", ?w, uom:degree) <= 0.5)");
        assertIndexed(1, "?g geo:asWKT ?w FILTER(1000 > geof:distance(?w, " + POINT + ", uom:metre))");
        assertIndexed(1, "?g geo:asWKT ?w FILTER(0.01 >= geof:distance(?w, " + POINT + ", uom:radian))");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:distance(?w, " + POINT + ", uom:metre) > 1000)");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:distance(?w, " + ROME + ", uom:metre) < 1000)");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:distance(?w, " + POINT + ", <urn:furlong>) < 1000)");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:distance(?w, " + POINT + ", uom:degree) < 200)");
        assertIndexed(0, "?g geo:asWKT ?w FILTER(geof:distance(?w, " + POINT + ", uom:metre) < ?max)");
    }

    @Test
    void malformedConstantTextQueriesAreRejected() {
        final TupleExpr expr = parse("SELECT * { ?d rdfs:label ?l FILTER(text:matches(?l, \"a AND\")) }");
        assertThrows(MalformedQueryException.class,
                () -> new TextQueryValidator().optimize(expr, null, EmptyBindingSet.getInstance()));
        final TupleExpr notAString = parse("SELECT * { ?d rdfs:label ?l FILTER(text:matches(?l, 42)) }");
        assertThrows(MalformedQueryException.class,
                () -> new TextQueryValidator().optimize(notAString, null, EmptyBindingSet.getInstance()));
        final TupleExpr fine = parse("SELECT * { ?d rdfs:label ?l FILTER(text:matches(?l, ?q)) }");
        new TextQueryValidator().optimize(fine, null, EmptyBindingSet.getInstance()); // a query from a variable is checked per row
    }
}
