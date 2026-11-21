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
package org.tripatha.accumulo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.rfile.RFile;
import org.apache.accumulo.core.client.rfile.RFileWriter;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.LoadPlan;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.BulkImport;

/**
 * RFiles written with the public writer and the table's own configuration (its compression and
 * block sizes), imported with a load plan that names each file's rows, so Accumulo doesn't read
 * them to place them (SCALE-STUDY.md 4.17). Counter deltas are cells holding the delta as decimal
 * text, which the counter table's combiner adds to what the table holds, as it adds online deltas.
 * The import moves the files into the table, so a file that is gone was imported.
 */
final class AccumuloBulkImport implements BulkImport {
    /**
     * The key size Accumulo's default constraint allows ({@code DefaultKeySizeConstraint}, 1 MB):
     * an imported file skips the table's constraints (R11), so the writer checks it.
     */
    static final int MAX_KEY_BYTES = 1 << 20;

    private final AccumuloClient client;

    AccumuloBulkImport(final AccumuloClient client) {
        this.client = client;
    }

    @Override
    public boolean splitPointEndsTablet() {
        return true;
    }

    @Override
    public BulkFile newFile(final String table, final String dir, final String name, final long counterTimestamp) {
        try {
            return open(client.tableOperations().getConfiguration(table), new Configuration(), dir, name, counterTimestamp);
        } catch (final AccumuloException | TableNotFoundException e) {
            throw new RyaDAOException("Could not read the configuration of " + table, e);
        }
    }

    /**
     * An RFile for a table, written with the table's configuration as given: no connection to
     * Accumulo, so a Spark executor can write one (tripatha.spark).
     *
     * @param conf the Hadoop configuration of the file system ({@code s3a://}, {@code hdfs://}, {@code file://})
     */
    static BulkFile open(final Map<String, String> tableProperties, final Configuration conf, final String dir, final String name,
            final long counterTimestamp) {
        final Path path = new Path(dir, name + ".rf");
        try {
            final FileSystem fs = path.getFileSystem(conf);
            fs.mkdirs(path.getParent());
            fs.delete(path, false);
            final RFileWriter writer = RFile.newWriter().to(path.toString()).withFileSystem(fs).withTableProperties(tableProperties)
                    .build();
            return new File(fs, path, writer, counterTimestamp);
        } catch (final IOException e) {
            throw new RyaDAOException("Could not open a bulk file at " + path, e);
        }
    }

    @Override
    public void importFiles(final String table, final List<Written> files) {
        if (files.isEmpty()) {
            return;
        }
        final String dir = new Path(files.get(0).path()).getParent().toString();
        final LoadPlan.Builder plan = LoadPlan.builder();
        for (final Written w : files) {
            final Path path = new Path(w.path());
            if (!path.getParent().toString().equals(dir)) {
                throw new IllegalArgumentException("the files of one import must share a directory: " + w.path());
            }
            plan.loadFileTo(path.getName(), LoadPlan.RangeType.FILE, w.firstRow(), w.lastRow());
        }
        try {
            client.tableOperations().importDirectory(dir).to(table).plan(plan.build()).load();
        } catch (final AccumuloException | AccumuloSecurityException | TableNotFoundException | IOException e) {
            throw new RyaDAOException("Could not import " + files.size() + " files into " + table + " from " + dir, e);
        }
    }

    private static final class File implements BulkFile {
        private final FileSystem fs;
        private final Path path;
        private final RFileWriter writer;
        private final long counterTimestamp;
        private byte[] first;
        private byte[] last;
        private long cells;
        private Boolean counters;
        private boolean closed;
        private boolean done;

        File(final FileSystem fs, final Path path, final RFileWriter writer, final long counterTimestamp) {
            this.fs = fs;
            this.path = path;
            this.writer = writer;
            this.counterTimestamp = counterTimestamp;
        }

        @Override
        public void put(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility, final long timestamp,
                final byte[] value) {
            kind(false);
            append(row, new Key(row, family, qualifier, visibility, timestamp), new Value(value));
        }

        @Override
        public void counter(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility,
                final long delta) {
            kind(true);
            append(row, new Key(row, family, qualifier, visibility, counterTimestamp),
                    new Value(Long.toString(delta).getBytes(StandardCharsets.UTF_8)));
        }

        private void kind(final boolean counter) {
            if (counters == null) {
                counters = counter;
            } else if (counters != counter) {
                throw new IllegalArgumentException("a bulk file holds puts or counter deltas, not both");
            }
        }

        private void append(final byte[] row, final Key key, final Value value) {
            if (key.getSize() > MAX_KEY_BYTES) {
                throw new RyaDAOException("A key of " + key.getSize() + " bytes: Accumulo's key size constraint allows "
                        + MAX_KEY_BYTES);
            }
            try {
                writer.append(key, value);
            } catch (final IOException | IllegalArgumentException e) {
                throw new RyaDAOException("Could not write to " + path + " (keys must be in order, one per key)", e);
            }
            if (first == null) {
                first = row;
            }
            last = row;
            cells++;
        }

        @Override
        public Written finish() {
            if (cells == 0) {
                close();
                return null;
            }
            closeWriter();
            done = true;
            return new Written(path.toString(), first, last, cells);
        }

        @Override
        public void close() {
            closeWriter();
            if (!done) {
                try {
                    fs.delete(path, false);
                } catch (final IOException e) {
                    // left for the build's clean-up
                }
            }
        }

        private void closeWriter() {
            if (!closed) {
                closed = true;
                try {
                    writer.close();
                } catch (final IOException e) {
                    throw new RyaDAOException("Could not finish " + path, e);
                }
            }
        }
    }
}
