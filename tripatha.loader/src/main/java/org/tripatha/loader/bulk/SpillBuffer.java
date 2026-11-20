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
package org.tripatha.loader.bulk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tripatha.store.spi.RowMutation;

/**
 * One writer thread's captured cells, combined per {@link CellKey} in memory (counter deltas
 * added up, a put's newest version kept) until they are spilled to sorted runs. Not thread-safe:
 * a {@link CapturingStore} per thread feeds one.
 */
public final class SpillBuffer implements CapturingStore.Sink {
    /** Rough bytes of a combined cell beyond its key and value: the map entry, the objects, the arrays' headers. */
    private static final long OVERHEAD = 120;

    private static final class Put {
        long timestamp;
        byte[] value;
    }

    private final long buildTime;
    private final Map<String, Map<CellKey, Object>> tables = new LinkedHashMap<>();
    private long bytes;

    /** @param buildTime the timestamp of a put that leaves its timestamp to the server */
    public SpillBuffer(final long buildTime) {
        this.buildTime = buildTime;
    }

    @Override
    public void accept(final String table, final RowMutation mutation) {
        final Map<CellKey, Object> cells = tables.computeIfAbsent(table, t -> new HashMap<>());
        for (final RowMutation.Update u : mutation.updates()) {
            final CellKey key = new CellKey(mutation.row(), u.family(), u.qualifier(), u.visibility());
            switch (u.op()) {
                case COUNTER -> {
                    final Object held = cells.get(key);
                    if (held == null) {
                        cells.put(key, new long[] {u.delta()});
                        bytes += key.bytes() + OVERHEAD;
                    } else if (held instanceof long[] sum) {
                        sum[0] += u.delta();
                    } else {
                        throw new IllegalStateException(table + ": a counter delta and a put for one cell, " + key);
                    }
                }
                case PUT -> {
                    final long ts = u.timestamp() == RowMutation.SERVER_TIME ? buildTime : u.timestamp();
                    final Object held = cells.get(key);
                    if (held == null) {
                        final Put p = new Put();
                        p.timestamp = ts;
                        p.value = u.value();
                        cells.put(key, p);
                        bytes += key.bytes() + u.value().length + OVERHEAD;
                    } else if (held instanceof Put p) {
                        if (ts >= p.timestamp) {
                            bytes += u.value().length - p.value.length;
                            p.timestamp = ts;
                            p.value = u.value();
                        }
                    } else {
                        throw new IllegalStateException(table + ": a counter delta and a put for one cell, " + key);
                    }
                }
                case DELETE -> throw new IllegalStateException(table + ": the bulk build only adds, but got a delete of " + key);
            }
        }
    }

    /** The bytes the buffer holds, roughly. */
    public long bytes() {
        return bytes;
    }

    public boolean isEmpty() {
        return tables.values().stream().allMatch(Map::isEmpty);
    }

    /**
     * Writes each table's cells, sorted, to a run named {@code name} under its table, and empties
     * the buffer.
     *
     * @return the runs written, table by table (relative paths)
     */
    public Map<String, String> spill(final Spills spills, final String name) {
        final Map<String, String> written = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<CellKey, Object>> e : tables.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            final List<Map.Entry<CellKey, Object>> sorted = new ArrayList<>(e.getValue().entrySet());
            sorted.sort(Map.Entry.comparingByKey());
            final var cells = sorted.stream().map(c -> c.getValue() instanceof long[] sum
                    ? new Spills.Combined(c.getKey(), true, sum[0], 0, null)
                    : new Spills.Combined(c.getKey(), false, 0, ((Put) c.getValue()).timestamp, ((Put) c.getValue()).value))
                    .iterator();
            written.put(e.getKey(), spills.write(e.getKey(), name, cells));
        }
        tables.clear();
        bytes = 0;
        return written;
    }
}
