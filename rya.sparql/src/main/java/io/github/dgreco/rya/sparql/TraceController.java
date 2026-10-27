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

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /sparql/trace/<id>}: a Server-Sent Events stream of what the request sent with
 * {@code trace=<id>} does in Accumulo. Events: {@code note}, {@code plan}, {@code scan},
 * {@code progress} (long scans, about once a second), {@code scan-end}, {@code flush},
 * {@code summary} and a final {@code end} with the status.
 * Open the stream first or right after sending the request: events wait for it.
 */
@RestController
public class TraceController {
    private final TraceHub hub;
    private final RyaProperties.Sparql settings;

    public TraceController(final TraceHub hub, final RyaProperties props) {
        this.hub = hub;
        this.settings = props.sparql();
    }

    @GetMapping("/sparql/trace/{id}")
    public SseEmitter stream(@PathVariable("id") final String id) {
        if (!settings.allowTrace()) {
            throw new SparqlRequestException(HttpStatus.FORBIDDEN, "Tracing is turned off (rya.sparql.allow-trace)");
        }
        final SseEmitter emitter = new SseEmitter(streamTimeout().toMillis());
        hub.subscribe(id, emitter);
        return emitter;
    }

    /** Long enough for the longest query a request may run, plus the wait for the request. */
    private Duration streamTimeout() {
        final Duration max = settings.maxQueryTimeout();
        final Duration def = settings.queryTimeout();
        final Duration longest = max.isZero() || def.isZero() ? Duration.ofHours(24) : max.compareTo(def) > 0 ? max : def;
        return longest.plus(TraceHub.MAX_WAIT);
    }
}
