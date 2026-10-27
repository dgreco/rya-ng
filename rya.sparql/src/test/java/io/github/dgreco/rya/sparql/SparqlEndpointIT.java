package io.github.dgreco.rya.sparql;

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

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.security.Authorizations;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import io.github.dgreco.rya.test.accumulo.MiniAccumuloSingleton;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
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
 * Drives the endpoint over real HTTP, backed by a MiniAccumuloCluster.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SparqlEndpointIT {
    private static final String PREFIX = "sparqlit" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @LocalServerPort
    private int port;

    @Autowired
    private SailRepository repository;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void accumulo(final DynamicPropertyRegistry registry) {
        final var cluster = MiniAccumuloSingleton.getCluster();
        registry.add("rya.accumulo.instance", cluster::getInstanceName);
        registry.add("rya.accumulo.zookeepers", cluster::getZooKeepers);
        registry.add("rya.accumulo.user", () -> MiniAccumuloSingleton.ROOT_USER);
        registry.add("rya.accumulo.password", () -> MiniAccumuloSingleton.ROOT_PASSWORD);
        registry.add("rya.table-prefix", () -> PREFIX);
        registry.add("rya.inference", () -> "true");
        registry.add("rya.sparql.authorizations", () -> "A,B");
        registry.add("rya.sparql.allow-request-visibility", () -> "true");
        registry.add("rya.sparql.max-query-timeout", () -> "60s");
    }

    @BeforeAll
    static void grantAuths() throws Exception {
        try (AccumuloClient client = Accumulo.newClient().from(MiniAccumuloSingleton.getClientProperties()).build()) {
            client.securityOperations().changeUserAuthorizations(MiniAccumuloSingleton.ROOT_USER, new Authorizations("A", "B"));
        }
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
    void blankNodesComeBackAsBlankNodes() throws Exception {
        update("INSERT DATA { _:b <urn:p> \"x\" }");
        final HttpResponse<String> r = get("SELECT ?s WHERE { ?s <urn:p> \"x\" }", "application/sparql-results+json");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().replace(" ", "").contains("\"type\":\"bnode\""), r.body());
        assertFalse(r.body().contains("urn:rya-ng/bnode"), r.body());

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
        final HttpResponse<String> r = get("PREFIX stats: <urn:rya-ng:stats#> "
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
