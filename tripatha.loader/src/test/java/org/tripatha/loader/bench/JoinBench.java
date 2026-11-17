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
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.loader.Loader;
import org.tripatha.loader.LoaderOptions;
import org.tripatha.loader.Prepare;
import org.tripatha.loader.StoreCheck;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.loader.gen.GenConfig;
import org.tripatha.rdftriplestore.RyaSailFactory;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Store;
import org.tripatha.store.trace.StoreTrace;
import org.tripatha.store.trace.TraceEvent;
import org.tripatha.test.TestBackend;
import org.tripatha.test.TestBackends;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * The local join benchmark (SCALE-STUDY.md, M8): a fixed query suite over a generated store, on
 * the backend named by {@code -Dtripatha.test.backend}, without a cluster.
 *
 * <p>It loads the loader's dataset once (a RocksDB store named by
 * {@code -Dtripatha.test.rocksdbPath} is kept and reused), then runs every query a few times to
 * warm up and a few more to time it, and once more traced: the trace says how many scans the
 * query made, how many cells they returned and how long they spent in storage calls. Each timed
 * run also measures the CPU time of the thread that ran the query and of the whole JVM, so the
 * report says how much of a query's time was its own work and how much was waiting. With
 * {@code --profile} it also records where the CPU time went (CPU samples only). {@code --variant}
 * runs every query under several settings, alternating, on the same store. The report goes to the
 * console and to {@code --out} as Markdown and CSV; {@code --baseline FILE.csv} adds the change
 * against an earlier report. Run it with {@code tripatha.loader/bench.sh}.
 */
public final class JoinBench {
    private static final String PREFIX = "bench_";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final Settings settings;
    private final PrintStream out;

    private JoinBench(final Settings settings, final PrintStream out) {
        this.settings = settings;
        this.out = out;
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
        new JoinBench(settings, System.out).run();
        // The test clusters keep non-daemon threads.
        System.exit(0);
    }

    private void run() throws Exception {
        Files.createDirectories(settings.out);
        final TestBackend backend = TestBackends.current();
        final LoaderOptions dataset = LoaderOptions.parse(datasetArgs("load"), Map.of());
        final GenConfig gen = dataset.genConfig();
        out.printf(Locale.ROOT, "Join benchmark on %s: %,d entities (about %s triples), %d processors, %s.%n", backend.id(),
                gen.entities(), settings.triples, Runtime.getRuntime().availableProcessors(), Runtime.version());
        try (Store store = backend.openStore()) {
            ensureLoaded(backend, store, dataset);
            final List<Query> suite = suite(new EntityGenerator(gen));
            final Map<Variant, SailRepository> repos = new LinkedHashMap<>();
            try {
                for (final Variant v : settings.variants) {
                    final StoreRdfConfiguration conf = dataset.rdfConf();
                    conf.setInfer(false);
                    settings.conf.forEach(conf::set);
                    v.conf().forEach(conf::set);
                    final SailRepository repo = RyaSailFactory.createRepository(store, conf);
                    repo.init();
                    repos.put(v, repo);
                }
                final List<Result> results = new ArrayList<>();
                for (final Query q : suite) {
                    if (settings.only.isEmpty() || settings.only.contains(q.name)) {
                        results.addAll(measure(repos, q));
                    }
                }
                report(backend.id(), gen, results);
            } finally {
                repos.values().forEach(SailRepository::shutDown);
            }
        }
    }

    // ---- dataset ----

    private String[] datasetArgs(final String command) {
        final List<String> a = new ArrayList<>(List.of(command, "--table-prefix", PREFIX, "--triples", settings.triples,
                "--text-index", "--geo-index", "--threads", Integer.toString(settings.loadThreads), "--progress-file",
                progressFile().toString(), "--report-interval", "30s"));
        return a.toArray(new String[0]);
    }

    private Path progressFile() {
        return settings.out.resolve("load-" + TestBackends.id() + ".progress");
    }

    /**
     * A file saying that the store at this location holds the whole dataset, written after a load
     * and its compaction. A kept RocksDB store is reused when it holds the same dataset and the file
     * is there; a new test cluster holds nothing, so it is always loaded.
     */
    private Path loadedMarker(final TestBackend backend, final GenConfig gen) {
        final String where = backend.storeSettings().getProperty("rocksdb.path", "");
        return settings.out.resolve("loaded-" + backend.id() + "-" + gen.fingerprint().substring(0, 16) + "-"
                + Integer.toHexString(where.hashCode()));
    }

    /** Loads the dataset unless the store already holds all of it (a kept RocksDB store). */
    private void ensureLoaded(final TestBackend backend, final Store store, final LoaderOptions dataset) throws Exception {
        final StoreRdfConfiguration conf = dataset.rdfConf();
        final boolean exists = store.tables().exists(conf.getTableLayoutStrategy().getSpo());
        final String stored = exists ? StoreCheck.storedFingerprint(store, conf, dataset.genConfig()) : null;
        final Path marker = loadedMarker(backend, dataset.genConfig());
        if (stored != null && stored.equals(dataset.genConfig().fingerprint()) && Files.exists(marker)) {
            out.println("Store already loaded; reusing it.");
            return;
        }
        if (exists) {
            out.println("Store holds other or partial data: deleting its tables.");
            backend.deleteTables(PREFIX);
        }
        Files.deleteIfExists(marker);
        Files.deleteIfExists(progressFile());
        final long t0 = System.nanoTime();
        final PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        Prepare.run(store, LoaderOptions.parse(datasetArgs("prepare"), Map.of()), quiet);
        final Loader.Result loaded = new Loader(store, dataset, out).run();
        if (!loaded.complete()) {
            throw new IllegalStateException("the load did not complete", loaded.failure());
        }
        out.println("Compacting…");
        final TableLayoutStrategy t = conf.getTableLayoutStrategy();
        for (final String table : List.of(t.getSpo(), t.getPo(), t.getOsp(), t.getEval(), t.getGraphs(), t.getNs(), t.getText(),
                t.getGeo())) {
            if (store.tables().exists(table)) {
                backend.compact(table);
            }
        }
        Files.writeString(marker, dataset.genConfig().fingerprint() + "\n", StandardCharsets.UTF_8);
        out.printf(Locale.ROOT, "Loaded and compacted in %.0f s.%n", (System.nanoTime() - t0) / 1e9);
    }

    // ---- the suite ----

    /** A named query; the text is completed with the standard prefixes. */
    record Query(String name, String purpose, String sparql) {
    }

    /** The suite, with constants picked from the generated data so that every query finds something. */
    private static List<Query> suite(final EntityGenerator generator) {
        final Constants c = Constants.pick(generator, 200_000);
        final List<Query> q = new ArrayList<>();
        q.add(new Query("lookup", "one subject, every statement: one range",
                "SELECT ?p ?o WHERE { <" + c.popularPerson + "> ?p ?o }"));
        q.add(new Query("star-in", "a bound object, then two attributes per row (entity prefetch)",
                "SELECT ?p ?n ?org WHERE { ?p o:knows <" + c.popularPerson + "> . ?p rdfs:label ?n . ?p o:worksFor ?org }"));
        q.add(new Query("star-optional", "attributes with OPTIONALs (entity prefetch)",
                "SELECT ?p ?n ?e ?b WHERE { ?p o:worksFor <" + c.popularEmployer + "> ; rdfs:label ?n "
                        + "OPTIONAL { ?p o:email ?e } OPTIONAL { ?p o:birthDate ?b } }"));
        q.add(new Query("chain", "two hops of incoming links, then attributes (batched joins)",
                "SELECT ?r ?prod ?x ?n WHERE { ?prod o:manufacturer <" + c.popularManufacturer + "> . ?r o:reviewOf ?prod . "
                        + "?r o:rating ?x . ?prod rdfs:label ?n }"));
        q.add(new Query("chain-limit", "an unanchored two-hop join, first 10 rows only",
                "SELECT ?a ?b ?org WHERE { ?a o:knows ?b . ?b o:worksFor ?org } LIMIT 10"));
        q.add(new Query("type-limit", "a class, two hops, first 100 rows only",
                "SELECT ?p ?c ?pop WHERE { ?p a o:Person ; o:livesIn ?c . ?c o:population ?pop } LIMIT 100"));
        q.add(new Query("range-number", "a numeric comparison on a predicate's values (xsd:long)",
                "SELECT ?c ?pop WHERE { ?c o:population ?pop FILTER(?pop > " + c.populationThreshold + ") }"));
        q.add(new Query("range-date", "a one-hour window of xsd:dateTime values",
                "SELECT ?obs ?t WHERE { ?obs o:resultTime ?t FILTER(?t >= \"" + c.windowStart
                        + "\"^^xsd:dateTime && ?t < \"" + c.windowEnd + "\"^^xsd:dateTime) }"));
        q.add(new Query("range-double", "a comparison on xsd:double values",
                "SELECT ?obs ?v WHERE { ?obs o:hasResult ?v FILTER(?v > 60) }"));
        q.add(new Query("prefix", "STRSTARTS on a predicate's string values",
                "SELECT ?p ?s WHERE { ?p o:sku ?s FILTER(STRSTARTS(?s, \"" + c.skuPrefix + "\")) }"));
        q.add(new Query("text", "a text search (text index)",
                "SELECT ?d ?l WHERE { ?d rdfs:label ?l FILTER(text:matches(?l, \"" + c.word + "\")) }"));
        q.add(new Query("geo", "points within a box (geo index)",
                "SELECT ?g ?w WHERE { ?g geo:asWKT ?w FILTER(geof:sfWithin(?w, \"" + c.box + "\"^^geo:wktLiteral)) }"));
        q.add(new Query("count-class", "the size of a class, counted",
                "SELECT (COUNT(*) AS ?n) WHERE { ?s a o:Sensor }"));
        // M10: "all entities matching X" (SCALE-STUDY.md D1), stars whose objects are constants.
        final String person = "?p o:worksFor <" + c.popularEmployer + "> ; o:nationality <" + c.topCountry + "> ";
        final String product = "?x a o:Product ; o:category <" + c.topCategory + "> ; o:inStock true ";
        q.add(new Query("class-2", "people of one nationality working for one employer: two constant lists",
                "SELECT ?p WHERE { " + person + "}"));
        q.add(new Query("class-3", "the same, of the class Person: one of the three lists is the whole class",
                "SELECT ?p WHERE { " + person + "; a o:Person }"));
        q.add(new Query("class-attrs", "class-3, with a label and an optional birth date per match",
                "SELECT ?p ?n ?b WHERE { " + person + "; a o:Person ; rdfs:label ?n OPTIONAL { ?p o:birthDate ?b } }"));
        q.add(new Query("class-large", "products of one category in stock: three long lists, a large answer",
                "SELECT ?x WHERE { " + product + "}"));
        q.add(new Query("class-count", "class-large, counted",
                "SELECT (COUNT(*) AS ?n) WHERE { " + product + "}"));
        q.add(new Query("class-value", "observations of one property above a value (xsd:double)",
                "SELECT ?obs ?v WHERE { ?obs a o:Observation ; o:observedProperty <" + c.topProperty
                        + "> ; o:hasResult ?v FILTER(?v > 60) }"));
        q.add(new Query("class-value-int", "companies of one industry with at least 100 employees (xsd:integer)",
                "SELECT ?co ?n WHERE { ?co a o:Company ; o:industry <" + c.topIndustry
                        + "> ; o:numberOfEmployees ?n FILTER(?n >= 100) }"));
        // The value condition keeps nine values in ten: a value range is the wrong driver here,
        // and its fixed estimate (a tenth of the predicate) says otherwise. O14's losing side.
        q.add(new Query("class-value-wide", "class-value with a condition most results pass (?v > 0)",
                "SELECT ?obs ?v WHERE { ?obs a o:Observation ; o:observedProperty <" + c.topProperty
                        + "> ; o:hasResult ?v FILTER(?v > 0) }"));
        final String prefixes = "PREFIX o: <" + generator.config().ontology("") + ">\n"
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n"
                + "PREFIX text: <urn:tripatha:text#>\n"
                + "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
                + "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n";
        return q.stream().map(x -> new Query(x.name, x.purpose, prefixes + x.sparql)).toList();
    }

    /** Constants for the suite, from the first entities of the dataset. */
    private record Constants(String popularPerson, String popularEmployer, String popularManufacturer,
            long populationThreshold, String windowStart, String windowEnd, String skuPrefix, String word, String box,
            String topCountry, String topCategory, String topProperty, String topIndustry) {

        static Constants pick(final EntityGenerator generator, final long sample) {
            final GenConfig c = generator.config();
            final Map<String, Integer> knows = new HashMap<>();
            final Map<String, Integer> employers = new HashMap<>();
            final Map<String, Integer> manufacturers = new HashMap<>();
            final Map<String, Integer> countries = new HashMap<>();
            final Map<String, Integer> categories = new HashMap<>();
            final Map<String, Integer> properties = new HashMap<>();
            final Map<String, Integer> industries = new HashMap<>();
            final List<Long> populations = new ArrayList<>();
            final List<String> skus = new ArrayList<>();
            Instant time = null;
            String word = null;
            String point = null;
            for (long e = 0; e < Math.min(sample, c.entities()); e++) {
                for (final RyaStatement st : generator.generate(e)) {
                    final String p = st.getPredicate().getData();
                    final String v = st.getObject().getData();
                    if (p.equals(c.ontology("knows"))) {
                        knows.merge(v, 1, Integer::sum);
                    } else if (p.equals(c.ontology("worksFor"))) {
                        employers.merge(v, 1, Integer::sum);
                    } else if (p.equals(c.ontology("manufacturer"))) {
                        manufacturers.merge(v, 1, Integer::sum);
                    } else if (p.equals(c.ontology("nationality"))) {
                        countries.merge(v, 1, Integer::sum);
                    } else if (p.equals(c.ontology("category"))) {
                        categories.merge(v, 1, Integer::sum);
                    } else if (p.equals(c.ontology("observedProperty"))) {
                        properties.merge(v, 1, Integer::sum);
                    } else if (p.equals(c.ontology("industry"))) {
                        industries.merge(v, 1, Integer::sum);
                    } else if (p.equals(c.ontology("population"))) {
                        populations.add(Long.parseLong(v));
                    } else if (p.equals(c.ontology("sku"))) {
                        skus.add(v);
                    } else if (time == null && p.equals(c.ontology("resultTime"))) {
                        time = Instant.parse(v);
                    } else if (word == null && p.equals(c.ontology("givenName")) && v.matches("[A-Za-z]{5,}")) {
                        word = v.toLowerCase(Locale.ROOT);
                    }
                    if (point == null && v.startsWith("POINT")) {
                        point = v;
                    }
                }
            }
            if (time == null || word == null || point == null || populations.isEmpty() || skus.isEmpty()) {
                throw new IllegalStateException("the dataset is too small for the suite");
            }
            populations.sort(null);
            final Instant start = time.truncatedTo(ChronoUnit.HOURS);
            final String[] lonLat = point.substring(point.indexOf('(') + 1, point.indexOf(')')).trim().split("\\s+");
            final double lon = Double.parseDouble(lonLat[0]);
            final double lat = Double.parseDouble(lonLat[1]);
            final String box = String.format(Locale.ROOT, "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))", lon - 1, lat - 1, lon + 1,
                    lat - 1, lon + 1, lat + 1, lon - 1, lat + 1, lon - 1, lat - 1);
            return new Constants(top(knows), top(employers), top(manufacturers),
                    populations.get((int) (populations.size() * 0.99)), start.toString(), start.plus(Duration.ofHours(1)).toString(),
                    skuPrefix(skus), word, box, top(countries), top(categories), top(properties), top(industries));
        }

        private static String top(final Map<String, Integer> counts) {
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        }

        /** The shortest prefix of the first SKU shared by at most 0.5 % of the sample's SKUs. */
        private static String skuPrefix(final List<String> skus) {
            final String first = skus.get(0);
            for (int len = "SKU-".length() + 1; len < first.length(); len++) {
                final String prefix = first.substring(0, len);
                if (skus.stream().filter(s -> s.startsWith(prefix)).count() <= Math.max(2, skus.size() / 200)) {
                    return prefix;
                }
            }
            return first;
        }
    }

    // ---- measuring ----

    /** A named set of store settings the suite is run under, on top of {@code --conf}. */
    record Variant(String name, Map<String, String> conf) {
    }

    /**
     * One query under one variant: rows, and per timed run the wall time, the CPU time of the thread
     * that ran the query, and the CPU time of the whole JVM.
     */
    record Result(Query query, Variant variant, long rows, boolean rowsDiffer, double[] ms, double[] cpuMs, double[] jvmCpuMs,
            Traced traced, Profile profile) {
        double p50() {
            return percentile(ms, 0.5);
        }

        double p90() {
            return percentile(ms, 0.9);
        }

        double cpuP50() {
            return percentile(cpuMs, 0.5);
        }

        double jvmCpuP50() {
            return percentile(jvmCpuMs, 0.5);
        }

        private static double percentile(final double[] values, final double p) {
            final double[] sorted = values.clone();
            Arrays.sort(sorted);
            return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
        }
    }

    /** What one traced run did in storage. */
    record Traced(int scans, long ranges, long cells, double storageMs, double wallMs) {
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /**
     * Runs a query under every variant: the warm-up runs, then the timed runs alternating between
     * the variants (so drift in the machine's state affects them alike), then the profile and the
     * traced run of each.
     */
    private List<Result> measure(final Map<Variant, SailRepository> repos, final Query q) throws Exception {
        final List<Variant> variants = new ArrayList<>(repos.keySet());
        final int n = variants.size();
        final long[] rows = new long[n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < settings.warmup; i++) {
                rows[j] = execute(repos.get(variants.get(j)), q);
            }
        }
        final double[][] ms = new double[n][settings.runs];
        final double[][] cpu = new double[n][settings.runs];
        final double[][] jvm = new double[n][settings.runs];
        for (int i = 0; i < settings.runs; i++) {
            for (int j = 0; j < n; j++) {
                final long c0 = THREADS.getCurrentThreadCpuTime();
                final long p0 = OS.getProcessCpuTime();
                final long t0 = System.nanoTime();
                rows[j] = execute(repos.get(variants.get(j)), q);
                ms[j][i] = (System.nanoTime() - t0) / 1e6;
                cpu[j][i] = (THREADS.getCurrentThreadCpuTime() - c0) / 1e6;
                jvm[j][i] = (OS.getProcessCpuTime() - p0) / 1e6;
            }
        }
        final List<Result> results = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            final SailRepository repo = repos.get(variants.get(j));
            Profile profile = null;
            if (settings.profile) {
                final Recording recording = startRecording();
                try {
                    for (int i = 0; i < settings.runs; i++) {
                        execute(repo, q);
                    }
                } finally {
                    recording.stop();
                }
                profile = Profile.of(recording, settings.out.resolve(q.name + "-" + j + ".jfr"));
            }
            final Traced traced = traced(repo, q);
            final Result r = new Result(q, variants.get(j), rows[j], rows[j] != rows[0], ms[j], cpu[j], jvm[j], traced, profile);
            results.add(r);
            out.printf(Locale.ROOT, "%-14s %-10s %,8d rows%s  p50 %9.1f ms  query CPU %8.1f ms  JVM CPU %8.1f ms  %,7d ranges  %,10d cells%n",
                    q.name, n == 1 ? "" : variants.get(j).name(), rows[j], r.rowsDiffer ? " (DIFFERENT)" : "", r.p50(), r.cpuP50(),
                    r.jvmCpuP50(), traced.ranges, traced.cells);
        }
        return results;
    }

    private long execute(final SailRepository repo, final Query q) {
        try (RepositoryConnection conn = repo.getConnection()) {
            final TupleQuery tq = conn.prepareTupleQuery(q.sparql);
            tq.setMaxExecutionTime(settings.timeoutSeconds);
            long n = 0;
            try (TupleQueryResult result = tq.evaluate()) {
                while (result.hasNext()) {
                    result.next();
                    n++;
                }
            }
            return n;
        }
    }

    /** One more run, traced: a traced query runs exactly like an untraced one (QueryTraceIT). */
    private Traced traced(final SailRepository repo, final Query q) {
        final AtomicLong scans = new AtomicLong();
        final AtomicLong ranges = new AtomicLong();
        final AtomicLong cells = new AtomicLong();
        final double[] storageMs = new double[1];
        final long t0 = System.nanoTime();
        try (StoreTrace.Scope scope = StoreTrace.start(event -> {
            if (event instanceof TraceEvent.ScanStart s) {
                scans.incrementAndGet();
                ranges.addAndGet(s.rangeCount());
            } else if (event instanceof TraceEvent.ScanEnd e) {
                cells.addAndGet(Math.max(0, e.entries()));
                synchronized (storageMs) {
                    storageMs[0] += e.ms();
                }
            }
        })) {
            execute(repo, q);
        } catch (final Exception e) {
            throw new IllegalStateException(q.name + ": " + e.getMessage(), e);
        }
        final double wall = (System.nanoTime() - t0) / 1e6;
        synchronized (storageMs) {
            return new Traced((int) scans.get(), ranges.get(), cells.get(), storageMs[0], wall);
        }
    }

    // ---- profiling ----

    private static Recording startRecording() {
        final Recording r = new Recording();
        // CPU samples only: threads waiting on the network or a lock are not sampled.
        r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
        r.start();
        return r;
    }

    /** Where the CPU time of the profiled runs went: by kind of code, and the busiest methods. */
    record Profile(int samples, Map<String, Integer> byArea, List<Map.Entry<String, Integer>> topOwn,
            List<Map.Entry<String, Integer>> topSelf) {

        static Profile of(final Recording recording, final Path file) throws IOException {
            recording.dump(file);
            recording.close();
            final Map<String, Integer> areas = new LinkedHashMap<>();
            final Map<String, Integer> own = new HashMap<>();
            final Map<String, Integer> self = new HashMap<>();
            int samples = 0;
            for (final RecordedEvent e : RecordingFile.readAllEvents(file)) {
                if (!e.getEventType().getName().equals("jdk.ExecutionSample") || e.getStackTrace() == null
                        || e.getStackTrace().getFrames().isEmpty()) {
                    continue;
                }
                final var thread = e.getThread("sampledThread");
                if (thread != null && thread.getJavaName() != null && thread.getJavaName().startsWith("JFR")) {
                    continue;
                }
                final List<RecordedFrame> frames = e.getStackTrace().getFrames();
                samples++;
                self.merge(method(frames.get(0)), 1, Integer::sum);
                final RecordedFrame first = firstOwn(frames);
                areas.merge(area(frames, first), 1, Integer::sum);
                if (first != null) {
                    own.merge(method(first), 1, Integer::sum);
                }
            }
            Files.deleteIfExists(file);
            return new Profile(samples, areas, top(own, 8), top(self, 8));
        }

        private static List<Map.Entry<String, Integer>> top(final Map<String, Integer> counts, final int n) {
            return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(n).toList();
        }

        private static String method(final RecordedFrame f) {
            final String type = f.getMethod().getType().getName();
            return type.substring(type.lastIndexOf('.') + 1) + "." + f.getMethod().getName();
        }

        /** The innermost frame that isn't the JDK or a general-purpose library: who asked for the work. */
        private static RecordedFrame firstOwn(final List<RecordedFrame> frames) {
            for (final RecordedFrame f : frames) {
                final String t = f.getMethod().getType().getName();
                if (!(t.startsWith("java.") || t.startsWith("javax.") || t.startsWith("jdk.") || t.startsWith("sun.")
                        || t.startsWith("com.sun.") || t.startsWith("com.google.common.") || t.startsWith("org.apache.commons."))) {
                    return f;
                }
            }
            return null;
        }

        private static String area(final List<RecordedFrame> frames, final RecordedFrame first) {
            for (final RecordedFrame f : frames) {
                final String t = f.getMethod().getType().getName();
                if (t.startsWith("org.apache.hadoop.hbase.regionserver") || t.startsWith("org.apache.accumulo.tserver")) {
                    return "storage server (in this JVM)";
                }
            }
            if (first == null) {
                return "JDK";
            }
            final String t = first.getMethod().getType().getName();
            if (t.startsWith("org.rocksdb.")) {
                return "storage: RocksDB";
            }
            if (t.startsWith("org.apache.accumulo.") || t.startsWith("org.apache.hadoop.hbase.") || t.startsWith("org.apache.thrift.")
                    || t.startsWith("org.apache.hadoop.")) {
                return "storage: client";
            }
            if (t.startsWith("org.tripatha.rocksdb.") || t.startsWith("org.tripatha.accumulo.") || t.startsWith("org.tripatha.hbase.")) {
                return "Tripatha: backend";
            }
            if (t.startsWith("org.tripatha.store.") || t.startsWith("org.tripatha.api.")) {
                return "Tripatha: store, decoding";
            }
            if (t.startsWith("org.tripatha.")) {
                return "Tripatha: query";
            }
            if (t.startsWith("org.eclipse.rdf4j.")) {
                return "RDF4J";
            }
            return "other";
        }
    }

    // ---- reporting ----

    private void report(final String backend, final GenConfig gen, final List<Result> results) throws IOException {
        final Map<String, Double> baseline = settings.baseline == null ? Map.of() : readBaseline(settings.baseline);
        final boolean variants = settings.variants.size() > 1;
        final Variant first = settings.variants.get(0);
        final StringBuilder md = new StringBuilder();
        md.append("# Join benchmark, ").append(backend).append("\n\n");
        md.append(String.format(Locale.ROOT, "%s, %,d entities (about %s triples), %d warm-up and %d timed runs per query, "
                + "%d processors, Java %s.%s%n", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), gen.entities(), settings.triples,
                settings.warmup, settings.runs, Runtime.getRuntime().availableProcessors(), Runtime.version(),
                settings.conf.isEmpty() ? "" : " Settings: " + settings.conf + "."));
        if (variants) {
            md.append("\nVariants, timed alternately: ").append(settings.variants.stream()
                    .map(v -> "`" + v.name() + "` " + (v.conf().isEmpty() ? "(no change)" : v.conf().toString()))
                    .collect(Collectors.joining(", "))).append(".\n");
        }
        md.append('\n');
        md.append("| Query |").append(variants ? " Variant |" : " What it exercises |").append(" Rows | p50 ms | p90 ms |")
                .append(variants ? " vs `" + first.name() + "` |" : "").append(baseline.isEmpty() ? "" : " p50 vs baseline |")
                .append(" Query CPU ms | JVM CPU ms | Scans | Ranges | Cells | Storage % |\n");
        md.append("|---|---|---|---|---|").append(variants ? "---|" : "").append(baseline.isEmpty() ? "" : "---|")
                .append("---|---|---|---|---|---|\n");
        final StringBuilder csv = new StringBuilder("query,variant,rows,p50_ms,p90_ms,cpu_ms,jvm_cpu_ms,scans,ranges,cells,storage_ms,wall_ms\n");
        final Map<String, Double> firstP50 = new HashMap<>();
        for (final Result r : results) {
            if (r.variant.equals(first)) {
                firstP50.put(r.query.name, r.p50());
            }
        }
        for (final Result r : results) {
            final Traced t = r.traced;
            md.append(String.format(Locale.ROOT, "| %s | %s | %,d%s | %.1f | %.1f |", r.query.name,
                    variants ? r.variant.name() : r.query.purpose, r.rows, r.rowsDiffer ? " ≠" : "", r.p50(), r.p90()));
            if (variants) {
                final Double f = firstP50.get(r.query.name);
                md.append(r.variant.equals(first) || f == null ? " — |" : String.format(Locale.ROOT, " %+.0f %% |", 100 * (r.p50() - f) / f));
            }
            if (!baseline.isEmpty()) {
                Double b = baseline.get(r.query.name + "|" + r.variant.name());
                if (b == null) {
                    b = baseline.get(r.query.name);
                }
                md.append(b == null ? " — |" : String.format(Locale.ROOT, " %+.0f %% |", 100 * (r.p50() - b) / b));
            }
            md.append(String.format(Locale.ROOT, " %.1f | %.1f | %,d | %,d | %,d | %.0f |%n", r.cpuP50(), r.jvmCpuP50(), t.scans, t.ranges,
                    t.cells, t.wallMs == 0 ? 0 : Math.min(100, 100 * t.storageMs / t.wallMs)));
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%.3f,%.3f%n", r.query.name, r.variant.name(), r.rows,
                    r.p50(), r.p90(), r.cpuP50(), r.jvmCpuP50(), t.scans, t.ranges, t.cells, t.storageMs, t.wallMs));
        }
        md.append("""

                - **Query CPU** is the CPU time of the thread that ran the query (median of the timed runs). It waited for the rest \
                of its wall time: on the store, or on scanner threads.
                - **JVM CPU** is the CPU time of the whole JVM over the same runs: scanner threads, and a store running in this \
                JVM (RocksDB, the HBase test cluster). The Accumulo test cluster's tablet server runs in another process, so its \
                work is not in it.
                - **Storage %** is the time the traced run spent in storage calls, summed over its scans, against its wall time. \
                It includes the client's work on every cell returned. Concurrent scans can add up to more than the wall time, so it \
                is capped at 100.
                """);
        if (results.stream().anyMatch(r -> r.rowsDiffer)) {
            md.append("- **≠**: this variant returned a different number of rows than the first. That is a bug in the variant.\n");
        }
        if (results.stream().anyMatch(r -> r.profile != null)) {
            md.append("\n## Where the CPU time goes\n\nCPU samples of extra runs (threads waiting on the network or a lock are not "
                    + "sampled), by the innermost frame that isn't the JDK (so a `HashMap.get` counts for the code that called it).\n");
            for (final Result r : results) {
                final Profile p = r.profile;
                if (p == null || p.samples == 0) {
                    continue;
                }
                md.append("\n### ").append(r.query.name).append(variants ? " (" + r.variant.name() + ")" : "").append(", ")
                        .append(p.samples).append(" samples\n\n");
                md.append(p.byArea.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .map(e -> String.format(Locale.ROOT, "- %s: %.0f %%", e.getKey(), 100.0 * e.getValue() / p.samples))
                        .collect(Collectors.joining("\n")));
                md.append("\n\nBusiest own frames: ").append(p.topOwn.stream()
                        .map(e -> String.format(Locale.ROOT, "`%s` %.0f %%", e.getKey(), 100.0 * e.getValue() / p.samples))
                        .collect(Collectors.joining(", ")));
                md.append("\n\nBusiest methods: ").append(p.topSelf.stream()
                        .map(e -> String.format(Locale.ROOT, "`%s` %.0f %%", e.getKey(), 100.0 * e.getValue() / p.samples))
                        .collect(Collectors.joining(", "))).append('\n');
            }
        }
        final String name = backend + "-" + LocalDateTime.now().format(STAMP);
        final Path mdFile = settings.out.resolve(name + ".md");
        Files.writeString(mdFile, md, StandardCharsets.UTF_8);
        Files.writeString(settings.out.resolve(name + ".csv"), csv, StandardCharsets.UTF_8);
        out.println();
        out.println(md);
        out.println("Report: " + mdFile.toAbsolutePath() + " (and .csv; pass it as --baseline to compare a later run)");
    }

    /** p50 by query, and by query and variant when the report has variants (older reports have none). */
    private static Map<String, Double> readBaseline(final Path csv) throws IOException {
        final Map<String, Double> m = new HashMap<>();
        final List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        final boolean withVariant = lines.get(0).startsWith("query,variant,");
        for (final String line : lines.subList(1, lines.size())) {
            final String[] f = line.split(",");
            if (withVariant) {
                m.put(f[0] + "|" + f[1], Double.parseDouble(f[3]));
                m.putIfAbsent(f[0], Double.parseDouble(f[3]));
            } else {
                m.put(f[0], Double.parseDouble(f[2]));
            }
        }
        return m;
    }

    // ---- settings ----

    record Settings(String triples, int warmup, int runs, int timeoutSeconds, int loadThreads, boolean profile, Path out,
            Path baseline, List<String> only, Map<String, String> conf, List<Variant> variants) {
        static final String USAGE = """
                Usage: bench.sh [--backend rocksdb|accumulo|hbase] [options]
                  --triples 10M        dataset size (the loader's generator, both indexes on)
                  --warmup 3 --runs 10 runs per query, before and while timing
                  --timeout 120        seconds per query run
                  --profile            record where the CPU time of the timed runs goes (JFR)
                  --only a,b           run only these queries
                  --conf key=value     a store configuration setting, for example query.join.batchsize=100
                  --variant NAME:k=v,k=v  run every query under this variant too, alternating with the others on the
                                       same store (repeat it; the first is the reference; NAME: alone changes nothing)
                  --baseline FILE.csv  compare the p50 latencies with an earlier report
                  --out DIR            where reports go (default tripatha.loader/target/bench)
                  --load-threads 8     writer threads for the one-time load
                """;

        static Settings parse(final String[] args) {
            String triples = "10M";
            int warmup = 3;
            int runs = 10;
            int timeout = 120;
            int loadThreads = 8;
            boolean profile = false;
            Path out = Path.of("target", "bench");
            Path baseline = null;
            List<String> only = List.of();
            final Map<String, String> conf = new LinkedHashMap<>();
            final List<Variant> variants = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                final String a = args[i];
                if (a.equals("--profile")) {
                    profile = true;
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + a);
                }
                final String v = args[++i];
                switch (a) {
                    case "--triples" -> triples = v;
                    case "--warmup" -> warmup = Integer.parseInt(v);
                    case "--runs" -> runs = Math.max(1, Integer.parseInt(v));
                    case "--timeout" -> timeout = Integer.parseInt(v);
                    case "--load-threads" -> loadThreads = Integer.parseInt(v);
                    case "--out" -> out = Path.of(v);
                    case "--baseline" -> baseline = Path.of(v);
                    case "--only" -> only = List.of(v.split(","));
                    case "--conf" -> {
                        final int eq = v.indexOf('=');
                        if (eq <= 0) {
                            throw new IllegalArgumentException("--conf takes key=value (got '" + v + "')");
                        }
                        conf.put(v.substring(0, eq), v.substring(eq + 1));
                    }
                    case "--variant" -> variants.add(variant(v));
                    default -> throw new IllegalArgumentException("unknown option " + a);
                }
            }
            if (variants.isEmpty()) {
                variants.add(new Variant("default", Map.of()));
            }
            return new Settings(triples, warmup, runs, timeout, loadThreads, profile, out, baseline, only, conf, List.copyOf(variants));
        }

        private static Variant variant(final String v) {
            final int colon = v.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("--variant takes NAME:key=value,key=value (got '" + v + "')");
            }
            final Map<String, String> conf = new LinkedHashMap<>();
            for (final String kv : v.substring(colon + 1).split(",")) {
                if (kv.isBlank()) {
                    continue;
                }
                final int eq = kv.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("--variant takes NAME:key=value,key=value (got '" + v + "')");
                }
                conf.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
            return new Variant(v.substring(0, colon), conf);
        }
    }
}
