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

import java.io.PrintStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.VersionInfo;
import org.apache.spark.api.java.JavaSparkContext;

import org.tripatha.accumulo.AccumuloRFiles;
import org.tripatha.accumulo.AccumuloStore;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.BulkImport;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.TableKind;

/**
 * The cluster check to run before a build: every executor writes a small RFile under the work
 * directory with Tripatha's writer, the driver imports them into a scratch table and reads them
 * back, then drops the table. It shows the executors' Java and Hadoop versions and the work
 * directory's file system, and fails where a build would: executors that can't run the code,
 * write the files or reach the file system, or a directory Accumulo can't import from.
 */
final class Smoke {
    static final int CELLS = 1_000;

    /** What one executor reports. A plain class, for Kryo. */
    static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;
        String host;
        String java;
        String hadoop;
        String fileSystem;
        Tasks.FileWritten file;

        Result() {
        }
    }

    private final JavaSparkContext sc;
    private final AccumuloStore store;
    private final WorkDir work;
    private final String table;
    private final PrintStream out;

    Smoke(final JavaSparkContext sc, final AccumuloStore store, final WorkDir work, final String prefix, final PrintStream out) {
        this.sc = sc;
        this.store = store;
        this.work = work;
        this.table = prefix + "spark_smoke";
        this.out = out;
    }

    void run() {
        if (store.tables().exists(table)) {
            store.tables().delete(table);
        }
        store.tables().create(table, TableKind.METADATA);
        try {
            final HashMap<String, String> properties = new HashMap<>(AccumuloRFiles.tableProperties(store.client(), table));
            final HashMap<String, String> hadoop = JobSpec.entries(sc.hadoopConfiguration());
            final String dir = work.uri() + "/smoke/" + table;
            work.delete("smoke");
            final int parts = Math.max(2, sc.defaultParallelism());
            final long time = System.currentTimeMillis();
            final String name = table;
            final List<Result> results = new ArrayList<>(sc.parallelize(IntStream.range(0, parts).boxed().toList(), parts)
                    .mapPartitionsWithIndex((i, it) -> {
                        final JobSpec spec = new JobSpec();
                        spec.hadoop = hadoop;
                        final org.apache.hadoop.conf.Configuration conf = spec.hadoopConf();
                        final BulkImport.BulkFile f = AccumuloRFiles.open(properties, conf, dir, String.format(Locale.ROOT, "%06d", i),
                                time);
                        for (int c = 0; c < CELLS; c++) {
                            f.put(String.format(Locale.ROOT, "smoke-%06d-%06d", i, c).getBytes(StandardCharsets.UTF_8), new byte[0],
                                    new byte[0], new byte[0], time, Integer.toString(c).getBytes(StandardCharsets.UTF_8));
                        }
                        final Result r = new Result();
                        r.host = InetAddress.getLocalHost().getHostName();
                        r.java = System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")";
                        r.hadoop = VersionInfo.getVersion();
                        r.fileSystem = new Path(dir).getFileSystem(conf).getClass().getName();
                        r.file = new Tasks.FileWritten(name, f.finish());
                        final List<Result> one = new ArrayList<>(1);
                        one.add(r);
                        return one.iterator();
                    }, true).collect());
            final List<BulkImport.Written> files = new ArrayList<>();
            for (final Result r : results) {
                out.printf(Locale.ROOT, "Executor %s: Java %s, Hadoop %s, %s: wrote %s%n", r.host, r.java, r.hadoop, r.fileSystem,
                        r.file.path);
                files.add(r.file.written());
            }
            store.bulk().orElseThrow().importFiles(table, files);
            long cells = 0;
            try (CellScanner scan = store.scan(table, KeyRange.all(), ScanSpec.of(Auths.ALL_HELD))) {
                while (scan.hasNext()) {
                    scan.next();
                    cells++;
                }
            }
            final long expected = (long) parts * CELLS;
            if (cells != expected) {
                throw new IllegalStateException("the scratch table holds " + cells + " cells after the import, not " + expected);
            }
            out.printf(Locale.ROOT, "Smoke test passed: %d files written under %s, imported into %s, %,d cells read back.%n", parts,
                    work.uri(), table, cells);
        } finally {
            store.tables().delete(table);
            work.delete("smoke");
        }
    }
}
