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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.security.SystemPermission;

/** The loader's command line: {@code prepare}, {@code load}, {@code dry-run}, {@code verify}, {@code drop}, {@code check}. */
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
        for (final String noisy : List.of("org.apache.zookeeper", "org.apache.accumulo", "org.apache.hadoop", "org.apache.curator")) {
            if (System.getProperty("org.slf4j.simpleLogger.log." + noisy) == null) {
                System.setProperty("org.slf4j.simpleLogger.log." + noisy, "warn");
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
            try (AccumuloClient client = ClientFactory.create(o)) {
                return switch (o.command) {
                    case PREPARE -> {
                        Prepare.run(client, o, out);
                        yield 0;
                    }
                    case LOAD -> load(client, o, out);
                    case VERIFY -> Verify.run(client, o, out) ? 0 : 1;
                    case DROP -> {
                        drop(client, o, out);
                        yield 0;
                    }
                    case CHECK -> {
                        check(client, o, out);
                        yield 0;
                    }
                    default -> 2;
                };
            }
        } catch (final IllegalArgumentException | StoreCheck.Mismatch e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (final Exception e) {
            err.print("Failed: ");
            e.printStackTrace(err);
            return 1;
        }
    }

    private static int load(final AccumuloClient client, final LoaderOptions o, final PrintStream out) throws Exception {
        final Loader loader = new Loader(client, o, out);
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
    private static void check(final AccumuloClient client, final LoaderOptions o, final PrintStream out) throws Exception {
        final String user = client.whoami();
        final Properties props = client.properties();
        out.println("Connected to instance " + props.getProperty("instance.name") + " (ZooKeepers "
                + props.getProperty("instance.zookeepers") + ") as " + user + ".");
        out.println("Tablet servers: " + client.instanceOperations().getTabletServers().size());
        final List<String> auths = new ArrayList<>();
        for (final byte[] a : client.securityOperations().getUserAuthorizations(user).getAuthorizations()) {
            auths.add(new String(a, StandardCharsets.UTF_8));
        }
        out.println("Authorizations of " + user + ": " + (auths.isEmpty() ? "none (unlabeled data only)" : String.join(",", auths)));
        out.println("May create tables: " + (client.securityOperations().hasSystemPermission(user,
                SystemPermission.CREATE_TABLE) ? "yes" : "no (prepare needs it)"));
        if (o.tablePrefix() != null) {
            final List<String> present = SUFFIXES.stream().map(s -> o.tablePrefix() + s)
                    .filter(t -> client.tableOperations().exists(t)).toList();
            out.println("Store " + o.tablePrefix() + ": " + (present.isEmpty() ? "not found" : "tables " + present));
        }
    }

    private static void drop(final AccumuloClient client, final LoaderOptions o, final PrintStream out) throws Exception {
        int dropped = 0;
        for (final String suffix : SUFFIXES) {
            final String table = o.tablePrefix() + suffix;
            if (client.tableOperations().exists(table)) {
                client.tableOperations().delete(table);
                out.println("Deleted " + table);
                dropped++;
            }
        }
        out.println(dropped == 0 ? "No tables with prefix " + o.tablePrefix() : "Dropped store " + o.tablePrefix());
    }
}
