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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.tripatha.store.spi.BulkImport;
import org.tripatha.store.spi.Capabilities;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.StoreAccess;
import org.tripatha.store.spi.TableAdmin;
import org.tripatha.store.spi.TableWriter;
import org.tripatha.store.spi.WriterOptions;

/**
 * A store whose writes to some tables go to a {@link Sink} instead: a DAO over it writes the
 * bulk build's cells with its own code, so they are exactly what {@code load} would write
 * (statements, indexes, counters, datatype markers). Everything else reaches the real store:
 * reads, table operations, and writes to the other tables (the metadata in {@code ns} and the
 * graph markers, which are few).
 */
public final class CapturingStore implements Store {

    /** Where the captured writes go. Called from the thread that writes. */
    public interface Sink {
        void accept(String table, RowMutation mutation);
    }

    private final Store store;
    private final Set<String> captured;
    private final Sink sink;

    public CapturingStore(final Store store, final Set<String> captured, final Sink sink) {
        this.store = store;
        this.captured = Set.copyOf(captured);
        this.sink = sink;
    }

    @Override
    public CellWriter newWriter(final WriterOptions options) {
        final CellWriter real = store.newWriter(options);
        return new CellWriter() {
            @Override
            public TableWriter table(final String table) {
                if (captured.contains(table)) {
                    return mutation -> sink.accept(table, mutation);
                }
                return real.table(table);
            }

            @Override
            public void flush() {
                real.flush();
            }

            @Override
            public void close() {
                real.close();
            }
        };
    }

    @Override
    public String backend() {
        return store.backend();
    }

    @Override
    public Capabilities capabilities() {
        return store.capabilities();
    }

    @Override
    public TableAdmin tables() {
        return store.tables();
    }

    @Override
    public StoreAccess access() {
        return store.access();
    }

    @Override
    public Optional<BulkImport> bulk() {
        return store.bulk();
    }

    @Override
    public CellScanner scan(final String table, final KeyRange range, final ScanSpec spec) {
        return store.scan(table, range, spec);
    }

    @Override
    public CellScanner batchScan(final String table, final Collection<KeyRange> ranges, final ScanSpec spec) {
        return store.batchScan(table, ranges, spec);
    }

    @Override
    public void deleteVisible(final String table, final Collection<KeyRange> ranges, final ScanSpec spec,
            final WriterOptions options) {
        store.deleteVisible(table, ranges, spec, options);
    }

    @Override
    public long counterValue(final byte[] value) {
        return store.counterValue(value);
    }

    @Override
    public boolean canIntersect(final String table) {
        return store.canIntersect(table);
    }

    @Override
    public CellScanner intersect(final String table, final List<byte[]> rows, final List<byte[]> families, final ScanSpec spec) {
        return store.intersect(table, rows, families, spec);
    }

    /** The real store stays open: its owner closes it. */
    @Override
    public void close() {
    }
}
