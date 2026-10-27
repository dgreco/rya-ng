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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.dgreco.rya.test.accumulo.MiniAccumuloSingleton;

/** Traces requests over real HTTP: the request with trace=, the events on /sparql/trace. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SparqlTraceIT {
    private static final String PREFIX = "traceit" + UUID.randomUUID().toString().substring(0, 8) + "_";

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

    /** One server-sent event. */
    private record Event(String name, String data) {
    }

    @BeforeEach
    void data() throws Exception {
        assertEquals(204, update("CLEAR ALL", null).statusCode());
        assertEquals(204, update("INSERT DATA { <urn:alice> <urn:knows> <urn:bob>, <urn:carol> . "
                + "<urn:bob> <urn:age> 30 . <urn:carol> <urn:age> 40 }", null).statusCode());
    }

    @Test
    void aQueryStreamsItsPlanScansAndSummary() throws Exception {
        final String id = newId();
        final CompletableFuture<List<Event>> stream = stream(id);
        final HttpResponse<String> r = query("SELECT ?f ?a WHERE { <urn:alice> <urn:knows> ?f . ?f <urn:age> ?a }", id);
        assertEquals(200, r.statusCode(), r.body());
        final List<Event> events = stream.get(60, TimeUnit.SECONDS);
        final List<String> names = events.stream().map(Event::name).toList();
        assertEquals("note", names.get(0), names.toString());
        assertTrue(events.get(0).data().contains("SELECT ?f ?a"), events.get(0).data());
        assertEquals(2, names.stream().filter("plan"::equals).count(), names.toString());
        assertTrue(names.contains("scan") && names.contains("scan-end"), names.toString());
        assertTrue(events.stream().anyMatch(e -> e.name().equals("scan") && e.data().contains(PREFIX + "spo")
                && e.data().contains("lookup <urn:alice> <urn:knows> ?")), events.toString());
        assertEquals("summary", names.get(names.size() - 2));
        assertEquals("end", names.get(names.size() - 1));
        assertTrue(events.get(events.size() - 1).data().contains("\"status\":\"ok\""), events.get(events.size() - 1).data());
    }

    @Test
    void theStreamMayConnectAfterTheRequest() throws Exception {
        final String id = newId();
        assertEquals(204, update("INSERT DATA { <urn:dave> <urn:age> 50 }", id).statusCode());
        final List<Event> events = stream(id).get(60, TimeUnit.SECONDS);
        assertTrue(events.stream().anyMatch(e -> e.name().equals("flush") && e.data().contains(PREFIX + "spo")), events.toString());
        assertTrue(events.get(events.size() - 1).data().contains("\"status\":\"ok\""));
    }

    @Test
    void aFailingRequestStillEndsItsStream() throws Exception {
        final String id = newId();
        final CompletableFuture<List<Event>> stream = stream(id);
        assertEquals(400, query("SELECT WHERE {", id).statusCode());
        final List<Event> events = stream.get(60, TimeUnit.SECONDS);
        assertEquals("end", events.get(events.size() - 1).name());
        assertTrue(events.get(events.size() - 1).data().contains("\"status\":\"error\""), events.toString());
    }

    @Test
    void idsAreCheckedAndServeOneRequest() throws Exception {
        assertEquals(400, query("ASK {}", "short").statusCode());
        final String id = newId();
        assertEquals(200, query("ASK {}", id).statusCode());
        assertEquals(409, query("ASK {}", id).statusCode());
        stream(id).get(60, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ helpers

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private URI uri(final String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> query(final String query, final String trace) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/sparql?query=" + enc(query) + (trace == null ? "" : "&trace=" + enc(trace))))
                .header("Accept", "application/sparql-results+json").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> update(final String update, final String trace) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/sparql" + (trace == null ? "" : "?trace=" + enc(trace))))
                .header("Content-Type", SparqlController.SPARQL_UPDATE).POST(HttpRequest.BodyPublishers.ofString(update)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Reads the trace stream until the server closes it. */
    private CompletableFuture<List<Event>> stream(final String id) {
        final HttpRequest request = HttpRequest.newBuilder(uri("/sparql/trace/" + id)).header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).thenApply(response -> {
            assertEquals(200, response.statusCode());
            final List<Event> events = new ArrayList<>();
            try (Stream<String> lines = response.body()) {
                String name = null;
                for (final String line : (Iterable<String>) lines::iterator) {
                    if (line.startsWith("event:")) {
                        name = line.substring(6).trim();
                    } else if (line.startsWith("data:") && name != null) {
                        events.add(new Event(name, line.substring(5)));
                        name = null;
                    }
                }
            }
            return events;
        });
    }
}
