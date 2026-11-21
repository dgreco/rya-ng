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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tripatha.api.RdfCloudTripleStoreConstants;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaSchema;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.loader.LoaderMain;
import org.tripatha.loader.LoaderOptions;
import org.tripatha.loader.StoreCheck;
import org.tripatha.store.EvalStatsMutations;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.test.StoreITBase;
import org.tripatha.test.accumulo.MiniAccumuloSingleton;

/**
 * The Spark build on local Spark and the test cluster: for the generator it writes every cell
 * the loader's own build writes; for RDF files every cell a DAO load of the same statements
 * writes, blank nodes read across splits, graphs, triple terms, labels and bad lines included.
 */
class SparkBuildIT extends StoreITBase {
    private static final String[] AUTHS = {"A", "B"};
    private static JavaSparkContext sc;

    @TempDir
    Path dir;

    @BeforeAll
    static void startSpark() {
        sc = new JavaSparkContext(SparkBuild.configure(new SparkConf().setMaster("local[3]").setAppName("SparkBuildIT")
                .set("spark.ui.enabled", "false").set("spark.default.parallelism", "3")));
    }

    @AfterAll
    static void stopSpark() {
        if (sc != null) {
            sc.close();
        }
    }

    @BeforeEach
    void accumuloOnly() {
        assumeBackend("accumulo");
        grantAuthorizations(AUTHS);
    }

    private static Map<String, String> env() {
        final var cluster = MiniAccumuloSingleton.getCluster();
        return Map.of("TRIPATHA_ACCUMULO_ZOOKEEPERS", cluster.getZooKeepers(), "TRIPATHA_ACCUMULO_INSTANCE", cluster.getInstanceName(),
                "TRIPATHA_ACCUMULO_USER", MiniAccumuloSingleton.ROOT_USER, "TRIPATHA_ACCUMULO_PASSWORD",
                MiniAccumuloSingleton.ROOT_PASSWORD);
    }

    /** Runs the Spark build on the test's store; returns what it printed. */
    private String spark(final String... args) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SparkBuild(sc, getStore(), SparkBuild.Options.parse(args, env()), new PrintStream(out, true, StandardCharsets.UTF_8)).run();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String loader(final String command, final List<String> args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final String[] all = Stream.concat(Stream.of(command), args.stream()).toArray(String[]::new);
        final int code = LoaderMain.run(all, env(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        final String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, text);
        return text;
    }

    // ------------------------------------------------------------------ the generator

    private List<String> dataset(final String prefix, final String... extra) {
        final List<String> a = new ArrayList<>(List.of("--table-prefix", prefix, "--entities", "2000", "--seed", "7", "--labels", "A,B",
                "--labelled-fraction", "0.3", "--graphs", "4", "--rdf12-fraction", "0.3", "--text-index", "--geo-index",
                "--entity-index", "--entity-shards", "4", "--block-size", "25"));
        a.addAll(List.of(extra));
        return a;
    }

    @Test
    void generateWritesWhatTheLoadersBuildWrites() throws Exception {
        final String built = getTablePrefix() + "b_";
        final String sparked = getTablePrefix();
        for (final String prefix : List.of(built, sparked)) {
            loader("prepare", dataset(prefix, "--splits", "7"));
        }
        loader("build", dataset(built, "--threads", "4", "--work-dir", dir.resolve("build").toString()));

        final List<String> spark = new ArrayList<>(List.of("generate"));
        spark.addAll(dataset(sparked, "--work-dir", dir.resolve("spark").toString(), "--file-size", "0.05", "--tasks", "5"));
        final String out = spark(spark.toArray(new String[0]));
        assertTrue(out.contains("Import: "), out);

        for (final String table : List.of("spo", "po", "osp", "text", "geo", "entity")) {
            assertSame(table, cells(built + table, true), cells(sparked + table, true));
        }
        for (final String table : List.of("eval", "textcount", "graphs")) {
            assertSame(table, cells(built + table, false), cells(sparked + table, false));
        }
        // Several files per tablet, and the entity index's shard rows spread over files.
        for (final String table : List.of("spo", "entity")) {
            final String[] plan = out.lines().filter(l -> l.trim().startsWith(sparked + table + " ")).findFirst().orElseThrow().trim()
                    .split("\\s+");
            assertTrue(Integer.parseInt(plan[4]) > Integer.parseInt(plan[3]), table + ": " + String.join(" ", plan));
        }

        // Done: importing again imports nothing, and writing again is refused (it would count twice).
        spark.addAll(List.of("--phase", "import"));
        assertTrue(spark(spark.toArray(new String[0])).contains("Import: done already."));
        final List<String> write = new ArrayList<>(spark.subList(0, spark.size() - 2));
        write.addAll(List.of("--phase", "write"));
        assertThrows(StoreCheck.Mismatch.class, () -> spark(write.toArray(new String[0])));
    }

    // ------------------------------------------------------------------ files

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    /** Two files: blank nodes at both ends of one, the same label in the other, graphs, RDF 1.2, a repeat, a bad line. */
    private List<Path> rdfFiles() throws Exception {
        final StringBuilder a = new StringBuilder();
        a.append("_:b1 <urn:name> \"blank one\" .\n");
        a.append("<urn:s1> <urn:p> \"hello world\"@en <urn:g1> .\n");
        a.append("<urn:s1> <urn:age> \"42\"^^<").append(XSD).append("integer> .\n");
        a.append("<urn:s2> <urn:knows> _:b1 <urn:g2> .\n");
        a.append("# a comment\n\n");
        a.append("<urn:s2> <urn:when> \"2026-11-21T10:00:00Z\"^^<").append(XSD).append("dateTime> .\n");
        a.append("<urn:s3> <urn:says> <<( <urn:s1> <urn:p> \"x\" )>> .\n");
        a.append("<urn:s3> <urn:says> <<( _:b2 <urn:p> \"y\" )>> <urn:g1> .\n");
        a.append("<urn:s3> <urn:label> \"marhaban\"@ar--rtl .\n");
        a.append("this is not rdf\n");
        for (int i = 0; i < 400; i++) {
            a.append("<urn:e").append(i).append("> <urn:p").append(i % 5).append("> \"text number ").append(i % 37).append(" of many\"")
                    .append(i % 3 == 0 ? "" : " <urn:g" + i % 3 + ">").append(" .\n");
            a.append("<urn:e").append(i).append("> <urn:n> \"").append(i).append("\"^^<").append(XSD).append("int> .\n");
        }
        a.append("<urn:s1> <urn:p> \"hello world\"@en <urn:g1> .\n");
        a.append("_:b1 <urn:knows> <urn:s1> .\n");
        final Path f1 = dir.resolve("in/one.nq");
        Files.createDirectories(f1.getParent());
        Files.writeString(f1, a.toString(), StandardCharsets.UTF_8);
        final Path f2 = dir.resolve("in/sub/two.nt");
        Files.createDirectories(f2.getParent());
        Files.writeString(f2, "_:b1 <urn:name> \"blank one of file two\" .\n<urn:s1> <urn:p> \"other\" .\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("in/README.txt"), "not RDF", StandardCharsets.UTF_8);
        return List.of(f1, f2);
    }

    private String[] files(final String prefix, final String work, final String... extra) {
        final List<String> a = new ArrayList<>(List.of("files", "--table-prefix", prefix, "--input", dir.resolve("in").toString(),
                "--work-dir", dir.resolve(work).toString(), "--visibility", "A", "--text-index", "--input-splits", "8",
                "--file-size", "0.01"));
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    /** The statements a DAO load of the files writes, read whole with the job's timestamp and label. */
    private List<RyaStatement> statements(final List<Path> paths, final long jobTime) throws Exception {
        final List<RyaStatement> out = new ArrayList<>();
        for (final Path p : paths) {
            final org.apache.hadoop.fs.Path hp = new org.apache.hadoop.fs.Path(p.toUri());
            final FileStatus status = hp.getFileSystem(new Configuration()).globStatus(hp)[0];
            final RdfLines lines = new RdfLines(RdfLines.fileId(status), jobTime, "A".getBytes(StandardCharsets.UTF_8));
            for (final String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                try {
                    lines.parse(line, out);
                } catch (final RuntimeException e) {
                    assertEquals("this is not rdf", line);
                }
            }
        }
        return out;
    }

    private void daoLoad(final String prefix, final List<RyaStatement> statements) {
        final StoreRdfConfiguration conf = LoaderOptions.parse(new String[] {"build", "--table-prefix", prefix, "--text-index"}, env())
                .rdfConf();
        final RyaStoreDAO dao = new RyaStoreDAO(getStore(), conf);
        dao.init();
        try {
            dao.add(statements.iterator(), EvalStatsMutations.CounterPolicy.ALL);
        } finally {
            dao.destroy();
        }
    }

    private long jobTime(final String work) throws Exception {
        final Properties p = new Properties();
        try (var r = Files.newBufferedReader(dir.resolve(work).resolve("job.properties"))) {
            p.load(r);
        }
        return Long.parseLong(p.getProperty("timestamp"));
    }

    @Test
    void filesWriteWhatADaoLoadWrites() throws Exception {
        final List<Path> paths = rdfFiles();
        final String sparked = getTablePrefix();
        final String loaded = getTablePrefix() + "l_";
        final String out = spark(files(sparked, "work", "--max-bad-lines", "1"));
        assertTrue(out.contains("1 bad lines left out"), out);
        assertTrue(out.contains("Left out (not .nt or .nq)"), out);
        final List<RyaStatement> statements = statements(paths, jobTime("work"));
        daoLoad(loaded, statements);

        for (final String table : List.of("spo", "po", "osp", "text")) {
            assertSame(table, cells(loaded + table, true), cells(sparked + table, true));
        }
        assertSame("graphs", cells(loaded + "graphs", false), cells(sparked + "graphs", false));
        assertSame("textcount", cells(loaded + "textcount", false), cells(sparked + "textcount", false));
        assertSameCounters(loaded + "eval", sparked + "eval");

        // _:b1 is one node across the splits of its file, and another one in the other file.
        final Set<String> blank = new TreeSet<>();
        for (final RyaStatement st : statements) {
            if (st.getSubject().getData().startsWith(RyaSchema.BNODE_NAMESPACE)) {
                blank.add(st.getSubject().getData());
            }
        }
        assertEquals(2, blank.size(), blank.toString());
        assertTrue(cells(sparked + "spo", false).size() > 800);

        // The new store was split and sized as prepare does it.
        assertEquals(10L << 30, getStore().tables().targetSize(sparked + "spo"));
        assertTrue(getStore().tables().splitCount(sparked + "spo") >= 3, "at least 4 tablets per server");
    }

    @Test
    void filesWithDistinctCountEachStatementOnce() throws Exception {
        final List<Path> paths = rdfFiles();
        final String sparked = getTablePrefix();
        final String loaded = getTablePrefix() + "l_";
        spark(files(sparked, "work", "--max-bad-lines", "1", "--distinct"));
        final List<RyaStatement> all = statements(paths, jobTime("work"));
        // Once each, as the store tells statements apart: by their spo cell.
        final RyaTripleContext context = RyaTripleContext.getInstance(new StoreRdfConfiguration());
        final Map<Tasks.Bytes, RyaStatement> distinct = new LinkedHashMap<>();
        for (final RyaStatement st : all) {
            distinct.putIfAbsent(Tasks.Bytes.of(context.serializeTriple(st).get(TABLE_LAYOUT.SPO)), st);
        }
        assertEquals(all.size() - 1, distinct.size(), "the files repeat one statement");
        daoLoad(loaded, new ArrayList<>(distinct.values()));
        for (final String table : List.of("spo", "po", "osp", "text")) {
            assertSame(table, cells(loaded + table, true), cells(sparked + table, true));
        }
        assertSameCounters(loaded + "eval", sparked + "eval");
    }

    @Test
    void tooManyBadLinesStopTheWriteAndARunAgainStartsOver() throws Exception {
        rdfFiles();
        final String sparked = getTablePrefix();
        assertThrows(Exception.class, () -> spark(files(sparked, "work")));
        assertFalse(cells(sparked + "spo", false).iterator().hasNext(), "nothing imported");
        final String out = spark(files(sparked, "work", "--max-bad-lines", "1"));
        assertTrue(out.contains("Import: "), out);
        assertTrue(cells(sparked + "spo", false).size() > 800);
        // A store that holds statements takes more files only with --append.
        assertThrows(StoreCheck.Mismatch.class, () -> spark(files(sparked, "work2", "--max-bad-lines", "1")));
    }

    @Test
    void smokeWritesImportsAndReadsBackAFile() throws Exception {
        final String out = spark("smoke", "--table-prefix", getTablePrefix(), "--work-dir", dir.resolve("smoke-work").toString());
        assertTrue(out.contains("Smoke test passed"), out);
        assertTrue(out.contains("Java 25"), out);
        assertFalse(getStore().tables().exists(getTablePrefix() + "spark_smoke"));
    }

    // ------------------------------------------------------------------ cells

    /** Every cell of a table, under the labels, as text; with or without its timestamp. */
    private Set<String> cells(final String table, final boolean timestamps) {
        final Set<String> out = new HashSet<>();
        try (CellScanner cells = getStore().scan(table, KeyRange.all(), ScanSpec.of(Auths.of(AUTHS)))) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                out.add(key(c) + (timestamps ? " @" + c.timestamp() : "") + " = " + java.util.HexFormat.of().formatHex(c.value()));
            }
        }
        return out;
    }

    private static String key(final Cell c) {
        return new String(c.row(), StandardCharsets.UTF_8) + " | " + new String(c.family(), StandardCharsets.UTF_8) + " | "
                + new String(c.qualifier(), StandardCharsets.UTF_8) + " | " + new String(c.visibility(), StandardCharsets.UTF_8);
    }

    /**
     * A DAO load's counters against a new store's build: the build leaves out totals of 1 in the
     * counter families but {@code p} (the planner reads a missing counter as 1), and writes each
     * datatype marker once per write call, so markers compare by presence.
     */
    private void assertSameCounters(final String loaded, final String built) {
        final Map<String, Long> expected = counters(loaded);
        final Map<String, Long> actual = counters(built);
        final Map<String, Long> want = new HashMap<>();
        expected.forEach((k, v) -> {
            final String family = k.split(" \\| ")[1];
            if (family.equals(org.tripatha.store.DatatypeMarkers.FAMILY)) {
                want.put(k, -1L);
            } else if (!(v == 1 && EvalStatsMutations.COUNTER_FAMILIES.contains(family)
                    && !family.equals(RdfCloudTripleStoreConstants.PRED_CF))) {
                want.put(k, v);
            }
        });
        final Map<String, Long> got = new HashMap<>();
        actual.forEach((k, v) -> got.put(k, k.split(" \\| ")[1].equals(org.tripatha.store.DatatypeMarkers.FAMILY) ? -1L : v));
        if (!want.equals(got)) {
            final Set<String> diff = new TreeSet<>();
            want.forEach((k, v) -> {
                if (!v.equals(got.get(k))) {
                    diff.add(k + " want " + v + " got " + got.get(k));
                }
            });
            got.forEach((k, v) -> {
                if (!want.containsKey(k)) {
                    diff.add(k + " unexpected " + v);
                }
            });
            fail(built + ": " + diff.size() + " counters differ, e.g. " + diff.stream().limit(8).toList());
        }
    }

    private Map<String, Long> counters(final String table) {
        final Map<String, Long> out = new HashMap<>();
        try (CellScanner cells = getStore().scan(table, KeyRange.all(), ScanSpec.of(Auths.of(AUTHS)))) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                out.merge(key(c), getStore().counterValue(c.value()), Long::sum);
            }
        }
        return out;
    }

    private static void assertSame(final String what, final Set<String> expected, final Set<String> actual) {
        if (expected.equals(actual)) {
            assertFalse(expected.isEmpty(), what + " is empty");
            return;
        }
        final Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        final Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        fail(what + ": " + missing.size() + " missing, e.g. " + missing.stream().limit(5).toList() + "; " + extra.size()
                + " unexpected, e.g. " + extra.stream().limit(5).toList());
    }
}
