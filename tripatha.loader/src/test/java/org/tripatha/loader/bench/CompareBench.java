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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a standard benchmark's queries against several SPARQL endpoints and compares them
 * (SCALE-STUDY.md M11): WatDiv's, on Tripatha's endpoint over RocksDB and on other engines loaded
 * with the same data. Run it with {@code tripatha.loader/compare.sh}, which generates the data and
 * the queries, loads the engines and starts their servers.
 *
 * <p>The queries are the {@code .rq} files of a directory. Files named after WatDiv's templates
 * ({@code S1-03.rq}) come in its order (linear, star, snowflake, complex), and the report adds a
 * summary per query shape.
 *
 * <p>Every query goes over HTTP as a SPARQL protocol POST, and its whole result is read as TSV
 * (one line per row, uncompressed); its time is the time to the last byte. Every engine runs each
 * query a few times before timing, then the engines take turns, each run starting with a
 * different one. An engine's result cache would answer the timed runs from the warm-up, so a
 * {@code --clear} URL is called before every run of that engine.
 *
 * <p>Numbers mean something only if the engines hold and return the same data, so it checks
 * first: the statement count overall and per graph, and then every query's row count. A query
 * whose row count differs between engines, or that fails on one, is reported but left out of the
 * summary.
 */
public final class CompareBench {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final Pattern INTEGER = Pattern.compile("^\"(-?\\d+)\"\\^\\^<http://www\\.w3\\.org/2001/XMLSchema#integer>$");
    /** A WatDiv query file's name: the template's shape and number, then the instance. */
    private static final Pattern TEMPLATE = Pattern.compile("^([LSFC])(\\d+)-(\\d+)$");
    private static final String SHAPES = "LSFC";
    private static final Map<String, String> SHAPE_NAMES = Map.of("L", "linear", "S", "star", "F", "snowflake", "C", "complex");

    /** A query, named after its file. */
    record Query(String name, String sparql) {
    }

    /** An engine's query URL, and the URL that empties its result cache (or null). */
    record Endpoint(String name, URI query, URI clear) {
    }

    /** One query on one engine: rows and latencies, or the error that stopped it. */
    record Outcome(long rows, double p50, double p90, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private CompareBench(final Settings settings) {
        this.settings = settings;
    }

    public static void main(final String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println(Settings.USAGE);
            return;
        }
        final Settings settings;
        try {
            settings = Settings.parse(args);
        } catch (final IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(Settings.USAGE);
            System.exit(2);
            return;
        }
        new CompareBench(settings).run();
    }

    private void run() throws Exception {
        Files.createDirectories(settings.out);
        final List<Query> suite = readQueries(settings.queries).stream()
                .filter(q -> settings.only.isEmpty() || settings.only.contains(q.name())).toList();
        System.out.printf(Locale.ROOT, "Comparing %s on %d queries.%n", settings.endpoints.stream().map(Endpoint::name).toList(),
                suite.size());
        final Map<String, Map<String, String>> checks = checks();
        final Map<String, Map<String, Outcome>> results = new LinkedHashMap<>();
        for (final Query q : suite) {
            final Map<String, Outcome> byEngine = measure(q);
            results.put(q.name(), byEngine);
            final StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-10s", q.name()));
            byEngine.forEach((e, o) -> line.append(String.format(Locale.ROOT, "  %s %s", e,
                    o.ok() ? String.format(Locale.ROOT, "%,d rows %.1f ms", o.rows(), o.p50()) : o.error())));
            System.out.println(line);
        }
        report(suite, checks, results);
    }

    /** The {@code .rq} files of a directory, in WatDiv's order when they are named after its templates. */
    static List<Query> readQueries(final Path dir) throws IOException {
        final List<Path> files;
        try (var listing = Files.list(dir)) {
            files = listing.filter(f -> f.getFileName().toString().endsWith(".rq")).toList();
        }
        final List<Query> out = new ArrayList<>();
        for (final Path f : files) {
            out.add(new Query(f.getFileName().toString().replaceFirst("\\.rq$", ""), Files.readString(f, StandardCharsets.UTF_8)));
        }
        out.sort(CompareBench::watDivOrder);
        return out;
    }

    /** WatDiv's order: L, S, F, C, then the template's number, then the instance; other names after, by name. */
    static int watDivOrder(final Query a, final Query b) {
        final Matcher x = TEMPLATE.matcher(a.name());
        final Matcher y = TEMPLATE.matcher(b.name());
        if (x.matches() && y.matches()) {
            int c = Integer.compare(SHAPES.indexOf(x.group(1)), SHAPES.indexOf(y.group(1)));
            c = c != 0 ? c : Integer.compare(Integer.parseInt(x.group(2)), Integer.parseInt(y.group(2)));
            return c != 0 ? c : Integer.compare(Integer.parseInt(x.group(3)), Integer.parseInt(y.group(3)));
        }
        return x.matches() != y.matches() ? (x.matches() ? -1 : 1) : a.name().compareTo(b.name());
    }

    // ---- checks ----

    /**
     * The statement count overall and per named graph, from each engine: the data must have
     * reached each one whole. A query without {@code GRAPH} reads every graph on all of them
     * (Oxigraph with {@code --union-default-graph}).
     */
    private Map<String, Map<String, String>> checks() throws Exception {
        final Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        for (final Endpoint e : settings.endpoints) {
            final List<String> total = select(e, "SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }");
            rows.computeIfAbsent("statements", k -> new LinkedHashMap<>()).put(e.name(), total.isEmpty() ? "none" : total.get(0));
            for (final String line : select(e, "SELECT ?g (COUNT(*) AS ?n) WHERE { GRAPH ?g { ?s ?p ?o } } GROUP BY ?g")) {
                final String[] f = line.split("\t", -1);
                rows.computeIfAbsent("graph " + f[0], k -> new LinkedHashMap<>()).put(e.name(), f.length > 1 ? f[1] : "");
            }
        }
        final List<String> engines = settings.endpoints.stream().map(Endpoint::name).toList();
        boolean same = true;
        for (final Map.Entry<String, Map<String, String>> r : rows.entrySet()) {
            final List<String> values = engines.stream().map(n -> r.getValue().getOrDefault(n, "missing")).toList();
            same &= values.stream().distinct().count() == 1;
            System.out.printf(Locale.ROOT, "Check %-60s %s%n", r.getKey(), values);
        }
        if (!same) {
            System.out.println("The engines don't hold the same statements: their latencies are not comparable.");
        }
        return rows;
    }

    /** A query's result lines, without the header, with integers written alike whatever the engine. */
    private List<String> select(final Endpoint e, final String sparql) throws Exception {
        final HttpResponse<String> response = http.send(request(e, sparql), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(e.name() + " answered " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        final List<String> lines = new ArrayList<>(response.body().lines().skip(1).filter(l -> !l.isEmpty()).toList());
        lines.replaceAll(CompareBench::normalize);
        lines.sort(null);
        return lines;
    }

    /** SPARQL TSV may write an integer bare or as a typed literal. */
    static String normalize(final String line) {
        final String[] fields = line.split("\t", -1);
        for (int i = 0; i < fields.length; i++) {
            final Matcher m = INTEGER.matcher(fields[i]);
            if (m.matches()) {
                fields[i] = m.group(1);
            }
        }
        return String.join("\t", fields);
    }

    // ---- queries ----

    private Map<String, Outcome> measure(final Query q) {
        final int n = settings.endpoints.size();
        final Map<String, List<Double>> times = new LinkedHashMap<>();
        final Map<String, Long> rows = new LinkedHashMap<>();
        final Map<String, String> errors = new LinkedHashMap<>();
        for (final Endpoint e : settings.endpoints) {
            times.put(e.name(), new ArrayList<>());
            for (int i = 0; i < settings.warmup && !errors.containsKey(e.name()); i++) {
                timed(e, q, rows, errors);
            }
        }
        for (int r = 0; r < settings.runs; r++) {
            for (int k = 0; k < n; k++) {
                final Endpoint e = settings.endpoints.get((r + k) % n);
                if (!errors.containsKey(e.name())) {
                    final double ms = timed(e, q, rows, errors);
                    if (ms >= 0) {
                        times.get(e.name()).add(ms);
                    }
                }
            }
        }
        final Map<String, Outcome> out = new LinkedHashMap<>();
        for (final Endpoint e : settings.endpoints) {
            final String error = errors.get(e.name());
            final List<Double> t = times.get(e.name());
            out.put(e.name(), error != null || t.isEmpty() ? new Outcome(-1, 0, 0, error == null ? "no runs" : error)
                    : new Outcome(rows.get(e.name()), percentile(t, 0.5), percentile(t, 0.9), null));
        }
        return out;
    }

    /**
     * One run: empties the engine's result cache, then sends the query and reads the whole result.
     * Returns the milliseconds to the last byte, or -1 after recording an error (or a row count
     * that changed between runs).
     */
    private double timed(final Endpoint e, final Query q, final Map<String, Long> rows, final Map<String, String> errors) {
        try {
            if (e.clear() != null) {
                final HttpResponse<Void> cleared = http.send(HttpRequest.newBuilder(e.clear()).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (cleared.statusCode() != 200) {
                    throw new IllegalStateException("clearing the cache answered " + cleared.statusCode());
                }
            }
            final long t0 = System.nanoTime();
            final HttpResponse<InputStream> response = http.send(request(e, q.sparql()), HttpResponse.BodyHandlers.ofInputStream());
            final long count;
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    errors.put(e.name(), "HTTP " + response.statusCode() + ": "
                            + abbreviate(new String(body.readNBytes(400), StandardCharsets.UTF_8)));
                    return -1;
                }
                count = countRows(body);
            }
            final double ms = (System.nanoTime() - t0) / 1e6;
            final Long before = rows.putIfAbsent(e.name(), count);
            if (before != null && before != count) {
                errors.put(e.name(), "rows changed between runs: " + before + ", then " + count);
                return -1;
            }
            return ms;
        } catch (final HttpTimeoutException ex) {
            errors.put(e.name(), "timeout (" + settings.timeoutSeconds + " s)");
            return -1;
        } catch (final IOException | RuntimeException ex) {
            errors.put(e.name(), "error: " + abbreviate(String.valueOf(ex.getMessage())));
            return -1;
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private HttpRequest request(final Endpoint e, final String sparql) {
        return HttpRequest.newBuilder(e.query()).timeout(Duration.ofSeconds(settings.timeoutSeconds))
                .header("Content-Type", "application/sparql-query; charset=utf-8").header("Accept", "text/tab-separated-values")
                .POST(HttpRequest.BodyPublishers.ofString(sparql, StandardCharsets.UTF_8)).build();
    }

    /** The rows of a TSV result: its lines, less the header. A literal's line breaks are escaped in TSV. */
    static long countRows(final InputStream body) throws IOException {
        final byte[] buffer = new byte[1 << 16];
        long lines = 0;
        boolean open = false;
        for (int n; (n = body.read(buffer)) > 0;) {
            for (int i = 0; i < n; i++) {
                if (buffer[i] == '\n') {
                    lines++;
                    open = false;
                } else {
                    open = true;
                }
            }
        }
        return Math.max(0, lines + (open ? 1 : 0) - 1);
    }

    private static double percentile(final List<Double> values, final double p) {
        final List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.min(sorted.size() - 1, Math.floor(p * sorted.size())));
    }

    private static String abbreviate(final String s) {
        final String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    // ---- the report ----

    private void report(final List<Query> suite, final Map<String, Map<String, String>> checks,
            final Map<String, Map<String, Outcome>> results) throws IOException {
        final List<String> engines = settings.endpoints.stream().map(Endpoint::name).toList();
        final String reference = engines.get(0);
        final String name = "compare-" + LocalDateTime.now().format(STAMP);
        final StringBuilder md = new StringBuilder("# Engine comparison\n\n");
        md.append(String.format(Locale.ROOT, "- %d processors, %d GB of memory, %s.%n", Runtime.getRuntime().availableProcessors(),
                Math.round(memoryBytes() / 1e9), System.getProperty("os.name") + " " + System.getProperty("os.version")));
        md.append(String.format(Locale.ROOT, "- Warm cache: %d warm-up and %d timed runs of each query per engine, the engines taking "
                + "turns. Times are p50 to the last byte of a TSV result over HTTP, in milliseconds.%n", settings.warmup, settings.runs));
        settings.info.forEach(i -> md.append("- ").append(i).append('\n'));

        md.append("\n## Load checks\n\n| Check | ").append(String.join(" | ", engines)).append(" |\n|---|")
                .append("---|".repeat(engines.size())).append('\n');
        checks.forEach((check, byEngine) -> md.append("| ").append(check).append(" | ")
                .append(String.join(" | ", engines.stream().map(e -> byEngine.getOrDefault(e, "missing")).toList())).append(" |\n"));

        md.append("\n## Queries\n\n| Query | Rows | ").append(String.join(" | ", engines));
        engines.stream().skip(1).forEach(e -> md.append(" | ").append(e).append(" / ").append(reference));
        md.append(" |\n|---|---|").append("---|".repeat(engines.size() * 2 - 1)).append('\n');
        final StringBuilder csv = new StringBuilder("query,engine,rows,p50_ms,p90_ms,comparable,error\n");
        final Map<String, List<Double>> comparable = new LinkedHashMap<>();
        engines.forEach(e -> comparable.put(e, new ArrayList<>()));
        final Map<String, Map<String, List<Double>>> byShape = new TreeMap<>(Comparator.comparingInt(SHAPES::indexOf));
        for (final Query q : suite) {
            final Map<String, Outcome> byEngine = results.get(q.name());
            final List<Long> counts = byEngine.values().stream().filter(Outcome::ok).map(Outcome::rows).distinct().toList();
            final boolean allOk = byEngine.values().stream().allMatch(Outcome::ok);
            final boolean same = allOk && counts.size() == 1;
            final String rows = same ? String.format(Locale.ROOT, "%,d", counts.get(0))
                    : (allOk ? "differ: " : "not comparable: ") + String.join(", ", engines.stream().map(e -> e + " "
                            + (byEngine.get(e).ok() ? String.format(Locale.ROOT, "%,d", byEngine.get(e).rows()) : "failed")).toList());
            md.append("| ").append(q.name()).append(" | ").append(rows);
            for (final String e : engines) {
                final Outcome o = byEngine.get(e);
                md.append(" | ").append(o.ok() ? String.format(Locale.ROOT, "%.1f", o.p50()) : o.error().replace("|", "\\|"));
                csv.append(String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%s,%s%n", q.name(), e, o.rows(), o.p50(), o.p90(), same,
                        o.ok() ? "" : '"' + o.error().replace("\"", "'") + '"'));
                if (same) {
                    comparable.get(e).add(o.p50());
                    final Matcher m = TEMPLATE.matcher(q.name());
                    if (m.matches()) {
                        byShape.computeIfAbsent(m.group(1), k -> new LinkedHashMap<>()).computeIfAbsent(e, k -> new ArrayList<>())
                                .add(o.p50());
                    }
                }
            }
            final Outcome ref = byEngine.get(reference);
            for (final String e : engines.subList(1, engines.size())) {
                final Outcome o = byEngine.get(e);
                md.append(" | ").append(same && ref.p50() > 0 ? String.format(Locale.ROOT, "%.2f", o.p50() / ref.p50()) : "");
            }
            md.append(" |\n");
        }
        md.append(String.format(Locale.ROOT, "%nGeometric mean of the p50s over the %d of %d queries every engine answered with the "
                + "same rows:%n%n", comparable.get(reference).size(), suite.size()));
        final double r = geometricMean(comparable.get(reference));
        for (final String e : engines) {
            final double g = geometricMean(comparable.get(e));
            md.append(String.format(Locale.ROOT, "- %s: %.1f ms%s%n", e, g, e.equals(reference) ? "" : String.format(Locale.ROOT,
                    " (%.2f times %s)", g / r, reference)));
        }
        if (!byShape.isEmpty()) {
            md.append("\nBy query shape (geometric mean of the p50s, comparable queries only):\n\n| Shape | Queries | ")
                    .append(String.join(" | ", engines)).append(" |\n|---|---|").append("---|".repeat(engines.size())).append('\n');
            byShape.forEach((shape, byEngine) -> {
                md.append("| ").append(SHAPE_NAMES.get(shape)).append(" | ").append(byEngine.get(reference).size());
                engines.forEach(e -> md.append(String.format(Locale.ROOT, " | %.1f", geometricMean(byEngine.getOrDefault(e, List.of())))));
                md.append(" |\n");
            });
        }
        final Path mdFile = settings.out.resolve(name + ".md");
        Files.writeString(mdFile, md, StandardCharsets.UTF_8);
        Files.writeString(settings.out.resolve(name + ".csv"), csv, StandardCharsets.UTF_8);
        System.out.println();
        System.out.print(md);
        System.out.println("\nReport: " + mdFile.toAbsolutePath());
    }

    private static double geometricMean(final List<Double> values) {
        return values.isEmpty() ? Double.NaN : Math.exp(values.stream().mapToDouble(v -> Math.log(Math.max(v, 1e-3))).average().orElse(0));
    }

    private static long memoryBytes() {
        return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                .getTotalMemorySize();
    }

    // ---- settings ----

    record Settings(Path queries, List<Endpoint> endpoints, int warmup, int runs, int timeoutSeconds, List<String> only, Path out,
            List<String> info) {
        static final String USAGE = """
                Usage: CompareBench --queries DIR --endpoint NAME=URL [--endpoint NAME=URL ...] [options]   (run it with compare.sh)
                  --queries DIR        the .rq files to run
                  --endpoint NAME=URL  an engine's SPARQL query URL; the first is the reference of the ratios
                  --clear NAME=URL     a URL to GET before every run of that engine, to empty its result cache
                  --warmup 3 --runs 10 runs per query and engine, before and while timing
                  --timeout 120        seconds per query run
                  --only a,b           run only these queries
                  --info TEXT          a line for the report's header (repeat it)
                  --out DIR            where reports go (default target/compare)
                """;

        static Settings parse(final String[] args) {
            Path queries = null;
            final Map<String, URI> endpoints = new LinkedHashMap<>();
            final Map<String, URI> clears = new LinkedHashMap<>();
            int warmup = 3;
            int runs = 10;
            int timeout = 120;
            List<String> only = List.of();
            Path out = Path.of("target", "compare");
            final List<String> info = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                final String a = args[i];
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + a);
                }
                final String v = args[++i];
                switch (a) {
                    case "--queries" -> queries = Path.of(v);
                    case "--endpoint" -> endpoints.put(name(v), URI.create(v.substring(v.indexOf('=') + 1)));
                    case "--clear" -> clears.put(name(v), URI.create(v.substring(v.indexOf('=') + 1)));
                    case "--warmup" -> warmup = Integer.parseInt(v);
                    case "--runs" -> runs = Math.max(1, Integer.parseInt(v));
                    case "--timeout" -> timeout = Integer.parseInt(v);
                    case "--only" -> only = List.of(v.split(","));
                    case "--info" -> info.add(v);
                    case "--out" -> out = Path.of(v);
                    default -> throw new IllegalArgumentException("unknown option " + a);
                }
            }
            if (queries == null) {
                throw new IllegalArgumentException("give the queries with --queries DIR");
            }
            if (endpoints.isEmpty()) {
                throw new IllegalArgumentException("give at least one --endpoint");
            }
            for (final String c : clears.keySet()) {
                if (!endpoints.containsKey(c)) {
                    throw new IllegalArgumentException("--clear names no endpoint: " + c);
                }
            }
            final List<Endpoint> list = endpoints.entrySet().stream()
                    .map(e -> new Endpoint(e.getKey(), e.getValue(), clears.get(e.getKey()))).toList();
            return new Settings(queries, list, warmup, runs, timeout, only, out, List.copyOf(info));
        }

        private static String name(final String v) {
            final int eq = v.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("takes NAME=URL (got '" + v + "')");
            }
            return v.substring(0, eq);
        }
    }
}
