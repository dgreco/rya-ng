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
package org.tripatha.accumulo.iterators;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;

/**
 * Counts the cells of each range a scan seeks, in the tablet server, and returns one entry instead
 * of them (SCALE-STUDY.md O26): the range's last key, valued its count in decimal. A range with no
 * cell gives nothing. It sits above every other iterator of the scan, so it counts what a scan
 * would return: the cells the labels, columns, time range and row filters leave.
 *
 * <p><b>Why the last key:</b> when a batch fills, the tablet server seeks the iterators again from
 * just after the last key it returned. That key closes the range, so nothing of it is counted
 * twice; emitted at the first key, the rest of the range would be. A range that spans tablets is
 * counted in each, once per part, and the parts are added up by the client, which knows the range
 * a key falls in.
 */
public final class RangeCountingIterator implements SortedKeyValueIterator<Key, Value> {
    private SortedKeyValueIterator<Key, Value> source;
    private Key topKey;
    private Value topValue;

    @Override
    public void init(final SortedKeyValueIterator<Key, Value> source, final Map<String, String> options,
            final IteratorEnvironment environment) {
        this.source = source;
    }

    @Override
    public SortedKeyValueIterator<Key, Value> deepCopy(final IteratorEnvironment environment) {
        final RangeCountingIterator copy = new RangeCountingIterator();
        copy.source = source.deepCopy(environment);
        return copy;
    }

    /** Counts the whole range here, the source's cells in it. */
    @Override
    public void seek(final Range range, final Collection<ByteSequence> columnFamilies, final boolean inclusive)
            throws IOException {
        topKey = null;
        topValue = null;
        source.seek(range, columnFamilies, inclusive);
        long count = 0;
        // The source may reuse the key it returns: copy its fields, as Accumulo's Combiner does.
        final Key last = new Key();
        while (source.hasTop()) {
            last.set(source.getTopKey());
            count++;
            source.next();
        }
        if (count > 0) {
            topKey = new Key(last);
            topValue = new Value(Long.toString(count).getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Override
    public boolean hasTop() {
        return topKey != null;
    }

    @Override
    public void next() {
        topKey = null;
        topValue = null;
    }

    @Override
    public Key getTopKey() {
        return topKey;
    }

    @Override
    public Value getTopValue() {
        return topValue;
    }
}
