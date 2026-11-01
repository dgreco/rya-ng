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
 * Buffers writes to any number of tables, and sends them together. Not thread-safe.
 */
public interface CellWriter extends AutoCloseable {

    /** The writer of one table (the same object every time for a table). */
    TableWriter table(String table);

    /** Sends every buffered write, of every table, and waits until they are written. */
    void flush();

    /** Flushes, then releases the writer. */
    @Override
    void close();
}
