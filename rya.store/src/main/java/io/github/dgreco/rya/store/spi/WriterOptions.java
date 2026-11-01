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
package io.github.dgreco.rya.store.spi;

/**
 * How a {@link CellWriter} buffers and sends writes.
 *
 * @param maxMemoryBytes writes buffered before they are sent without waiting for a flush
 * @param maxLatencyMs longest a buffered write waits before it is sent anyway
 * @param timeoutMs how long a write may keep failing before the writer gives up
 * @param threads threads sending writes
 * @param durability how durable a write is when it counts as written
 */
public record WriterOptions(long maxMemoryBytes, long maxLatencyMs, long timeoutMs, int threads, Durability durability) {

    /** 10 MB buffered, sent within 2 minutes, 60 s timeout, 4 threads. */
    public static final WriterOptions DEFAULT = new WriterOptions(10_000_000L, 120_000L, 60_000L, 4, Durability.DEFAULT);

    public WriterOptions withThreads(final int n) {
        return new WriterOptions(maxMemoryBytes, maxLatencyMs, timeoutMs, n, durability);
    }
}
