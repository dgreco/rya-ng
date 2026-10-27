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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

/**
 * Pairs traced requests ({@code trace=<id>}) with the streams that read them
 * ({@code /sparql/trace/<id>}). Each id serves one request and one stream; channels whose other
 * half doesn't show up are dropped after {@link #MAX_WAIT}.
 */
@Component
public class TraceHub {
    /** Ids are chosen by the client; long random ones can't be guessed by others. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    static final Duration MAX_WAIT = Duration.ofMinutes(2);

    private final Map<String, TraceChannel> channels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "rya-trace-cleaner");
        t.setDaemon(true);
        return t;
    });

    public TraceHub() {
        cleaner.scheduleWithFixedDelay(this::cleanUp, 30, 30, TimeUnit.SECONDS);
    }

    static void checkId(final String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "A trace id is 16 to 64 letters, digits, '-' or '_'");
        }
    }

    /** The channel a request reports to. */
    TraceChannel forRequest(final String id) {
        checkId(id);
        final TraceChannel channel = channels.computeIfAbsent(id, k -> new TraceChannel());
        if (!channel.claimRequest()) {
            throw new SparqlRequestException(HttpStatus.CONFLICT, "Trace id " + id + " is already in use");
        }
        return channel;
    }

    /** Connects a stream to its channel. */
    void subscribe(final String id, final SseEmitter emitter) {
        checkId(id);
        final TraceChannel channel = channels.computeIfAbsent(id, k -> new TraceChannel());
        if (!channel.attach(emitter)) {
            throw new SparqlRequestException(HttpStatus.CONFLICT, "Trace id " + id + " is already being read");
        }
        final Runnable gone = () -> {
            channel.readerGone();
            channels.remove(id, channel);
        };
        emitter.onCompletion(gone);
        emitter.onTimeout(gone);
        emitter.onError(e -> gone.run());
    }

    void cleanUp() {
        final long now = System.nanoTime();
        channels.entrySet().removeIf(e -> {
            if (e.getValue().expired(now, MAX_WAIT.toNanos())) {
                e.getValue().abandon();
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    void stop() {
        cleaner.shutdownNow();
    }
}
