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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Changes to the cells of one row: puts, deletes and counter deltas. Absent parts (family,
 * qualifier, visibility, value) are empty, never null; an empty visibility means unlabelled.
 */
public final class RowMutation {
    /** A timestamp the server picks (its clock) instead of the writer. */
    public static final long SERVER_TIME = Long.MIN_VALUE;

    private static final byte[] EMPTY = new byte[0];

    /** What an update does. */
    public enum Op {
        /** Writes a cell. */
        PUT,
        /** Deletes the cell with this key: versions at or before the timestamp. */
        DELETE,
        /** Adds a delta to a counter cell (see {@link #addToCounter}). */
        COUNTER
    }

    /** One change. {@code delta} is used by counters only, {@code value} by puts only. */
    public record Update(Op op, byte[] family, byte[] qualifier, byte[] visibility, long timestamp, byte[] value,
            long delta) {
    }

    private final byte[] row;
    private final List<Update> updates = new ArrayList<>(2);

    public RowMutation(final byte[] row) {
        this.row = row;
    }

    public byte[] row() {
        return row;
    }

    public List<Update> updates() {
        return Collections.unmodifiableList(updates);
    }

    /** Writes a cell with this timestamp. */
    public RowMutation put(final byte[] family, final byte[] qualifier, final byte[] visibility, final long timestamp,
            final byte[] value) {
        updates.add(new Update(Op.PUT, nz(family), nz(qualifier), nz(visibility), timestamp, nz(value), 0));
        return this;
    }

    /** Writes a cell stamped by the server. */
    public RowMutation put(final byte[] family, final byte[] qualifier, final byte[] visibility, final byte[] value) {
        return put(family, qualifier, visibility, SERVER_TIME, value);
    }

    /** Deletes the versions of a cell (this exact label) at or before a timestamp. */
    public RowMutation delete(final byte[] family, final byte[] qualifier, final byte[] visibility, final long timestamp) {
        updates.add(new Update(Op.DELETE, nz(family), nz(qualifier), nz(visibility), timestamp, EMPTY, 0));
        return this;
    }

    /** Deletes every version of a cell (this exact label) written until now. */
    public RowMutation delete(final byte[] family, final byte[] qualifier, final byte[] visibility) {
        return delete(family, qualifier, visibility, SERVER_TIME);
    }

    /**
     * Adds a delta to a counter cell. The backend adds up the deltas of a cell (on the server;
     * see {@link Capabilities#serverCombine()}), and {@link Store#counterValue} decodes the sum.
     * Writing a delta is a blind write: it doesn't read the counter.
     */
    public RowMutation addToCounter(final byte[] family, final byte[] qualifier, final byte[] visibility, final long delta) {
        updates.add(new Update(Op.COUNTER, nz(family), nz(qualifier), nz(visibility), SERVER_TIME, EMPTY, delta));
        return this;
    }

    private static byte[] nz(final byte[] b) {
        return b == null ? EMPTY : b;
    }
}
