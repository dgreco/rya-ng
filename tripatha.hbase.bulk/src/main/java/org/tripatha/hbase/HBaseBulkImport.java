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
package org.tripatha.hbase;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellBuilderType;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.ExtendedCellBuilderFactory;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFileContextBuilder;
import org.apache.hadoop.hbase.security.visibility.VisibilityLabelOrdinalProvider;
import org.apache.hadoop.hbase.security.visibility.VisibilityUtils;
import org.apache.hadoop.hbase.tool.BulkLoadHFiles;
import org.apache.hadoop.hbase.util.Bytes;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.spi.BulkImport;

/**
 * The bulk build's files on HBase (SCALE-STUDY.md O10, 4.17): HFiles in Tripatha's cell layout
 * ({@link HBaseCodec}), written with HBase's own writer and the column family's settings, then
 * loaded with {@link BulkLoadHFiles} as the store's user.
 * <ul>
 * <li><b>Labels:</b> a cell's visibility is a tag of label ordinals. HBase shows {@code hbase:labels}
 * to superusers only, and the loader's account isn't one, so the ordinals come from the label
 * canaries ({@link HBaseLabels#ordinals}): a label the account doesn't hold, or that wasn't
 * registered through Tripatha's {@code labels} command, stops the build.</li>
 * <li><b>Counters:</b> in coprocessor mode, plain cells stamped with the build's own timestamp,
 * which {@code CounterObserver} adds up with the table's on reads; two cells of one key and
 * timestamp would be one. In increment mode a counter is one value, which an imported cell would
 * replace or hide, so counters are refused ({@link #refusesCounters}).</li>
 * <li><b>Rows and columns:</b> through {@link HBaseCodec}, as the write path stores them: the empty
 * row as {@code 00}, rows over its limit and column names holding {@code 00} refused, guards an
 * import would otherwise skip (R11).</li>
 * </ul>
 */
final class HBaseBulkImport implements BulkImport {
    private final HBaseStore store;
    private final Map<String, Integer> ordinals = new ConcurrentHashMap<>();
    private final VisibilityLabelOrdinalProvider provider = new VisibilityLabelOrdinalProvider() {
        @Override
        public int getLabelOrdinal(final String label) {
            return ordinals.computeIfAbsent(label, l -> HBaseLabels.ordinals(store, List.of(l)).get(l));
        }

        @Override
        public String getLabel(final int ordinal) {
            return ordinals.entrySet().stream().filter(e -> e.getValue() == ordinal).map(Map.Entry::getKey).findFirst().orElse(null);
        }
    };

    HBaseBulkImport(final HBaseStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> refusesCounters(final String table) {
        if (store.counterMode(table) == HBaseStore.CounterMode.INCREMENT) {
            return Optional.of("the counter table " + table + " is in HBase's increment mode, where a counter is one value that "
                    + "an imported cell would replace instead of adding to: build in coprocessor mode (the default), or use load");
        }
        return Optional.empty();
    }

    @Override
    public BulkFile newFile(final String table, final String dir, final String name, final long counterTimestamp) {
        final Configuration conf = store.connection().getConfiguration();
        final Path path = new Path(new Path(dir, Bytes.toString(HBaseCodec.FAMILY)), name);
        try (Admin admin = store.connection().getAdmin()) {
            final ColumnFamilyDescriptor family = admin.getDescriptor(TableName.valueOf(table)).getColumnFamily(HBaseCodec.FAMILY);
            final HFileContext context = new HFileContextBuilder().withIncludesTags(true).withCompression(family.getCompressionType())
                    .withBlockSize(family.getBlocksize()).withDataBlockEncoding(family.getDataBlockEncoding())
                    .withColumnFamily(HBaseCodec.FAMILY).withTableName(Bytes.toBytes(table)).build();
            // As the store's user, who owns the files then (on HDFS) and loads them.
            final FileSystem fs = asUser(() -> path.getFileSystem(conf));
            final HFile.Writer writer = asUser(() -> {
                fs.mkdirs(path.getParent());
                fs.delete(path, false);
                return HFile.getWriterFactory(conf, new CacheConfig(conf)).withPath(fs, path).withFileContext(context).create();
            });
            return new File(fs, path, writer, counterTimestamp);
        } catch (final IOException e) {
            throw new RyaDAOException("Could not open a bulk file for " + table + " at " + path, e);
        }
    }

    @Override
    public void importFiles(final String table, final List<Written> files) {
        if (files.isEmpty()) {
            return;
        }
        final Map<byte[], List<Path>> byFamily = new TreeMap<>(Bytes.BYTES_COMPARATOR);
        final List<Path> paths = new ArrayList<>();
        for (final Written w : files) {
            paths.add(new Path(w.path()));
        }
        byFamily.put(HBaseCodec.FAMILY, paths);
        try {
            asUser(() -> {
                // HBase moves the files into the table: their directory must let it.
                final FileSystem fs = paths.get(0).getFileSystem(store.connection().getConfiguration());
                final org.apache.hadoop.fs.permission.FsPermission open = new org.apache.hadoop.fs.permission.FsPermission((short) 0777);
                fs.setPermission(paths.get(0).getParent(), open);
                for (final Path p : paths) {
                    fs.setPermission(p, open);
                }
                BulkLoadHFiles.create(store.connection().getConfiguration()).bulkLoad(TableName.valueOf(table), byFamily);
                return null;
            });
        } catch (final IOException e) {
            throw new RyaDAOException("Could not bulk-load " + files.size() + " files into " + table, e);
        }
    }

    /** Runs as the store's user (the process's own when it has none). */
    private <T> T asUser(final PrivilegedExceptionAction<T> action) throws IOException {
        try {
            return store.hbaseUser() == null ? action.run() : store.hbaseUser().runAs(action);
        } catch (final IOException | RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    private final class File implements BulkFile {
        private final FileSystem fs;
        private final Path path;
        private final HFile.Writer writer;
        private final long counterTimestamp;
        private byte[] first;
        private byte[] last;
        private long cells;
        private boolean closed;
        private boolean done;

        File(final FileSystem fs, final Path path, final HFile.Writer writer, final long counterTimestamp) {
            this.fs = fs;
            this.path = path;
            this.writer = writer;
            this.counterTimestamp = counterTimestamp;
        }

        @Override
        public void put(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility, final long timestamp,
                final byte[] value) {
            append(row, family, qualifier, visibility, timestamp, value);
        }

        @Override
        public void counter(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility,
                final long delta) {
            append(row, family, qualifier, visibility, counterTimestamp, Bytes.toBytes(delta));
        }

        private void append(final byte[] row, final byte[] family, final byte[] qualifier, final byte[] visibility, final long ts,
                final byte[] value) {
            final List<Tag> tags;
            try {
                tags = visibility.length == 0 ? List.of()
                        : VisibilityUtils.createVisibilityExpTags(HBaseCodec.visibility(visibility).getExpression(), true, false,
                                null, provider);
            } catch (final IOException e) {
                throw new RyaDAOException("Could not write the visibility " + Bytes.toString(visibility) + " as HBase tags", e);
            }
            final ExtendedCell cell = ExtendedCellBuilderFactory.create(CellBuilderType.DEEP_COPY).setRow(HBaseCodec.row(row))
                    .setFamily(HBaseCodec.FAMILY).setQualifier(HBaseCodec.qualifier(family, qualifier, visibility)).setTimestamp(ts)
                    .setType(Cell.Type.Put).setValue(value).setTags(tags).build();
            try {
                writer.append(cell);
            } catch (final IOException | IllegalArgumentException e) {
                throw new RyaDAOException("Could not write to " + path + " (cells must be in order, one per key)", e);
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
