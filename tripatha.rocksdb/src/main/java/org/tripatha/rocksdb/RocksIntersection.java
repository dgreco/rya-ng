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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.visibility.VisibilityExpression;

/**
 * The entity index's intersection with native seeks (SCALE-STUDY.md O23): in each row, the
 * subjects (each cell's qualifier up to its first {@code 01}) found under every family, as
 * {@link org.tripatha.store.spi.Store#intersect} describes. One RocksDB iterator per family,
 * leapfrogged: each is sought to the largest subject the others are at, until they agree, so a
 * long list costs about as many seeks as the shortest has cells, not its length. Labels and the
 * time range are applied to each cell, as {@link RocksScan} applies them.
 *
 * <p>The rows are intersected one after the other, from one snapshot. Closes itself when it has
 * returned its last match; one abandoned earlier must be closed.
 */
final class RocksIntersection implements CellScanner {
    private static final byte SEP = 1;
    private static final byte[] EMPTY = new byte[0];

    private final RocksDatabase database;
    private final ScanSpec spec;
    private final List<byte[]> rows;
    private final byte[][] families;
    private final Snapshot snapshot;
    private final ReadOptions options;
    private final RocksIterator[] lists;
    /** Whether each distinct visibility seen so far is visible. */
    private final Map<ByteBuffer, Boolean> visible = new HashMap<>();
    /** The key prefix of each family in the current row, or null between rows. */
    private byte[][] prefixes;
    private int row = -1;
    private Cell next;
    private boolean closed;

    RocksIntersection(final RocksDatabase database, final String table, final List<byte[]> rows, final List<byte[]> families,
            final ScanSpec spec) {
        this.database = database;
        this.spec = spec;
        this.rows = List.copyOf(rows);
        this.families = families.toArray(new byte[0][]);
        database.retain();
        this.snapshot = database.db().getSnapshot();
        this.options = new ReadOptions().setSnapshot(snapshot);
        this.lists = new RocksIterator[this.families.length];
        for (int i = 0; i < lists.length; i++) {
            lists[i] = database.db().newIterator(database.family(table), options);
        }
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
            if (prefixes == null && !openNextRow()) {
                return null;
            }
            final byte[] match = intersect();
            if (match == null) {
                prefixes = null;
                continue;
            }
            long count = 1;
            for (int i = 0; i < lists.length; i++) {
                long n = 0;
                while (Arrays.equals(head(i), match)) {
                    n++;
                    lists[i].next();
                    skipHidden(i);
                }
                count = Math.multiplyExact(count, n);
            }
            return new Cell(rows.get(row), EMPTY, match, EMPTY, 0, Long.toString(count).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Opens the next row whose every family has a visible cell; false when none is left. */
    private boolean openNextRow() {
        while (++row < rows.size()) {
            prefixes = new byte[families.length][];
            boolean all = true;
            for (int i = 0; i < families.length && all; i++) {
                prefixes[i] = RocksCodec.familyPrefix(rows.get(row), families[i]);
                lists[i].seek(prefixes[i]);
                skipHidden(i);
                all = head(i) != null;
            }
            if (all) {
                return true;
            }
        }
        prefixes = null;
        return false;
    }

    /** Advances the lists to the next subject they all have; null when one has run out. */
    private byte[] intersect() {
        byte[] target = null;
        for (int i = 0; i < lists.length; i++) {
            final byte[] head = head(i);
            if (head == null) {
                return null;
            }
            if (target == null || Arrays.compareUnsigned(head, target) > 0) {
                target = head;
            }
        }
        boolean agreed;
        do {
            agreed = true;
            for (int i = 0; i < lists.length; i++) {
                byte[] head = head(i);
                if (head != null && Arrays.compareUnsigned(head, target) < 0) {
                    lists[i].seek(concat(prefixes[i], target));
                    skipHidden(i);
                    head = head(i);
                }
                if (head == null) {
                    return null;
                }
                if (!Arrays.equals(head, target)) {
                    target = head;
                    agreed = false;
                }
            }
        } while (!agreed);
        return target;
    }

    /** The subject of the cell a list is at, or null when it has left its row and family. */
    private byte[] head(final int list) {
        final RocksIterator it = lists[list];
        if (!it.isValid()) {
            return null;
        }
        final byte[] key = it.key();
        final byte[] prefix = prefixes[list];
        if (key.length < prefix.length || !Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length)) {
            return null;
        }
        int end = prefix.length;
        while (end < key.length && key[end] != SEP && key[end] != 0) {
            end++;
        }
        return Arrays.copyOfRange(key, prefix.length, end);
    }

    /** Moves a list past the cells of its row and family that the labels or the time range hide. */
    private void skipHidden(final int list) {
        final RocksIterator it = lists[list];
        while (head(list) != null && !shown(it)) {
            it.next();
        }
    }

    private boolean shown(final RocksIterator it) {
        if (spec.minTimestamp() != null || spec.maxTimestamp() != null) {
            final long ts = RocksCodec.timestamp(it.value());
            if ((spec.minTimestamp() != null && ts < spec.minTimestamp()) || (spec.maxTimestamp() != null && ts > spec.maxTimestamp())) {
                return false;
            }
        }
        if (spec.auths().isAllHeld()) {
            return true;
        }
        final byte[] key = it.key();
        int qualifierEnd = key.length - 1;
        while (qualifierEnd >= 0 && key[qualifierEnd] != 0) {
            qualifierEnd--;
        }
        final byte[] visibility = Arrays.copyOfRange(key, qualifierEnd + 1, key.length);
        return visibility.length == 0 || visible.computeIfAbsent(ByteBuffer.wrap(visibility),
                v -> VisibilityExpression.parse(visibility).isVisibleTo(spec.auths().labels()));
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        next = null;
        for (final RocksIterator it : lists) {
            it.close();
        }
        options.close();
        database.db().releaseSnapshot(snapshot);
        snapshot.close();
        database.release();
    }
}
