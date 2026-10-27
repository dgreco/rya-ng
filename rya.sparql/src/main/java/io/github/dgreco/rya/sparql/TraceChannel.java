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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One trace id: the request that reports to it and the stream that reads it, whichever arrives
 * first. Events published before the stream connects are kept (up to {@link #BUFFER}). A slow or
 * vanished reader never holds up the query: failed sends just stop the stream.
 */
final class TraceChannel {
    /** Events kept for a stream that hasn't connected yet. */
    static final int BUFFER = 5000;

    private final ArrayDeque<SseEmitter.SseEventBuilder> buffer = new ArrayDeque<>();
    private final long createdNanos = System.nanoTime();
    private SseEmitter emitter;
    private boolean requested;
    private boolean finished;
    private boolean readerGone;
    private long finishedNanos;
    private long dropped;

    /** @return false if a request already reported to this id. */
    synchronized boolean claimRequest() {
        if (requested) {
            return false;
        }
        requested = true;
        return true;
    }

    /** @return false if a stream already reads this id. */
    synchronized boolean attach(final SseEmitter reader) {
        if (emitter != null) {
            return false;
        }
        emitter = reader;
        while (!buffer.isEmpty() && !readerGone) {
            send(buffer.poll());
        }
        buffer.clear();
        if (finished) {
            reader.complete();
        }
        return true;
    }

    synchronized void publish(final String name, final Object data) {
        if (finished || readerGone) {
            return;
        }
        final SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
        if (emitter != null) {
            send(event);
        } else if (buffer.size() < BUFFER) {
            buffer.add(event);
        } else {
            dropped++;
        }
    }

    /** Sends the final {@code end} event ("ok", "error" or "timeout") and closes the stream. */
    synchronized void finish(final String status) {
        if (finished) {
            return;
        }
        publish("end", Map.of("status", status, "dropped", dropped));
        finished = true;
        finishedNanos = System.nanoTime();
        if (emitter != null && !readerGone) {
            emitter.complete();
        }
    }

    synchronized void readerGone() {
        readerGone = true;
        buffer.clear();
    }

    /** Whether nobody will use this channel any more, or it waited too long for its other half. */
    synchronized boolean expired(final long now, final long maxWaitNanos) {
        if (finished && (emitter != null || now - finishedNanos > maxWaitNanos)) {
            return true;
        }
        return !requested && now - createdNanos > maxWaitNanos;
    }

    synchronized void abandon() {
        if (emitter != null && !finished && !readerGone) {
            send(SseEmitter.event().name("end").data(Map.of("status", "no request", "dropped", dropped), MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        finished = true;
    }

    private void send(final SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (final IOException | IllegalStateException e) {
            readerGone = true;
        }
    }
}
