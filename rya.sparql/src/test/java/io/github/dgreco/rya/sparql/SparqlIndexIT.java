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
package io.github.dgreco.rya.sparql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.dgreco.rya.accumulo.index.IndexSearcher;
import io.github.dgreco.rya.test.accumulo.MiniAccumuloSingleton;

/** Text and geo search through the endpoint, with both indexes on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SparqlIndexIT {
    private static final String PREFIX = "sparqlidx" + UUID.randomUUID().toString().substring(0, 8) + "_";
    private static final String PREFIXES = """
            PREFIX text: <urn:rya-ng:text#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
            PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
            """;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void accumulo(final DynamicPropertyRegistry registry) {
        final var cluster = MiniAccumuloSingleton.getCluster();
        registry.add("rya.accumulo.instance", cluster::getInstanceName);
        registry.add("rya.accumulo.zookeepers", cluster::getZooKeepers);
        registry.add("rya.accumulo.user", () -> MiniAccumuloSingleton.ROOT_USER);
        registry.add("rya.accumulo.password", () -> MiniAccumuloSingleton.ROOT_PASSWORD);
        registry.add("rya.table-prefix", () -> PREFIX);
        registry.add("rya.text-index", () -> "true");
        registry.add("rya.geo-index", () -> "true");
    }

    @BeforeEach
    void load() throws Exception {
        assertEquals(204, update("CLEAR ALL").statusCode());
        assertEquals(204, update(PREFIXES + """
                INSERT DATA {
                  <urn:colosseum> rdfs:label "Colosseo, the Flavian Amphitheatre"@it ;
                      geo:hasGeometry <urn:colosseum-g> .
                  <urn:colosseum-g> geo:asWKT "POINT(12.4922 41.8902)"^^geo:wktLiteral .
                  <urn:louvre> rdfs:label "Musée du Louvre" ; geo:hasGeometry <urn:louvre-g> .
                  <urn:louvre-g> geo:asWKT "POINT(2.3376 48.8606)"^^geo:wktLiteral .
                }""").statusCode());
    }

    @Test
    void textSearchUsesTheIndex() throws Exception {
        final long before = IndexSearcher.getSearchCount();
        final HttpResponse<String> r = query("SELECT ?s WHERE { ?s rdfs:label ?l FILTER(text:matches(?l, \"musee louv*\")) }");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("urn:louvre"), r.body());
        assertFalse(r.body().contains("urn:colosseum"), r.body());
        assertTrue(IndexSearcher.getSearchCount() > before, "the text index was not used");
    }

    @Test
    void geoSearchUsesTheIndex() throws Exception {
        final long before = IndexSearcher.getSearchCount();
        final HttpResponse<String> r = query("SELECT ?s WHERE { ?s geo:hasGeometry ?g . ?g geo:asWKT ?w "
                + "FILTER(geof:distance(?w, \"POINT(12.49 41.89)\"^^geo:wktLiteral, uom:metre) < 2000) }");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("urn:colosseum"), r.body());
        assertFalse(r.body().contains("urn:louvre"), r.body());
        assertTrue(IndexSearcher.getSearchCount() > before, "the geo index was not used");
    }

    @Test
    void aMalformedTextQueryIsABadRequest() throws Exception {
        final HttpResponse<String> r = query("SELECT ?s WHERE { ?s rdfs:label ?l FILTER(text:matches(?l, \"(louvre\")) }");
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("text query"), r.body());
    }

    private HttpResponse<String> query(final String query) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sparql"))
                .header("Content-Type", SparqlController.SPARQL_QUERY)
                .header("Accept", "application/sparql-results+json")
                .POST(HttpRequest.BodyPublishers.ofString(PREFIXES + query)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> update(final String update) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sparql"))
                .header("Content-Type", SparqlController.SPARQL_UPDATE)
                .POST(HttpRequest.BodyPublishers.ofString(update)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
