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
package org.tripatha.rocksdb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;

import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.visibility.VisibilityExpression;

/**
 * Reads the cells of some ranges, one range after the other, each in key order, from one snapshot
 * of the database. This is where labels are enforced: a cell whose visibility the spec's labels
 * don't satisfy is never returned.
 *
 * <p>Closes itself when it has returned its last cell; a scanner abandoned earlier must be closed.
 */
final class RocksScan implements CellScanner {
    private final RocksDatabase database;
    private final boolean counters;
    private final ScanSpec spec;
    private final List<byte[][]> bounds;
    private final Pattern rowRegex;
    private final Pattern qualifierRegex;
    /** Whether each distinct visibility seen so far is visible. */
    private final Map<ByteBuffer, Boolean> visible = new HashMap<>();
    private final Snapshot snapshot;
    private final ReadOptions options;
    private final RocksIterator iterator;
    private int range = -1;
    private byte[] upper;
    private Cell next;
    private boolean closed;

    RocksScan(final RocksDatabase database, final String table, final List<KeyRange> ranges, final ScanSpec spec) {
        this.database = database;
        this.counters = database.isCounters(table);
        this.spec = spec;
        this.bounds = new ArrayList<>(ranges.size());
        for (final KeyRange r : ranges) {
            bounds.add(new byte[][] {RocksCodec.lowerBound(r), RocksCodec.upperBound(r)});
        }
        this.rowRegex = spec.rowRegex() == null ? null : Pattern.compile(spec.rowRegex());
        this.qualifierRegex = spec.qualifierRegex() == null ? null : Pattern.compile(spec.qualifierRegex());
        database.retain();
        this.snapshot = database.db().getSnapshot();
        this.options = new ReadOptions().setSnapshot(snapshot);
        this.iterator = database.db().newIterator(database.family(table), options);
    }

    @Override
    public boolean hasNext() {
        if (next == null && !closed) {
            next = advance();
            if (next == null) {
                close();
            }
        }
        return next != null;
    }

    @Override
    public Cell next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        final Cell cell = next;
        next = null;
        return cell;
    }

    private Cell advance() {
        while (true) {
            if (range >= 0 && iterator.isValid()) {
                final byte[] key = iterator.key();
                if (upper == null || Arrays.compareUnsigned(key, upper) < 0) {
                    final Cell cell = RocksCodec.cell(key, iterator.value(), counters);
                    iterator.next();
                    if (accepts(cell)) {
                        return cell;
                    }
                    continue;
                }
            }
            if (++range >= bounds.size()) {
                return null;
            }
            final byte[] lower = bounds.get(range)[0];
            upper = bounds.get(range)[1];
            if (lower.length == 0) {
                iterator.seekToFirst();
            } else {
                iterator.seek(lower);
            }
        }
    }

    private boolean accepts(final Cell cell) {
        if (!spec.families().isEmpty() || !spec.columns().isEmpty()) {
            boolean selected = false;
            for (final byte[] family : spec.families()) {
                selected |= Arrays.equals(family, cell.family());
            }
            for (final ScanSpec.Column column : spec.columns()) {
                selected |= Arrays.equals(column.family(), cell.family()) && Arrays.equals(column.qualifier(), cell.qualifier());
            }
            if (!selected) {
                return false;
            }
        }
        if (!spec.auths().isAllHeld() && cell.visibility().length > 0 && !visible.computeIfAbsent(
                ByteBuffer.wrap(cell.visibility()),
                v -> VisibilityExpression.parse(cell.visibility()).isVisibleTo(spec.auths().labels()))) {
            return false;
        }
        if ((spec.minTimestamp() != null && cell.timestamp() < spec.minTimestamp())
                || (spec.maxTimestamp() != null && cell.timestamp() > spec.maxTimestamp())) {
            return false;
        }
        if (rowRegex != null && !rowRegex.matcher(new String(cell.row(), StandardCharsets.UTF_8)).matches()) {
            return false;
        }
        return qualifierRegex == null || qualifierRegex.matcher(new String(cell.qualifier(), StandardCharsets.UTF_8)).matches();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        next = null;
        iterator.close();
        options.close();
        database.db().releaseSnapshot(snapshot);
        snapshot.close();
        database.release();
    }
}
