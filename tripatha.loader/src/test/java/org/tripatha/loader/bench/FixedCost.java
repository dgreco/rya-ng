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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

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

/**
 * The fixed cost of a query (SCALE-STUDY.md M14): where a small query's time goes, in-process,
 * on a RocksDB store through the Sail, as the endpoint runs it. For each query file, the p50 over
 * the timed runs of:
 * <ul>
 * <li><b>open</b>, the connection; <b>parse</b>, {@code prepareTupleQuery};</li>
 * <li><b>prepare</b>, from {@code evaluate} to the trace's {@code parsed} plan: the connection's
 * configuration and set-up;</li>
 * <li><b>plan</b>, to {@code optimized}: the optimizers, with the scans they make (statistics
 * counters, datatype markers) counted apart;</li>
 * <li><b>run</b>, from {@code optimized} to the last row; <b>close</b>;</li>
 * <li><b>TSV</b>, the same query untraced with its rows written as the endpoint writes them, to a
 * sink that only counts bytes; and <b>untraced</b>, the plain total, so the trace's own cost
 * shows.</li>
 * </ul>
 * The HTTP side is CompareBench's {@code --raw} against a running endpoint; the difference is the
 * endpoint's own cost. Point it at a copy of a store.
 *
 * <p>Arguments: {@code --rocksdb-path DIR --queries DIR [--only S1,L2,F] [--table-prefix watdiv_]
 * [--warmup 100] [--runs 300] [--conf key=value]}.
 */
public final class FixedCost {
    private FixedCost() {
    }

    /** One run's times, in milliseconds. */
    private record Run(double open, double parse, double prepare, double plan, double run, double close, int planScans, int runScans,
            long rows) {
    }

    public static void main(final String[] args) throws Exception {
        Path directory = null;
        Path queries = null;
        String prefix = "watdiv_";
        List<String> only = List.of();
        int warmup = 100;
        int runs = 300;
        final Map<String, String> settings = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rocksdb-path" -> directory = Path.of(args[++i]);
                case "--queries" -> queries = Path.of(args[++i]);
                case "--only" -> only = List.of(args[++i].split(","));
                case "--table-prefix" -> prefix = args[++i];
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--conf" -> {
                    final String kv = args[++i];
                    settings.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (directory == null || queries == null) {
            System.err.println("Usage: FixedCost --rocksdb-path DIR --queries DIR [--only S1,L2] [--table-prefix watdiv_] "
                    + "[--warmup 100] [--runs 300] [--conf key=value ...]");
            System.exit(2);
        }
        final List<String> wanted = only;
        final Map<String, String> files = new TreeMap<>();
        try (Stream<Path> list = Files.list(queries)) {
            for (final Path f : list.filter(p -> p.toString().endsWith(".rq")).toList()) {
                final String name = f.getFileName().toString().replace(".rq", "");
                if (wanted.isEmpty() || wanted.stream().anyMatch(name::startsWith)) {
                    files.put(name, Files.readString(f, StandardCharsets.UTF_8));
                }
            }
        }
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(prefix);
        conf.setEntityIndex(true);
        conf.setMaintainStats(true);
        conf.setUseStats(true);
        conf.setInfer(false);
        settings.forEach(conf::set);
        try (RocksStore store = RocksStore.open(directory)) {
            final SailRepository repo = RyaSailFactory.createRepository(store, conf);
            repo.init();
            try {
                report(repo, files, warmup, runs);
            } finally {
                repo.shutDown();
            }
        }
    }

    /** Prints the table for these queries, by name, on this repository (JoinBench's {@code --fixed-cost} on any backend). */
    static void report(final SailRepository repo, final Map<String, String> files, final int warmup, final int runs)
            throws Exception {
        System.out.printf(Locale.ROOT, "# The fixed cost per query, in-process (p50 of %d runs after %d warm-up runs, ms)%n%n", runs, warmup);
        System.out.println("| Query | Rows | Open | Parse | Prepare | Plan | Plan scans | Run | Run scans | Close | Traced total | "
                + "Untraced | With TSV |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        final List<double[]> all = new ArrayList<>();
        for (final Map.Entry<String, String> q : files.entrySet()) {
            final String sparql = q.getValue();
            for (int i = 0; i < warmup; i++) {
                traced(repo, sparql);
                untraced(repo, sparql, false);
                untraced(repo, sparql, true);
            }
            final List<Run> timed = new ArrayList<>();
            final double[] plain = new double[runs];
            final double[] tsv = new double[runs];
            for (int i = 0; i < runs; i++) {
                timed.add(traced(repo, sparql));
                plain[i] = untraced(repo, sparql, false);
                tsv[i] = untraced(repo, sparql, true);
            }
            final double[] row = {p50(timed, Run::open), p50(timed, Run::parse), p50(timed, Run::prepare), p50(timed, Run::plan),
                timed.get(0).planScans(), p50(timed, Run::run), timed.get(0).runScans(), p50(timed, Run::close),
                p50(timed, r -> r.open() + r.parse() + r.prepare() + r.plan() + r.run() + r.close()), p50(plain), p50(tsv)};
            all.add(row);
            System.out.printf(Locale.ROOT, "| %s | %,d | %.3f | %.3f | %.3f | %.3f | %.0f | %.3f | %.0f | %.3f | %.3f | %.3f | %.3f |%n",
                    q.getKey(), timed.get(0).rows(), row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], row[8], row[9],
                    row[10]);
        }
        final double[] median = new double[11];
        for (int c = 0; c < median.length; c++) {
            final int col = c;
            median[c] = p50(all.stream().mapToDouble(r -> r[col]).toArray());
        }
        System.out.printf(Locale.ROOT, "| **median of the queries** | | %.3f | %.3f | %.3f | %.3f | %.0f | %.3f | %.0f | %.3f | %.3f | %.3f | %.3f |%n",
                median[0], median[1], median[2], median[3], median[4], median[5], median[6], median[7], median[8], median[9], median[10]);
    }

    /** One traced run: its stages from the clock and the trace's marks. */
    private static Run traced(final SailRepository repo, final String sparql) {
        final List<TraceEvent> events = new ArrayList<>();
        final long t0 = System.nanoTime();
        long rows = 0;
        final long t1;
        final long t2;
        final long t3;
        try (RepositoryConnection conn = repo.getConnection()) {
            t1 = System.nanoTime();
            final TupleQuery query = conn.prepareTupleQuery(sparql);
            t2 = System.nanoTime();
            try (StoreTrace.Scope scope = StoreTrace.start(events::add); TupleQueryResult result = query.evaluate()) {
                while (result.hasNext()) {
                    result.next();
                    rows++;
                }
            }
            t3 = System.nanoTime();
        }
        final long t4 = System.nanoTime();
        double parsed = 0;
        double optimized = 0;
        for (final TraceEvent e : events) {
            if (e instanceof TraceEvent.Plan p && p.stage().equals("parsed")) {
                parsed = p.at();
            } else if (e instanceof TraceEvent.Plan p && p.stage().equals("optimized")) {
                optimized = p.at();
            }
        }
        int planScans = 0;
        int runScans = 0;
        for (final TraceEvent e : events) {
            if (e instanceof TraceEvent.ScanStart s) {
                if (s.at() < optimized) {
                    planScans++;
                } else {
                    runScans++;
                }
            }
        }
        final double evaluate = (t3 - t2) / 1e6;
        return new Run((t1 - t0) / 1e6, (t2 - t1) / 1e6, parsed, optimized - parsed, evaluate - optimized, (t4 - t3) / 1e6, planScans,
                runScans, rows);
    }

    /** One untraced run, its rows read or written as TSV: its total, in milliseconds. */
    private static double untraced(final SailRepository repo, final String sparql, final boolean tsv) {
        final long t0 = System.nanoTime();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TupleQuery query = conn.prepareTupleQuery(sparql);
            if (tsv) {
                query.evaluate(new SPARQLResultsTSVWriter(OutputStream.nullOutputStream()));
            } else {
                try (TupleQueryResult result = query.evaluate()) {
                    while (result.hasNext()) {
                        result.next();
                    }
                }
            }
        }
        return (System.nanoTime() - t0) / 1e6;
    }

    private static double p50(final List<Run> runs, final java.util.function.ToDoubleFunction<Run> f) {
        return p50(runs.stream().mapToDouble(f).toArray());
    }

    private static double p50(final double[] values) {
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
    }
}
