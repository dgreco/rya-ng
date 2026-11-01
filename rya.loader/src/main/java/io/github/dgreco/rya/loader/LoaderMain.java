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
package io.github.dgreco.rya.loader;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.dgreco.rya.accumulo.AccumuloStore;
import io.github.dgreco.rya.hbase.HBaseLabels;
import io.github.dgreco.rya.hbase.HBaseStore;
import io.github.dgreco.rya.store.spi.Store;

/**
 * The loader's command line: {@code prepare}, {@code load}, {@code dry-run}, {@code verify}, {@code drop},
 * {@code check}, {@code labels}.
 */
public final class LoaderMain {
    /** Every table suffix a store uses (see TablePrefixLayoutStrategy). */
    static final List<String> SUFFIXES = List.of("spo", "po", "osp", "ns", "eval", "graphs", "text", "geo");

    private LoaderMain() {
    }

    public static void main(final String[] args) {
        quietLibraries();
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    /** Keeps the output readable: the loader's own lines, and only warnings from the libraries. */
    private static void quietLibraries() {
        for (final String noisy : List.of("org.apache.zookeeper", "org.apache.accumulo", "org.apache.hadoop", "org.apache.curator",
                "org.apache.hbase")) {
            if (System.getProperty("org.slf4j.simpleLogger.log." + noisy) == null) {
                System.setProperty("org.slf4j.simpleLogger.log." + noisy, "warn");
            }
        }
        // Expected on every HBase run: no native Hadoop library, and the ZooKeeper registry rya-ng picks on purpose
        // when given only a quorum.
        for (final String expected : List.of("org.apache.hadoop.util.NativeCodeLoader",
                "org.apache.hadoop.hbase.client.ZKConnectionRegistry")) {
            if (System.getProperty("org.slf4j.simpleLogger.log." + expected) == null) {
                System.setProperty("org.slf4j.simpleLogger.log." + expected, "error");
            }
        }
        if (System.getProperty("org.slf4j.simpleLogger.showDateTime") == null) {
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        }
    }

    /** @return the exit code: 0 done, 1 failed or interrupted (resumable), 2 bad arguments or configuration */
    public static int run(final String[] args, final Map<String, String> env, final PrintStream out, final PrintStream err) {
        final LoaderOptions o;
        try {
            o = LoaderOptions.parse(args, env);
        } catch (final IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println("Run with --help for the options.");
            return 2;
        }
        if (o.help || o.command == null) {
            out.print(LoaderOptions.usage());
            return 0;
        }
        try {
            if (o.command == LoaderOptions.Command.DRY_RUN) {
                DryRun.run(o, out);
                return 0;
            }
            try (Store store = StoreFactory.open(o)) {
                return switch (o.command) {
                    case PREPARE -> {
                        Prepare.run(store, o, out);
                        yield 0;
                    }
                    case LOAD -> load(store, o, out);
                    case VERIFY -> Verify.run(store, o, out) ? 0 : 1;
                    case DROP -> {
                        drop(store, o, out);
                        yield 0;
                    }
                    case CHECK -> {
                        check(store, o, out);
                        yield 0;
                    }
                    case LABELS -> {
                        Labels.run(store, o, out);
                        yield 0;
                    }
                    default -> 2;
                };
            }
        } catch (final IllegalArgumentException | StoreCheck.Mismatch e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (final HBaseLabels.NotEnforced e) {
            err.println("Error: " + e.getMessage());
            err.println("Only the labels command runs as a superuser; give the other commands a normal account "
                    + "(--hbase-user, or the account of --hbase-properties).");
            return 2;
        } catch (final Exception e) {
            err.print("Failed: ");
            e.printStackTrace(err);
            return 1;
        }
    }

    private static int load(final Store store, final LoaderOptions o, final PrintStream out) throws Exception {
        final Loader loader = new Loader(store, o, out);
        final CountDownLatch finished = new CountDownLatch(1);
        // Ctrl-C: finish the current blocks, flush, record progress, then exit.
        final Thread hook = new Thread(() -> {
            loader.requestStop();
            try {
                finished.await(5, TimeUnit.MINUTES);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return loader.run().complete() ? 0 : 1;
        } finally {
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException e) {
                // Already shutting down.
            }
        }
    }

    /** What the loader will connect to and may do there. */
    private static void check(final Store store, final LoaderOptions o, final PrintStream out) throws Exception {
        final String user = store.access().whoami();
        if (store instanceof AccumuloStore accumulo) {
            final Properties props = accumulo.client().properties();
            out.println("Connected to Accumulo instance " + props.getProperty("instance.name") + " (ZooKeepers "
                    + props.getProperty("instance.zookeepers") + ") as " + user + ".");
        } else {
            out.println("Connected to " + store.backend() + " as " + user + ".");
        }
        out.println("Servers: " + store.tables().serverCount());
        final Optional<List<String>> labels = store.access().heldLabels();
        if (labels.isPresent()) {
            out.println("Labels of " + user + ": " + (labels.get().isEmpty() ? "none (unlabeled data only)"
                    : String.join(",", labels.get())));
        } else {
            out.println("Labels of " + user + ": not readable on " + store.backend()
                    + " (the endpoint checks its labels at startup)");
        }
        if (store instanceof HBaseStore hbase && !hbase.enforcesLabels()) {
            out.println("Warning: this HBase cluster doesn't enforce cell visibility; labelled statements will be refused.");
        }
        out.println("May create tables: " + (store.access().mayCreateTables() ? "yes" : "no (prepare needs it)"));
        if (o.tablePrefix() != null) {
            final List<String> present = SUFFIXES.stream().map(s -> o.tablePrefix() + s)
                    .filter(t -> store.tables().exists(t)).toList();
            out.println("Store " + o.tablePrefix() + ": " + (present.isEmpty() ? "not found" : "tables " + present));
        }
    }

    private static void drop(final Store store, final LoaderOptions o, final PrintStream out) throws Exception {
        int dropped = 0;
        for (final String suffix : SUFFIXES) {
            final String table = o.tablePrefix() + suffix;
            if (store.tables().exists(table)) {
                store.tables().delete(table);
                out.println("Deleted " + table);
                dropped++;
            }
        }
        out.println(dropped == 0 ? "No tables with prefix " + o.tablePrefix() : "Dropped store " + o.tablePrefix());
    }
}
