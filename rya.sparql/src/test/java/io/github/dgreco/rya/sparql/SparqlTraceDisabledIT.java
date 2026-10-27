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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.dgreco.rya.test.accumulo.MiniAccumuloSingleton;

/** With rya.sparql.allow-trace=false, neither side of a trace is accepted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SparqlTraceDisabledIT {
    private static final String PREFIX = "traceoff" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void accumulo(final DynamicPropertyRegistry registry) {
        final var cluster = MiniAccumuloSingleton.getCluster();
        registry.add("rya.accumulo.instance", cluster::getInstanceName);
        registry.add("rya.accumulo.zookeepers", cluster::getZooKeepers);
        registry.add("rya.accumulo.user", () -> MiniAccumuloSingleton.ROOT_USER);
        registry.add("rya.accumulo.password", () -> MiniAccumuloSingleton.ROOT_PASSWORD);
        registry.add("rya.table-prefix", () -> PREFIX);
        registry.add("rya.sparql.allow-trace", () -> "false");
    }

    @Test
    void tracingIsRefused() throws Exception {
        final HttpClient http = HttpClient.newHttpClient();
        final String id = UUID.randomUUID().toString();
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sparql/trace/" + id)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sparql?query=ASK%7B%7D&trace=" + id))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sparql?query=ASK%7B%7D")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
