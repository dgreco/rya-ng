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
package org.tripatha.hbase.coprocessor;

import java.io.IOException;

/**
 * One term's postings in a shard row, as {@link Leapfrog} reads them: its visible cells in order,
 * each known by its subject. Reading spends the page's {@link Leapfrog.Budget}: a list whose budget
 * ran out is paused, exactly after the last cell it read, and continues from there.
 */
interface Postings extends AutoCloseable {
    /**
     * The subject of the current cell; null when the list has ended, or is {@link #paused()}
     * before finding one.
     */
    byte[] head() throws IOException;

    /** Whether {@link #head()} is null because the budget ran out, not because the list ended. */
    boolean paused();

    /**
     * When paused, the lowest subject the list may still hold at or after its bound: it has read
     * every cell before that subject's (or, when nothing was read, its bound).
     */
    byte[] frontier();

    /** Moves to the first cell whose subject is at or after the bound. */
    void seek(byte[] subject) throws IOException;

    /** Moves past the current cell. */
    void next() throws IOException;

    /** Continues a paused list from the cell it stopped at, on the budget's new allowance. */
    void resume() throws IOException;

    @Override
    void close() throws IOException;
}
