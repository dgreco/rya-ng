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
package org.tripatha.loader;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.tripatha.loader.bulk.Spills;
import org.tripatha.store.spi.BulkImport;
import org.tripatha.store.spi.Store;

/**
 * The last two phases of a bulk build, shared by {@code build} and {@code rebuild}: the sorted runs
 * of each table merged into one file per row range of the table in the backend's format, then
 * every file imported once. Both are resumable: {@code merge.properties} records each range merged,
 * {@code import.properties} each file imported, and a file that is gone was imported (backends
 * move the files they import).
 */
final class BulkFiles {
    /** Whether a counter's total stays out of the files. */
    interface LeftOut {
        boolean test(byte[] family, long total);
    }

    private final Store store;
    private final Spills spills;
    private final Path work;
    private final int part;
    private final int parts;
    private final int threads;
    private final PrintStream out;

    BulkFiles(final Store store, final Spills spills, final Path work, final int part, final int parts, final int threads,
            final PrintStream out) {
        this.store = store;
        this.spills = spills;
        this.work = work;
        this.part = part;
        this.parts = parts;
        this.threads = threads;
        this.out = out;
    }

    /**
     * Merges each table's runs, per row range of the table. The ranges are fixed the first time,
     * in {@code merge-plan.properties}: a store's split rows change as files are imported (RocksDB
     * reads them from its files) and as tables split, and a range merged twice would be imported
     * twice. This process merges the ranges whose index is its part modulo the parts.
     */
    void merge(final BulkImport bulk, final Map<String, List<String>> byTable, final List<String> tables,
            final Predicate<String> counterTable, final LeftOut leftOut, final long importTime) throws Exception {
        final Path manifest = work.resolve("merge.properties");
        final Properties done = load(manifest);
        final Path planFile = work.resolve("merge-plan.properties");
        final Properties plan = load(planFile);
        if (plan.isEmpty()) {
            for (final String table : tables) {
                final List<String> rows = new ArrayList<>();
                for (final byte[] row : store.tables().splitRows(table, Integer.MAX_VALUE)) {
                    rows.add(HexFormat.of().formatHex(row));
                }
                plan.setProperty(table, String.join(",", rows));
            }
            save(planFile, plan);
        }
        final List<Object[]> tasks = new ArrayList<>();
        for (final String table : tables) {
            final String planned = plan.getProperty(table, "");
            final List<byte[]> splits = new ArrayList<>();
            for (final String row : planned.isEmpty() ? new String[0] : planned.split(",")) {
                splits.add(HexFormat.of().parseHex(row));
            }
            for (int i = 0; i <= splits.size(); i++) {
                if (i % parts == part && done.getProperty(table + "#" + i) == null) {
                    tasks.add(new Object[] {table, i, i == 0 ? null : splits.get(i - 1), i == splits.size() ? null : splits.get(i)});
                }
            }
        }
        final long t0 = System.currentTimeMillis();
        final AtomicLong cells = new AtomicLong();
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (final Object[] t : tasks) {
                futures.add(pool.submit(() -> {
                    final String table = (String) t[0];
                    final int index = (Integer) t[1];
                    final BulkImport.Written w = mergeRange(bulk, table, byTable.getOrDefault(table, List.of()), (byte[]) t[2],
                            (byte[]) t[3], index, counterTable.test(table), leftOut, importTime);
                    synchronized (done) {
                        done.setProperty(table + "#" + index, w == null ? "" : describe(w));
                        save(manifest, done);
                    }
                    if (w != null) {
                        cells.addAndGet(w.cells());
                    }
                    return null;
                }));
            }
            for (final Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        final long millis = Math.max(1, System.currentTimeMillis() - t0);
        out.printf(Locale.ROOT, "Merge: %,d files, %,d cells in %s.%n", tasks.size(), cells.get(), duration(millis));
    }

    private BulkImport.Written mergeRange(final BulkImport bulk, final String table, final List<String> files, final byte[] from,
            final byte[] to, final int index, final boolean counters, final LeftOut leftOut, final long importTime) {
        final String dir = spills.path("final/" + table);
        // Each file within one tablet: Accumulo's split point ends its tablet, HBase's starts the next region.
        final boolean endsTablet = bulk.splitPointEndsTablet();
        try (Spills.Reader reader = spills.read(files, from, !endsTablet, to, endsTablet, counters);
                BulkImport.BulkFile file = bulk.newFile(table, dir, String.format(Locale.ROOT, "%s-%06d", table, index), importTime)) {
            while (reader.hasNext()) {
                final Spills.Combined c = reader.next();
                if (counters) {
                    if (!leftOut.test(c.key().family(), c.delta())) {
                        file.counter(c.key().row(), c.key().family(), c.key().qualifier(), c.key().visibility(), c.delta());
                    }
                } else {
                    file.put(c.key().row(), c.key().family(), c.key().qualifier(), c.key().visibility(), c.timestamp(), c.value());
                }
            }
            return file.finish();
        }
    }

    private static String describe(final BulkImport.Written w) {
        return w.path() + "|" + HexFormat.of().formatHex(w.firstRow()) + "|" + HexFormat.of().formatHex(w.lastRow()) + "|" + w.cells();
    }

    private static BulkImport.Written written(final String description) {
        final String[] p = description.split("\\|");
        return new BulkImport.Written(p[0], HexFormat.of().parseHex(p[1]), HexFormat.of().parseHex(p[2]), Long.parseLong(p[3]));
    }

    /** Imports every merged file not imported yet, table by table. */
    void importFiles(final BulkImport bulk, final List<String> tables) throws Exception {
        final Properties merged = load(work.resolve("merge.properties"));
        final Path manifest = work.resolve("import.properties");
        final Properties imported = load(manifest);
        final long t0 = System.currentTimeMillis();
        int files = 0;
        for (final String table : tables) {
            final List<String> keys = new ArrayList<>();
            final List<BulkImport.Written> pending = new ArrayList<>();
            for (final String key : new TreeMap<>(merged).keySet().stream().map(Object::toString).toList()) {
                if (!key.startsWith(table + "#") || imported.getProperty(key) != null) {
                    continue;
                }
                final String description = merged.getProperty(key);
                if (description.isEmpty()) {
                    imported.setProperty(key, "empty");
                    continue;
                }
                final BulkImport.Written w = written(description);
                if (!spills.exists(w.path())) {
                    // Gone: imported before a failure stopped the build from recording it.
                    imported.setProperty(key, "found imported");
                    continue;
                }
                keys.add(key);
                pending.add(w);
            }
            if (!pending.isEmpty()) {
                bulk.importFiles(table, pending);
                files += pending.size();
            }
            for (final String key : keys) {
                imported.setProperty(key, "imported");
            }
            save(manifest, imported);
        }
        final long millis = Math.max(1, System.currentTimeMillis() - t0);
        out.printf(Locale.ROOT, "Import: %,d files in %s.%n", files, duration(millis));
    }

    static String duration(final long millis) {
        final long s = millis / 1000;
        return s < 60 ? String.format(Locale.ROOT, "%.1f s", millis / 1000.0)
                : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    static Properties load(final Path file) throws IOException {
        final Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return p;
    }

    static void save(final Path file, final Properties p) {
        try {
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "Tripatha bulk build");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
