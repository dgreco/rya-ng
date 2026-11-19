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
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;

import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.resultio.text.tsv.SPARQLResultsTSVWriter;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.tripatha.rdftriplestore.RyaSailFactory;
import org.tripatha.rocksdb.RocksStore;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;

import jdk.jfr.Recording;

/**
 * Where one query's time goes, in-process: runs a query file on a RocksDB store through the Sail,
 * as the endpoint does, and reports
 * <ul>
 * <li>the plan, and the store's scans grouped by the reason the trace gives them;</li>
 * <li>the p50 of reading every row, and of reading them and writing them as TSV (the endpoint's
 * output, to a sink that only counts bytes);</li>
 * <li>the query thread's CPU time, the JVM's, and the garbage collectors' time;</li>
 * <li>where the CPU time of the reading runs went, and of the writing runs (JFR samples).</li>
 * </ul>
 * {@code --conf key=value} sets a store configuration setting, for example
 * {@code query.join.subjectranges=true}. Point it at a copy of a store: opening one with other
 * index settings could change it.
 */
public final class QueryProfile {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS = (com.sun.management.OperatingSystemMXBean) ManagementFactory
            .getOperatingSystemMXBean();

    private QueryProfile() {
    }

    public static void main(final String[] args) throws Exception {
        Path directory = null;
        Path query = null;
        String prefix = "watdiv_";
        boolean entityIndex = true;
        int warmup = 3;
        int runs = 10;
        final Map<String, String> settings = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rocksdb-path" -> directory = Path.of(args[++i]);
                case "--query" -> query = Path.of(args[++i]);
                case "--table-prefix" -> prefix = args[++i];
                case "--no-entity-index" -> entityIndex = false;
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--conf" -> {
                    final String kv = args[++i];
                    settings.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (directory == null || query == null) {
            System.err.println("Usage: QueryProfile --rocksdb-path DIR --query FILE.rq [--table-prefix watdiv_] [--no-entity-index] "
                    + "[--warmup 3] [--runs 10] [--conf key=value ...]");
            System.exit(2);
        }
        final String sparql = Files.readString(query, StandardCharsets.UTF_8);
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(prefix);
        conf.setEntityIndex(entityIndex);
        conf.setMaintainStats(true);
        conf.setUseStats(true);
        conf.setInfer(false);
        settings.forEach(conf::set);
        try (RocksStore store = RocksStore.open(directory)) {
            final SailRepository repo = RyaSailFactory.createRepository(store, conf);
            repo.init();
            try {
                profile(repo, query.getFileName().toString(), sparql, warmup, runs);
            } finally {
                repo.shutDown();
            }
        }
    }

    private static void profile(final SailRepository repo, final String name, final String sparql, final int warmup, final int runs)
            throws Exception {
        for (int i = 0; i < warmup; i++) {
            read(repo, sparql);
            readAndWrite(repo, sparql);
        }
        System.out.println("# " + name + "\n");
        traced(repo, sparql);

        final Recording reading = startRecording();
        final Timing read = time(runs, () -> read(repo, sparql));
        reading.stop();
        final Recording writing = startRecording();
        final Timing written = time(runs, () -> readAndWrite(repo, sparql));
        writing.stop();

        System.out.printf(Locale.ROOT, "%n## Time (p50 of %d runs, after %d warm-up runs)%n%n", runs, warmup);
        System.out.println("| Runs | Wall | Query thread CPU | JVM CPU | GC | Result |");
        System.out.println("|---|---|---|---|---|---|");
        System.out.printf(Locale.ROOT, "| Reading every row | %.1f ms | %.1f ms | %.1f ms | %.1f ms | %,d rows |%n", read.wall, read.cpu, read.jvm,
                read.gc, read.result);
        System.out.printf(Locale.ROOT, "| Reading and writing TSV | %.1f ms | %.1f ms | %.1f ms | %.1f ms | %,d bytes |%n", written.wall,
                written.cpu, written.jvm, written.gc, written.result);
        print("Where the CPU time of the reading runs went", JoinBench.Profile.of(reading, Files.createTempFile("read", ".jfr")));
        print("Where the CPU time of the writing runs went", JoinBench.Profile.of(writing, Files.createTempFile("write", ".jfr")));
    }

    /** The p50 of each measure over the runs, and the result of the last run. */
    private record Timing(double wall, double cpu, double jvm, double gc, long result) {
    }

    private static Timing time(final int runs, final ThrowingLongSupplier run) throws Exception {
        final double[] wall = new double[runs];
        final double[] cpu = new double[runs];
        final double[] jvm = new double[runs];
        final double[] gc = new double[runs];
        long result = 0;
        for (int i = 0; i < runs; i++) {
            final long g0 = gcMillis();
            final long c0 = THREADS.getCurrentThreadCpuTime();
            final long p0 = OS.getProcessCpuTime();
            final long t0 = System.nanoTime();
            result = run.get();
            wall[i] = (System.nanoTime() - t0) / 1e6;
            cpu[i] = (THREADS.getCurrentThreadCpuTime() - c0) / 1e6;
            jvm[i] = (OS.getProcessCpuTime() - p0) / 1e6;
            gc[i] = gcMillis() - g0;
        }
        return new Timing(p50(wall), p50(cpu), p50(jvm), p50(gc), result);
    }

    @FunctionalInterface
    private interface ThrowingLongSupplier {
        long get() throws Exception;
    }

    private static double p50(final double[] values) {
        final double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long gcMillis() {
        long total = 0;
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    /** Every row read, nothing written: the rows. */
    private static long read(final SailRepository repo, final String sparql) {
        return evaluate(repo, sparql, result -> {
            long n = 0;
            while (result.hasNext()) {
                result.next();
                n++;
            }
            return n;
        });
    }

    /** Every row read and written as TSV, as the endpoint writes it: the bytes. */
    private static long readAndWrite(final SailRepository repo, final String sparql) {
        return evaluate(repo, sparql, result -> {
            final long[] bytes = {0};
            final OutputStream sink = new OutputStream() {
                @Override
                public void write(final int b) {
                    bytes[0]++;
                }

                @Override
                public void write(final byte[] b, final int off, final int len) {
                    bytes[0] += len;
                }
            };
            final SPARQLResultsTSVWriter writer = new SPARQLResultsTSVWriter(sink);
            writer.startQueryResult(result.getBindingNames());
            while (result.hasNext()) {
                writer.handleSolution(result.next());
            }
            writer.endQueryResult();
            return bytes[0];
        });
    }

    private static long evaluate(final SailRepository repo, final String sparql, final ToLongFunction<TupleQueryResult> consumer) {
        try (RepositoryConnection conn = repo.getConnection()) {
            final TupleQuery tq = conn.prepareTupleQuery(sparql);
            try (TupleQueryResult result = tq.evaluate()) {
                return consumer.applyAsLong(result);
            }
        }
    }

    /** One run, traced: the plan, and the scans grouped by their reason. */
    private static void traced(final SailRepository repo, final String sparql) {
        final List<TraceEvent.Plan> plans = new ArrayList<>();
        final Map<Integer, String> reasonOf = new LinkedHashMap<>();
        final Map<String, long[]> byReason = new LinkedHashMap<>();
        final double[] ms = new double[1];
        final long t0 = System.nanoTime();
        final long rows;
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            synchronized (byReason) {
                if (event instanceof TraceEvent.Plan p) {
                    plans.add(p);
                } else if (event instanceof TraceEvent.ScanStart s) {
                    final String reason = s.op() + " " + s.table() + ": " + (s.reason() == null ? "(no reason)" : s.reason());
                    reasonOf.put(s.id(), reason);
                    final long[] r = byReason.computeIfAbsent(reason, k -> new long[3]);
                    r[0]++;
                    r[1] += s.rangeCount();
                } else if (event instanceof TraceEvent.ScanEnd e) {
                    final long[] r = byReason.get(reasonOf.get(e.id()));
                    if (r != null) {
                        r[2] += Math.max(0, e.entries());
                    }
                    ms[0] += e.ms();
                }
            }
        })) {
            rows = read(repo, sparql);
        }
        final double wall = (System.nanoTime() - t0) / 1e6;
        for (final TraceEvent.Plan p : plans) {
            System.out.println("## Plan (" + p.stage() + ")\n\n```\n" + p.tree().strip() + "\n```\n");
        }
        System.out.printf(Locale.ROOT, "## Storage, one traced run: %,d rows in %.1f ms, %.1f ms in storage calls%n%n", rows, wall, ms[0]);
        System.out.println("| Scans | Ranges | Cells | Why |");
        System.out.println("|---|---|---|---|");
        synchronized (byReason) {
            byReason.forEach((reason, r) -> System.out.printf(Locale.ROOT, "| %,d | %,d | %,d | %s |%n", r[0], r[1], r[2],
                    reason.replace("|", "\\|")));
        }
    }

    private static Recording startRecording() {
        final Recording r = new Recording();
        r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
        r.start();
        return r;
    }

    private static void print(final String title, final JoinBench.Profile p) {
        System.out.printf(Locale.ROOT, "%n## %s (%,d samples)%n%n", title, p.samples());
        System.out.println("| Area | Share |");
        System.out.println("|---|---|");
        p.byArea().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf(Locale.ROOT, "| %s | %.0f %% |%n", e.getKey(), 100.0 * e.getValue() / p.samples()));
        System.out.println("\nBusiest methods, the first Tripatha or RDF4J frame (and the innermost frame):\n");
        p.topOwn().forEach(e -> System.out.printf(Locale.ROOT, "- %s: %.0f %%%n", e.getKey(), 100.0 * e.getValue() / p.samples()));
        System.out.println();
        p.topSelf().forEach(e -> System.out.printf(Locale.ROOT, "- (innermost) %s: %.0f %%%n", e.getKey(), 100.0 * e.getValue() / p.samples()));
    }
}
