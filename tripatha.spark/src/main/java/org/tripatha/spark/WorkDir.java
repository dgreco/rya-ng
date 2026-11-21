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
package org.tripatha.spark;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * A build's work directory, on any Hadoop file system ({@code s3a://}, {@code hdfs://},
 * {@code file://}); for an import, on one of Accumulo's volumes.
 * <ul>
 * <li>{@code job.properties}: which build the directory belongs to, its timestamp and its state
 * ({@code writing}, {@code written}, {@code importing}, {@code done});</li>
 * <li>{@code files/<table>/NNNNNN.rf}: the files, one directory per table, each imported (and so
 * moved away) by one Accumulo bulk import;</li>
 * <li>{@code manifest.properties}: each file's first and last rows, for the import's load plan.</li>
 * </ul>
 * A small file is written whole on close, which on S3 makes it appear whole or not at all.
 */
final class WorkDir {
    /** The file Accumulo's bulk import writes into the directory it imports, and moves away with the files. */
    static final String LOAD_MAPPING = "loadmap.json";

    private final Path root;
    private final FileSystem fs;

    WorkDir(final String uri, final Configuration conf) {
        try {
            final Path p = new Path(uri);
            this.fs = p.getFileSystem(conf);
            this.root = fs.makeQualified(p);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not open the work directory " + uri, e);
        }
    }

    String uri() {
        return root.toString();
    }

    String filesDir() {
        return new Path(root, "files").toString();
    }

    Path tableDir(final String table) {
        return new Path(new Path(root, "files"), table);
    }

    Properties read(final String name) {
        final Properties p = new Properties();
        final Path file = new Path(root, name);
        try {
            if (fs.exists(file)) {
                try (Reader r = new InputStreamReader(fs.open(file), StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return p;
    }

    void write(final String name, final Properties p) {
        final Path file = new Path(root, name);
        try {
            fs.mkdirs(root);
            try (Writer w = new OutputStreamWriter(fs.create(file, true), StandardCharsets.UTF_8)) {
                p.store(w, "Tripatha Spark build");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    void delete(final String name) {
        try {
            fs.delete(new Path(root, name), true);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not delete " + name + " in " + root, e);
        }
    }

    boolean exists(final String path) {
        try {
            return fs.exists(new Path(path));
        } catch (final IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /** Whether an Accumulo import of a table's directory started and didn't move its files yet. */
    boolean importStarted(final String table) {
        try {
            return fs.exists(new Path(tableDir(table), LOAD_MAPPING));
        } catch (final IOException e) {
            throw new UncheckedIOException("could not read " + tableDir(table), e);
        }
    }

    /** The files left in a table's directory. */
    List<String> files(final String table) {
        final List<String> out = new ArrayList<>();
        try {
            final Path dir = tableDir(table);
            if (fs.exists(dir)) {
                for (final FileStatus s : fs.listStatus(dir)) {
                    if (s.getPath().getName().endsWith(".rf")) {
                        out.add(s.getPath().toString());
                    }
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("could not list " + tableDir(table), e);
        }
        return out;
    }
}
