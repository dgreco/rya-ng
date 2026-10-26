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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.dgreco.rya.test.accumulo.MiniAccumuloSingleton;

/** The query page and every file it loads are served by the endpoint itself. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SparqlUiIT {
    private static final String PREFIX = "sparqlui" + UUID.randomUUID().toString().substring(0, 8) + "_";

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
    }

    private HttpResponse<String> get(final String path, final String accept) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theQueryPageIsTheHomePage() throws Exception {
        final HttpResponse<String> page = get("/", "text/html");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>rya-ng SPARQL</title>"), page.body());
        assertTrue(page.body().contains("vendor/yasgui/yasgui.min.js"));
    }

    @Test
    void everyAssetIsServed() throws Exception {
        final Map<String, String> assets = Map.ofEntries(
                Map.entry("/vendor/yasgui/yasgui.min.js", "javascript"),
                Map.entry("/vendor/yasgui/yasgui.min.css", "text/css"),
                Map.entry("/vendor/leaflet/leaflet.js", "javascript"),
                Map.entry("/vendor/leaflet/leaflet.css", "text/css"),
                Map.entry("/vendor/leaflet/images/marker-icon.png", "image/png"),
                Map.entry("/vendor/yasgui-chart-plugin/yasgui-chart-plugin.min.js", "javascript"),
                Map.entry("/vendor/yasgui-chart-plugin/yasgui-chart-plugin.min.css", "text/css"),
                Map.entry("/ui/wkt.js", "javascript"),
                Map.entry("/ui/rya-map.js", "javascript"),
                Map.entry("/ui/rya-ui.js", "javascript"),
                Map.entry("/ui/rya-ui.css", "text/css"),
                Map.entry("/ui/favicon.svg", "image/svg+xml"),
                Map.entry("/vendor/THIRD-PARTY-NOTICES.txt", "text/plain"));
        for (final Map.Entry<String, String> asset : assets.entrySet()) {
            final HttpResponse<String> r = get(asset.getKey(), "*/*");
            assertEquals(200, r.statusCode(), asset.getKey());
            assertTrue(r.headers().firstValue("Content-Type").orElse("").contains(asset.getValue()),
                    asset.getKey() + ": " + r.headers().firstValue("Content-Type"));
            assertTrue(!r.body().isEmpty(), asset.getKey());
        }
    }

    @Test
    void theEndpointStillAnswersQueries() throws Exception {
        final HttpResponse<String> r = get("/sparql?query=ASK%7B%7D", "application/sparql-results+json");
        assertEquals(200, r.statusCode());
    }
}
