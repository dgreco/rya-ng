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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.rfile.RFile;
import org.apache.accumulo.core.client.rfile.RFileWriter;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;

/**
 * The bulk build's sorted runs: one file per table and spill, under {@code spill/<table>/} in
 * the work directory, holding one cell per {@link CellKey} in key order. They are RFiles, read
 * here without Accumulo's iterators, so nothing is filtered: a format with a block index,
 * compression and a merge of many files, used for sorting only, whatever the store.
 * <p>
 * Reading several runs merges them and combines the cells of one key: counter deltas are added
 * up, and a put keeps its newest version (what a scan of the store returns).
 */
public final class Spills {
    /** Where the runs and the finished files go: a local directory or a Hadoop file system URI. */
    private final String root;
    private final FileSystem fs;

    public Spills(final String root) {
        this.root = root;
        try {
            this.fs = new Path(root).getFileSystem(new Configuration());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A combined cell: a counter's sum, or a put's newest version. */
    public record Combined(CellKey key, boolean counter, long delta, long timestamp, byte[] value) {
    }

    public String root() {
        return root;
    }

    /** The full path of a file named relative to the work directory. */
    public String path(final String relative) {
        return new Path(new Path(root), relative).toString();
    }

    /** Writes cells already in key order, one per key, to {@code spill/<table>/<name>.rf}; returns its relative path. */
    public String write(final String table, final String name, final Iterator<Combined> cells) {
        final String relative = "spill/" + table + "/" + name + ".rf";
        try (RFileWriter writer = RFile.newWriter().to(path(relative)).withFileSystem(fs).build()) {
            while (cells.hasNext()) {
                final Combined c = cells.next();
                final CellKey k = c.key();
                writer.append(new Key(k.row(), k.family(), k.qualifier(), k.visibility(), c.counter() ? 0 : c.timestamp()),
                        new Value(c.counter() ? ByteBuffer.allocate(Long.BYTES).putLong(c.delta()).array() : c.value()));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return relative;
    }

    /**
     * The cells of {@code files} (relative paths) whose rows fall in [{@code from}, {@code to}),
     * merged and combined, in key order. Null bounds are open. Close the result's scanner with
     * {@link Reader#close()}.
     *
     * @param counters whether the files hold counter deltas (an eval table's) rather than puts
     */
    public Reader read(final List<String> files, final byte[] from, final byte[] to, final boolean counters) {
        return new Reader(files, from, to, counters);
    }

    public void delete(final List<String> files) {
        for (final String f : files) {
            try {
                fs.delete(new Path(path(f)), false);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public boolean exists(final String file) {
        try {
            return fs.exists(new Path(file));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every file under {@code spill/}, relative to the work directory. */
    public List<String> listSpills() {
        final List<String> out = new java.util.ArrayList<>();
        try {
            final Path spill = new Path(new Path(root), "spill");
            if (!fs.exists(spill)) {
                return out;
            }
            final var files = fs.listFiles(spill, true);
            final String prefix = fs.makeQualified(new Path(root)).toString() + "/";
            while (files.hasNext()) {
                final String full = files.next().getPath().toString();
                out.add(full.startsWith(prefix) ? full.substring(prefix.length()) : full);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Merged, combined cells of several runs, in key order. */
    public final class Reader implements Iterator<Combined>, AutoCloseable {
        private final Scanner scanner;
        private final Iterator<Map.Entry<Key, Value>> entries;
        private final boolean counters;
        private Map.Entry<Key, Value> lookahead;
        private Combined next;

        private Reader(final List<String> files, final byte[] from, final byte[] to, final boolean counters) {
            this.counters = counters;
            if (files.isEmpty()) {
                this.scanner = null;
                this.entries = java.util.Collections.emptyIterator();
                return;
            }
            final String[] paths = files.stream().map(Spills.this::path).toArray(String[]::new);
            this.scanner = RFile.newScanner().from(paths).withFileSystem(fs).withoutSystemIterators().build();
            scanner.setRange(new Range(from == null ? null : new Text(from), true, to == null ? null : new Text(to), false));
            this.entries = scanner.iterator();
            lookahead = entries.hasNext() ? entries.next() : null;
        }

        @Override
        public boolean hasNext() {
            if (next == null && lookahead != null) {
                next = combineNext();
            }
            return next != null;
        }

        @Override
        public Combined next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final Combined c = next;
            next = null;
            return c;
        }

        /** The cells of the key at the lookahead, combined; leaves the lookahead at the next key. */
        private Combined combineNext() {
            final Key first = lookahead.getKey();
            final CellKey key = new CellKey(first.getRowData().toArray(), first.getColumnFamilyData().toArray(),
                    first.getColumnQualifierData().toArray(), first.getColumnVisibilityData().toArray());
            long delta = 0;
            // Keys of one cell come newest first: the first put is the version a scan returns.
            final long timestamp = first.getTimestamp();
            final byte[] value = lookahead.getValue().get().clone();
            while (lookahead != null && lookahead.getKey().equals(first, org.apache.accumulo.core.data.PartialKey.ROW_COLFAM_COLQUAL_COLVIS)) {
                if (counters) {
                    delta += ByteBuffer.wrap(lookahead.getValue().get()).getLong();
                }
                lookahead = entries.hasNext() ? entries.next() : null;
            }
            return counters ? new Combined(key, true, delta, 0, null) : new Combined(key, false, 0, timestamp, value);
        }

        @Override
        public void close() {
            if (scanner != null) {
                scanner.close();
            }
        }
    }
}
