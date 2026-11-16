package org.tripatha.sparql;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.tripatha.rdftriplestore.RdfCloudTripleStore;
import org.tripatha.test.TestBackends;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.resultio.QueryResultIO;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives the endpoint over real HTTP, backed by the test cluster of {@code -Dtripatha.test.backend}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SparqlEndpointIT {
    private static final String PREFIX = "sparqlit" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @LocalServerPort
    private int port;

    @Autowired
    private SailRepository repository;

    private static final TupleQueryResultFormat JSON = TupleQueryResultFormat.JSON;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void store(final DynamicPropertyRegistry registry) {
        // The backend of -Dtripatha.test.backend, reached as the test account.
        TestBackends.current().endpointProperties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("tripatha.table-prefix", () -> PREFIX);
        registry.add("tripatha.inference", () -> "true");
        registry.add("tripatha.sparql.authorizations", () -> "A,B");
        registry.add("tripatha.sparql.allow-request-visibility", () -> "true");
        registry.add("tripatha.sparql.max-query-timeout", () -> "60s");
    }

    @BeforeAll
    static void grantAuths() {
        TestBackends.current().grantLabels("A", "B");
    }

    @BeforeEach
    void clear() throws Exception {
        assertEquals(204, update("CLEAR ALL").statusCode());
    }

    @Test
    void updateThenSelectAsJson() throws Exception {
        assertEquals(204, update("INSERT DATA { GRAPH <urn:g1> { <urn:alice> <urn:knows> <urn:bob> } }").statusCode());

        final HttpResponse<String> r = post(SparqlController.SPARQL_QUERY,
                "SELECT ?s ?o WHERE { GRAPH <urn:g1> { ?s <urn:knows> ?o } }", "application/sparql-results+json");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/sparql-results+json"));
        assertTrue(r.body().contains("urn:alice"), r.body());
        assertTrue(r.body().contains("urn:bob"), r.body());
    }

    @Test
    void getAskAndXmlResults() throws Exception {
        update("INSERT DATA { <urn:alice> <urn:age> 42 }");
        final HttpResponse<String> ask = get("ASK { <urn:alice> <urn:age> 42 }", null);
        assertEquals(200, ask.statusCode());
        assertTrue(ask.body().replace(" ", "").contains("\"boolean\":true"), ask.body());

        final HttpResponse<String> xml = get("SELECT ?age WHERE { <urn:alice> <urn:age> ?age }", "application/sparql-results+xml");
        assertEquals(200, xml.statusCode());
        assertTrue(xml.body().replace('\'', '"').contains("<literal datatype=\"http://www.w3.org/2001/XMLSchema#integer\">42</literal>"), xml.body());
    }

    @Test
    void constructAsTurtle() throws Exception {
        update("INSERT DATA { <urn:alice> <urn:knows> <urn:bob> }");
        final HttpResponse<String> r = post(SparqlController.SPARQL_QUERY,
                "CONSTRUCT { ?s <urn:friendOf> ?o } WHERE { ?s <urn:knows> ?o }", "text/turtle");
        assertEquals(200, r.statusCode());
        final Model model = Rio.parse(new StringReader(r.body()), RDFFormat.TURTLE);
        final var vf = SimpleValueFactory.getInstance();
        assertTrue(model.contains(vf.createIRI("urn:alice"), vf.createIRI("urn:friendOf"), vf.createIRI("urn:bob")), r.body());
    }

    @Test
    void tripleTermsAreExactValues() throws Exception {
        update("INSERT DATA { <urn:s> <urn:p> \"a\", \"b\" }");

        // A triple term is a value like any other: one that isn't there deletes nothing.
        assertEquals(204, update("DELETE DATA { <urn:s> <urn:p> <<( <urn:a> <urn:b> <urn:c> )>> }").statusCode());
        assertTrue(ask("ASK { <urn:s> <urn:p> \"a\", \"b\" }"));

        assertEquals(204, update("INSERT DATA { <urn:s> <urn:p> <<( <urn:a> <urn:b> <urn:c> )>>, <<( <urn:a> <urn:b> \"c\" )>> }").statusCode());
        final String count = "ASK { { SELECT (COUNT(?o) AS ?n) WHERE { <urn:s> <urn:p> ?o FILTER(isTRIPLE(?o)) } } FILTER(?n = %d) }";
        assertTrue(ask(count.formatted(2)));
        assertTrue(ask("ASK { <urn:s> <urn:p> <<( <urn:a> <urn:b> <urn:c> )>> }"));
        assertFalse(ask("ASK { <urn:s> <urn:p> <<( <urn:a> <urn:b> <urn:d> )>> }"));
        assertTrue(ask("ASK { ?s <urn:p> <<( <urn:a> ?p \"c\" )>> }"));
        assertTrue(ask("ASK { <urn:s> <urn:p> ?o FILTER(isTRIPLE(?o) && SUBJECT(?o) = <urn:a> && OBJECT(?o) = <urn:c>) }"));

        assertEquals(204, update("DELETE DATA { <urn:s> <urn:p> <<( <urn:a> <urn:b> <urn:c> )>> }").statusCode());
        assertTrue(ask(count.formatted(1)));
        assertTrue(ask("ASK { <urn:s> <urn:p> ?o FILTER(isTRIPLE(?o) && OBJECT(?o) = \"c\") }"));
        assertTrue(ask("ASK { <urn:s> <urn:p> \"a\", \"b\" }"));
    }

    @Test
    void baseDirectionLiterals() throws Exception {
        assertEquals(204, update("INSERT DATA { <urn:s> <urn:p> \"x\"@ar--rtl, \"x\"@ar, \"x\"@ar--ltr }").statusCode());
        assertTrue(ask("ASK { <urn:s> <urn:p> \"x\"@ar--rtl }"));
        assertTrue(ask("ASK { <urn:s> <urn:p> ?o FILTER(LANGDIR(?o) = \"rtl\" && LANG(?o) = \"ar\") }"));

        final HttpResponse<String> r = get("SELECT ?o WHERE { <urn:s> <urn:p> ?o FILTER(hasLANGDIR(?o)) }",
                "application/sparql-results+xml");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("rtl") && r.body().contains("ltr"), r.body());

        // An exact delete removes only that direction.
        assertEquals(204, update("DELETE DATA { <urn:s> <urn:p> \"x\"@ar--rtl }").statusCode());
        assertFalse(ask("ASK { <urn:s> <urn:p> \"x\"@ar--rtl }"));
        assertTrue(ask("ASK { <urn:s> <urn:p> \"x\"@ar--ltr }"));
        assertTrue(ask("ASK { <urn:s> <urn:p> \"x\"@ar }"));
    }

    @Test
    void annotatedStatementsAreInsertedQueriedAndDeleted() throws Exception {
        assertEquals(204, update("INSERT DATA { <urn:a> <urn:knows> <urn:b> ~ <urn:r> {| <urn:since> 2020 ; <urn:source> <urn:web> |} . "
                + "<urn:a> <urn:knows> <urn:c> {| <urn:since> 2021 |} }").statusCode());

        assertEquals(Set.of("2020"), column(select("SELECT ?y WHERE { <urn:a> <urn:knows> <urn:b> {| <urn:since> ?y |} }", JSON), "y"));
        assertEquals(Set.of("urn:b", "urn:c"), column(select("SELECT ?who WHERE { << <urn:a> <urn:knows> ?who >> <urn:since> ?y }", JSON), "who"));
        assertEquals(Set.of("urn:r"), column(select("SELECT ?r WHERE { ?r rdf:reifies <<( <urn:a> <urn:knows> <urn:b> )>> }", JSON), "r"));
        assertTrue(ask("ASK { <urn:a> <urn:knows> <urn:b>, <urn:c> }"));

        // An exact delete of the reifying statement leaves the annotation's other statements and the asserted triple.
        assertEquals(204, update("DELETE DATA { <urn:r> rdf:reifies <<( <urn:a> <urn:knows> <urn:b> )>> }").statusCode());
        assertFalse(ask("ASK { <urn:a> <urn:knows> <urn:b> {| <urn:since> ?y |} }"));
        assertTrue(ask("ASK { <urn:r> <urn:since> 2020 . <urn:a> <urn:knows> <urn:b> }"));
        assertTrue(ask("ASK { <urn:a> <urn:knows> <urn:c> {| <urn:since> 2021 |} }"));

        assertEquals(204, update("DELETE WHERE { ?r rdf:reifies <<( <urn:a> <urn:knows> <urn:c> )>> ; ?p ?o }").statusCode());
        assertFalse(ask("ASK { ?r rdf:reifies ?t }"));
    }

    @Test
    void tripleTermsAndDirectionsInEverySelectFormat() throws Exception {
        assertEquals(204, update("INSERT DATA { <urn:r> rdf:reifies <<( <urn:a> <urn:says> <<( <urn:b> <urn:label> \"x\"@ar--rtl )>> )>> ; "
                + "<urn:label> \"y\"@he--rtl, \"z\"@en--ltr, \"w\"@en }").statusCode());
        final ValueFactory vf = SimpleValueFactory.getInstance();
        final Value term = vf.createTripleTerm(vf.createIRI("urn:a"), vf.createIRI("urn:says"), vf.createTripleTerm(
                vf.createIRI("urn:b"), vf.createIRI("urn:label"), vf.createLiteral("x", "ar", Literal.BaseDirection.RTL)));
        final Set<Value> expected = Set.of(term, vf.createLiteral("y", "he", Literal.BaseDirection.RTL),
                vf.createLiteral("z", "en", Literal.BaseDirection.LTR), vf.createLiteral("w", "en"));

        final String query = "SELECT ?o WHERE { <urn:r> ?p ?o }";
        for (final TupleQueryResultFormat format : List.of(JSON, TupleQueryResultFormat.SPARQL)) {
            assertEquals(expected, values(select(query, format), "o"), format.getName());
        }
        // RDF4J's TSV reader can't read a nested triple term back, so the TSV text is compared.
        final Set<String> tsv = Set.of(select(query, TupleQueryResultFormat.TSV).body().strip().split("\\R"));
        assertEquals(Set.of("?o", "<<( <urn:a> <urn:says> <<( <urn:b> <urn:label> \"x\"@ar--rtl )>> )>>", "\"y\"@he--rtl",
                "\"z\"@en--ltr", "\"w\"@en"), tsv);
        final String json = get(query, JSON.getDefaultMIMEType()).body().replace(" ", "");
        assertTrue(json.contains("\"its:dir\":\"rtl\"") && json.contains("\"its:dir\":\"ltr\""), json);
        assertFalse(json.contains("--"), json);
        assertTrue(json.contains("\"type\":\"triple\""), json);

        // CSV has no types: IRIs and literals are bare values, inside a triple term too.
        final Set<String> csv = Set.of(get(query, "text/csv").body().strip().split("\\R"));
        assertEquals(Set.of("o", "\"<<( urn:a urn:says <<( urn:b urn:label \"\"x\"\" )>> )>>\"", "y", "z", "w"), csv);
    }

    @Test
    void constructKeepsTripleTermsAndDirections() throws Exception {
        assertEquals(204, update("INSERT DATA { <urn:a> <urn:knows> <urn:b> ~ <urn:r> {| <urn:note> \"x\"@ar--rtl |} }").statusCode());
        final ValueFactory vf = SimpleValueFactory.getInstance();
        final Value term = vf.createTripleTerm(vf.createIRI("urn:a"), vf.createIRI("urn:knows"), vf.createIRI("urn:b"));
        for (final RDFFormat format : List.of(RDFFormat.TURTLE, RDFFormat.NTRIPLES, RDFFormat.NQUADS, RDFFormat.TRIG, RDFFormat.RDFXML)) {
            final HttpResponse<String> r = get("CONSTRUCT WHERE { ?s ?p ?o }", format.getDefaultMIMEType());
            assertEquals(200, r.statusCode(), r.body());
            final Model model = Rio.parse(new StringReader(r.body()), "", format);
            assertEquals(3, model.size(), format.getName() + "\n" + r.body());
            assertTrue(model.contains(vf.createIRI("urn:r"), RDF.REIFIES, term), format.getName() + "\n" + r.body());
            assertTrue(model.contains(vf.createIRI("urn:r"), vf.createIRI("urn:note"),
                    vf.createLiteral("x", "ar", Literal.BaseDirection.RTL)), format.getName() + "\n" + r.body());
        }
    }

    @Test
    void blankNodesInsideTripleTermsComeBackAsBlankNodes() throws Exception {
        update("INSERT DATA { <urn:s> <urn:p> <<( _:b <urn:q> <<( <urn:x> <urn:q> _:c )>> )>> . <urn:a> <urn:knows> <urn:b> {| <urn:since> 2020 |} }");
        final HttpResponse<String> r = get("SELECT ?s ?o WHERE { ?s ?p ?o }", JSON.getDefaultMIMEType());
        assertEquals(200, r.statusCode(), r.body());
        assertFalse(r.body().contains("urn:tripatha/bnode"), r.body());
        final Set<Value> objects = values(r, "o");
        final TripleTerm outer = (TripleTerm) objects.stream().filter(v -> v instanceof TripleTerm t
                && t.getObject() instanceof TripleTerm).findFirst().orElseThrow();
        assertTrue(outer.getSubject() instanceof BNode, outer.toString());
        assertTrue(((TripleTerm) outer.getObject()).getObject() instanceof BNode, outer.toString());
        // The anonymous reifier of an annotation is a blank node too.
        assertTrue(values(r, "s").stream().anyMatch(v -> v instanceof BNode), r.body());

        final HttpResponse<String> t = get("CONSTRUCT WHERE { ?s ?p ?o }", "application/n-triples");
        assertFalse(t.body().contains("urn:tripatha/bnode"), t.body());
        assertTrue(t.body().contains("<<( _:"), t.body());
    }

    @Test
    void labelsApplyToAnnotatedStatements() throws Exception {
        assertEquals(204, post(SparqlController.SPARQL_UPDATE,
                "INSERT DATA { <urn:a> <urn:knows> <urn:b> {| <urn:since> 2020 |} }", null, "visibility=A").statusCode());
        final String annotated = "ASK { <urn:a> <urn:knows> <urn:b> {| <urn:since> 2020 |} }";
        assertTrue(askWith(annotated, "A"));
        assertFalse(askWith(annotated, "B"));
        assertFalse(askWith("ASK { ?r rdf:reifies ?t }", "B"));
    }

    @Test
    void blankNodesComeBackAsBlankNodes() throws Exception {
        update("INSERT DATA { _:b <urn:p> \"x\" }");
        final HttpResponse<String> r = get("SELECT ?s WHERE { ?s <urn:p> \"x\" }", "application/sparql-results+json");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().replace(" ", "").contains("\"type\":\"bnode\""), r.body());
        assertFalse(r.body().contains("urn:tripatha/bnode"), r.body());

        final HttpResponse<String> t = get("CONSTRUCT WHERE { ?s <urn:p> ?o }", "text/turtle");
        assertTrue(t.body().contains("_:"), t.body());
    }

    @Test
    void formEncodedQueryAndUpdate() throws Exception {
        final HttpResponse<String> u = form("update=" + enc("INSERT DATA { <urn:x> <urn:p> \"form\" }"), null);
        assertEquals(204, u.statusCode());
        final HttpResponse<String> q = form("query=" + enc("SELECT ?o WHERE { <urn:x> <urn:p> ?o }"), "text/csv");
        assertEquals(200, q.statusCode());
        assertTrue(q.body().contains("form"), q.body());
    }

    @Test
    void clearAndDropNamedGraphs() throws Exception {
        update("INSERT DATA { GRAPH <urn:g1> { <urn:a> <urn:p> 1 } GRAPH <urn:g2> { <urn:b> <urn:p> 2 } <urn:c> <urn:p> 3 }");
        assertTrue(ask("ASK { GRAPH <urn:g1> { ?s ?p ?o } }"));

        assertEquals(204, update("CLEAR GRAPH <urn:g1>").statusCode());
        assertFalse(ask("ASK { GRAPH <urn:g1> { ?s ?p ?o } }"));
        assertTrue(ask("ASK { GRAPH <urn:g2> { ?s ?p ?o } }"));

        assertEquals(204, update("DROP NAMED").statusCode());
        assertFalse(ask("ASK { GRAPH ?g { ?s ?p ?o } }"));
        assertTrue(ask("ASK { <urn:c> <urn:p> 3 }"), "default graph must survive DROP NAMED");
    }

    @Test
    void addCopyAndMoveRespectGraphBoundaries() throws Exception {
        // Inference is on in this test class; graph-scoped operations must still see only their graph.
        update("INSERT DATA { GRAPH <urn:g1> { <urn:a> <urn:p> 1 } <urn:d> <urn:p> 4 }");

        assertEquals(204, update("COPY <urn:g1> TO <urn:g2>").statusCode());
        assertTrue(ask("ASK { GRAPH <urn:g2> { <urn:a> <urn:p> 1 } }"));
        assertFalse(ask("ASK { GRAPH <urn:g2> { <urn:d> <urn:p> 4 } }"));

        assertEquals(204, update("ADD DEFAULT TO <urn:g3>").statusCode());
        assertTrue(ask("ASK { GRAPH <urn:g3> { <urn:d> <urn:p> 4 } }"));
        assertFalse(ask("ASK { GRAPH <urn:g3> { <urn:a> <urn:p> 1 } }"), "ADD DEFAULT must not copy named graphs");

        assertEquals(204, update("MOVE <urn:g2> TO <urn:g4>").statusCode());
        assertFalse(ask("ASK { GRAPH <urn:g2> { ?s ?p ?o } }"));
        assertTrue(ask("ASK { GRAPH <urn:g4> { <urn:a> <urn:p> 1 } }"));
    }

    @Test
    void datasetParameterRestrictsTheDefaultGraph() throws Exception {
        update("INSERT DATA { GRAPH <urn:g1> { <urn:a> <urn:p> 1 } GRAPH <urn:g2> { <urn:b> <urn:p> 2 } }");
        final HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("?default-graph-uri=" + enc("urn:g1")))
                .header("Content-Type", SparqlController.SPARQL_QUERY)
                .header("Accept", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("SELECT ?s WHERE { ?s <urn:p> ?o }")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("urn:a"), r.body());
        assertFalse(r.body().contains("urn:b"), r.body());
    }

    @Test
    void errorsMapToProtocolStatusCodes() throws Exception {
        assertEquals(400, get("SELECT WHERE {", null).statusCode());
        assertEquals(400, post(SparqlController.SPARQL_UPDATE, "INSERT DATA {", null).statusCode());
        assertEquals(406, get("SELECT * WHERE { ?s ?p ?o }", "image/png").statusCode());
        assertEquals(400, http.send(HttpRequest.newBuilder(uri("")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void visibilityAndAuthorizations() throws Exception {
        final HttpResponse<String> u = http.send(HttpRequest.newBuilder(uri("?visibility=A"))
                .header("Content-Type", SparqlController.SPARQL_UPDATE)
                .POST(HttpRequest.BodyPublishers.ofString("INSERT DATA { <urn:secret> <urn:p> \"hidden\" }")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, u.statusCode(), u.body());

        final String askSecret = "ASK { <urn:secret> <urn:p> \"hidden\" }";
        assertTrue(askWith(askSecret, "A"));
        assertFalse(askWith(askSecret, "B"));
        assertTrue(ask(askSecret), "no 'auths' means all configured authorizations");
        assertEquals(403, getWithAuths(askSecret, "C").statusCode());
    }

    @Test
    void timeoutParameterIsCheckedAndCapped() throws Exception {
        final String ask = "ASK { ?s ?p ?o }";
        assertEquals(200, getWith(ask, "timeout=60").statusCode());
        assertEquals(400, getWith(ask, "timeout=soon").statusCode());
        assertEquals(400, getWith(ask, "timeout=0").statusCode());
        final HttpResponse<String> tooLong = getWith(ask, "timeout=61");
        assertEquals(403, tooLong.statusCode());
        assertTrue(tooLong.body().contains("at most 60 seconds"), tooLong.body());
        assertEquals(403, post(SparqlController.SPARQL_UPDATE, "CLEAR SILENT GRAPH <urn:none>", null, "timeout=61").statusCode());
    }

    @Test
    void approximateGraphSizes() throws Exception {
        assertEquals(204, update("INSERT DATA { GRAPH <urn:g1> { <urn:a> <urn:p> <urn:b> . <urn:a> <urn:q> <urn:c> } "
                + "GRAPH <urn:g2> { <urn:a> <urn:p> <urn:c> } }").statusCode());
        final HttpResponse<String> r = get("PREFIX stats: <urn:tripatha:stats#> "
                + "SELECT ?g ?n WHERE { ?g stats:approxSize ?n } ORDER BY ?g", "text/csv");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("g,n\r\nurn:g1,2\r\nurn:g2,1\r\n", r.body());
    }

    @Test
    void subClassOfInference() throws Exception {
        update("INSERT DATA { <urn:Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <urn:Animal> . "
                + "<urn:rex> a <urn:Dog> }");
        ((RdfCloudTripleStore<?>) repository.getSail()).getInferenceEngine().refreshGraph();
        assertTrue(ask("ASK { <urn:rex> a <urn:Animal> }"));
    }

    // ------------------------------------------------------------------ helpers

    private URI uri(final String query) {
        return URI.create("http://localhost:" + port + "/sparql" + query);
    }

    private static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(final String query, final String accept) throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder(uri("?query=" + enc(query))).GET();
        if (accept != null) {
            b.header("Accept", accept);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithAuths(final String query, final String auths) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("?query=" + enc(query) + "&auths=" + enc(auths))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWith(final String query, final String params) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("?query=" + enc(query) + "&" + params)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(final String contentType, final String body, final String accept) throws Exception {
        return post(contentType, body, accept, null);
    }

    private HttpResponse<String> post(final String contentType, final String body, final String accept, final String params)
            throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder(uri(params == null ? "" : "?" + params)).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (accept != null) {
            b.header("Accept", accept);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> select(final String query, final TupleQueryResultFormat format) throws Exception {
        final HttpResponse<String> r = get(query, format.getDefaultMIMEType());
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith(format.getDefaultMIMEType()), r.headers().toString());
        return r;
    }

    /** The values of one variable, read back with RDF4J's parser for the response's format. */
    private static Set<Value> values(final HttpResponse<String> response, final String variable) throws Exception {
        final String type = response.headers().firstValue("Content-Type").orElseThrow().split(";")[0];
        final TupleQueryResultFormat format = Stream.of(JSON, TupleQueryResultFormat.SPARQL, TupleQueryResultFormat.TSV)
                .filter(f -> f.hasMIMEType(type)).findFirst().orElseThrow();
        final Set<Value> values = new HashSet<>();
        try (TupleQueryResult result = QueryResultIO.parseTuple(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)), format, null)) {
            for (final BindingSet row : result) {
                if (row.hasBinding(variable)) {
                    values.add(row.getValue(variable));
                }
            }
        }
        return values;
    }

    private static Set<String> column(final HttpResponse<String> response, final String variable) throws Exception {
        final Set<String> labels = new HashSet<>();
        values(response, variable).forEach(v -> labels.add(v.stringValue()));
        return labels;
    }

    private HttpResponse<String> form(final String body, final String accept) throws Exception {
        return post("application/x-www-form-urlencoded", body, accept);
    }

    private HttpResponse<String> update(final String update) throws Exception {
        return post(SparqlController.SPARQL_UPDATE, update, null);
    }

    private boolean ask(final String query) throws Exception {
        final HttpResponse<String> r = get(query, "application/sparql-results+json");
        assertEquals(200, r.statusCode(), r.body());
        return r.body().replace(" ", "").contains("\"boolean\":true");
    }

    private boolean askWith(final String query, final String auths) throws Exception {
        final HttpResponse<String> r = getWithAuths(query, auths);
        assertEquals(200, r.statusCode(), r.body());
        return r.body().replace(" ", "").contains("\"boolean\":true");
    }
}
