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
package org.tripatha.loader.bench;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.tripatha.loader.BulkBuild;
import org.tripatha.loader.IndexRebuild;
import org.tripatha.loader.LoaderOptions;
import org.tripatha.loader.Prepare;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.test.TestBackend;
import org.tripatha.test.TestBackends;

/**
 * An index rebuild both ways, on the test backend (SCALE-STUDY.md O5): two stores of the same
 * generated dataset with the text and geo indexes are built, then the entity index is enabled on
 * each, built by the store's start-up rebuild ({@code index.rebuild=true}) on one and by the
 * loader's {@code rebuild} job on the other. Prints both times and the postings each wrote.
 *
 * <p>Arguments: {@code [--triples 3M] [--threads 8] [--work-dir DIR]}.
 */
public final class RebuildBench {
    private RebuildBench() {
    }

    public static void main(final String[] args) throws Exception {
        String triples = "3M";
        String threads = "8";
        Path work = Path.of("target/bench/rebuild-work");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--triples" -> triples = args[++i];
                case "--threads" -> threads = args[++i];
                case "--work-dir" -> work = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        final PrintStream out = System.out;
        final PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        final TestBackend backend = TestBackends.current();
        out.printf(Locale.ROOT, "Rebuild benchmark on %s: %s triples, %s threads, %d processors.%n", backend.id(), triples, threads,
                Runtime.getRuntime().availableProcessors());
        try (Store store = backend.openStore()) {
            final List<String> prefixes = List.of("rbstart_", "rbjob_");
            for (final String prefix : prefixes) {
                backend.deleteTables(prefix);
                final long t0 = System.nanoTime();
                Prepare.run(store, options("prepare", prefix, triples, threads, false), quiet);
                final Path build = work.resolve("build-" + prefix);
                deleteRecursively(build);
                final List<String> a = new ArrayList<>(List.of(argv("build", prefix, triples, threads, false)));
                a.addAll(List.of("--work-dir", build.toString()));
                if (!new BulkBuild(store, LoaderOptions.parse(a.toArray(new String[0]), Map.of()), quiet).run()) {
                    throw new IllegalStateException("the build did not complete");
                }
                deleteRecursively(build);
                backend.compact(prefix + "spo");
                out.printf(Locale.ROOT, "%s: built without the entity index in %.0f s.%n", prefix, (System.nanoTime() - t0) / 1e9);
            }

            final StoreRdfConfiguration conf = options("load", "rbstart_", triples, threads, true).rdfConf();
            conf.setRebuildIndexes(true);
            long t0 = System.nanoTime();
            final RyaStoreDAO dao = new RyaStoreDAO(store, conf);
            dao.init();
            dao.destroy();
            final double startUp = (System.nanoTime() - t0) / 1e9;
            out.printf(Locale.ROOT, "Start-up rebuild (one thread, through the write path): %.1f s, %,d postings.%n", startUp,
                    cells(store, "rbstart_entity"));

            final Path job = work.resolve("rebuild-job");
            deleteRecursively(job);
            final List<String> a = new ArrayList<>(List.of(argv("rebuild", "rbjob_", triples, threads, true)));
            a.addAll(List.of("--work-dir", job.toString()));
            t0 = System.nanoTime();
            if (!new IndexRebuild(store, LoaderOptions.parse(a.toArray(new String[0]), Map.of()), out).run()) {
                throw new IllegalStateException("the rebuild did not complete");
            }
            final double batch = (System.nanoTime() - t0) / 1e9;
            out.printf(Locale.ROOT, "Rebuild job (%s threads, sorted files imported): %.1f s, %,d postings.%n", threads, batch,
                    cells(store, "rbjob_entity"));
            out.printf(Locale.ROOT, "Job / start-up: %.2f.%n", batch / startUp);
            deleteRecursively(job);
            for (final String prefix : prefixes) {
                backend.deleteTables(prefix);
            }
        }
    }

    private static String[] argv(final String command, final String prefix, final String triples, final String threads,
            final boolean entity) {
        final List<String> a = new ArrayList<>(List.of(command, "--table-prefix", prefix, "--triples", triples, "--text-index",
                "--geo-index", "--threads", threads));
        if (entity) {
            a.add("--entity-index");
        }
        return a.toArray(new String[0]);
    }

    private static LoaderOptions options(final String command, final String prefix, final String triples, final String threads,
            final boolean entity) {
        return LoaderOptions.parse(argv(command, prefix, triples, threads, entity), Map.of());
    }

    private static long cells(final Store store, final String table) {
        long n = 0;
        try (CellScanner c = store.scan(table, KeyRange.all(), ScanSpec.of(Auths.ALL_HELD))) {
            while (c.hasNext()) {
                c.next();
                n++;
            }
        }
        return n;
    }

    private static void deleteRecursively(final Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (final Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
