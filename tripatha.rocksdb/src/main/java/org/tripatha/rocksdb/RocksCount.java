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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.visibility.VisibilityExpression;

/**
 * Counts the cells of some ranges with native iteration (SCALE-STUDY.md O26), as {@link RocksScan}
 * would return them, without building them: each key is read once, its family and label checked
 * where it lies, and its value read only when a time range needs the timestamp. With no family,
 * label or time condition, every key in a range counts. Row and qualifier filters and single
 * columns are left to the scanning count, which applies them.
 */
final class RocksCount {
    private RocksCount() {
    }

    static boolean handles(final ScanSpec spec) {
        return spec.rowRegex() == null && spec.rowSuffix() == null && spec.qualifierRegex() == null && spec.columns().isEmpty();
    }

    static long[] count(final RocksDatabase database, final String table, final List<KeyRange> ranges, final ScanSpec spec) {
        final long[] counts = new long[ranges.size()];
        final boolean counters = database.isCounters(table);
        final List<byte[]> families = spec.families();
        final boolean labels = !spec.auths().isAllHeld();
        final boolean timed = !counters && (spec.minTimestamp() != null || spec.maxTimestamp() != null);
        final boolean everyKey = families.isEmpty() && !labels && !timed;
        final Map<ByteBuffer, Boolean> visible = new HashMap<>();
        database.retain();
        final Snapshot snapshot = database.db().getSnapshot();
        try (ReadOptions options = new ReadOptions().setSnapshot(snapshot);
                RocksIterator it = database.db().newIterator(database.family(table), options)) {
            for (int i = 0; i < counts.length; i++) {
                final byte[] lower = RocksCodec.lowerBound(ranges.get(i));
                final byte[] upper = RocksCodec.upperBound(ranges.get(i));
                if (lower.length == 0) {
                    it.seekToFirst();
                } else {
                    it.seek(lower);
                }
                long n = 0;
                for (; it.isValid(); it.next()) {
                    final byte[] key = it.key();
                    if (upper != null && Arrays.compareUnsigned(key, upper) >= 0) {
                        break;
                    }
                    if (everyKey || shown(key, it, families, labels, timed, spec, visible)) {
                        n++;
                    }
                }
                counts[i] = n;
            }
        } finally {
            database.db().releaseSnapshot(snapshot);
            snapshot.close();
            database.release();
        }
        return counts;
    }

    /**
     * {@link RocksScan}'s conditions, on the key in place, read from its end: a key is
     * {@code esc(row) 00 01 family 00 qualifier 00 visibility}, and neither the family, the
     * qualifier nor the visibility holds a {@code 00}, so the last two {@code 00}s end the family and
     * the qualifier. The escaped row has no {@code 00 01} (its {@code 00}s are {@code 00 FF}), so a
     * family is the one before the last two {@code 00}s only when {@code 00 01} precedes it.
     */
    private static boolean shown(final byte[] key, final RocksIterator it, final List<byte[]> families, final boolean labels,
            final boolean timed, final ScanSpec spec, final Map<ByteBuffer, Boolean> visible) {
        final int qualifierEnd = lastZero(key, key.length - 1);
        if (!families.isEmpty()) {
            final int familyEnd = lastZero(key, qualifierEnd - 1);
            boolean selected = false;
            for (final byte[] family : families) {
                final int start = familyEnd - family.length;
                selected |= start >= 2 && key[start - 2] == 0 && key[start - 1] == 1
                        && Arrays.equals(key, start, familyEnd, family, 0, family.length);
            }
            if (!selected) {
                return false;
            }
        }
        if (labels && qualifierEnd + 1 < key.length) {
            final byte[] visibility = Arrays.copyOfRange(key, qualifierEnd + 1, key.length);
            if (!visible.computeIfAbsent(ByteBuffer.wrap(visibility),
                    v -> VisibilityExpression.parse(visibility).isVisibleTo(spec.auths().labels()))) {
                return false;
            }
        }
        if (timed) {
            final long ts = RocksCodec.timestamp(it.value());
            return (spec.minTimestamp() == null || ts >= spec.minTimestamp()) && (spec.maxTimestamp() == null || ts <= spec.maxTimestamp());
        }
        return true;
    }

    /** The last {@code 00} at or before {@code from}. */
    private static int lastZero(final byte[] key, final int from) {
        for (int i = from; i >= 0; i--) {
            if (key[i] == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("Not a Tripatha key: " + key.length + " bytes");
    }
}
