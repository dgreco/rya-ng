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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.client.Durability;
import org.junit.jupiter.api.Test;

class LoaderOptionsTest {

    private static LoaderOptions parse(final String... args) {
        return LoaderOptions.parse(args, Map.of());
    }

    @Test
    void defaults() {
        final LoaderOptions o = parse("load", "--table-prefix", "t_", "--entities", "1000");
        assertEquals(LoaderOptions.Command.LOAD, o.command);
        assertEquals(8, o.threads());
        assertEquals(1000, o.blockSize());
        assertEquals(15_000, o.commitIntervalMs());
        assertEquals(32L * 1024 * 1024, o.batchMemoryBytes());
        assertEquals(Durability.DEFAULT, o.durability());
        assertEquals(-1, o.splits());
        assertTrue(o.stats());
        assertFalse(o.textIndex());
        assertFalse(o.resume());
        assertEquals(0, o.maxRate());
        assertEquals("rya-loader-t_part0of1.progress", o.progressFile().toString());
        assertEquals("rya-loader-t_e10-500_part1of2.progress", parse("load", "--table-prefix", "t_", "--entities", "1000",
                "--start-entity", "10", "--end-entity", "500", "--part", "1", "--parts", "2").progressFile().toString());
        assertEquals("root", o.user());
    }

    @Test
    void sizesAndDurations() {
        final LoaderOptions o = parse("load", "--table-prefix=t_", "--entities", "1.5M", "--commit-interval", "250ms",
                "--max-latency=2m", "--writer-timeout", "1h", "--report-interval", "7", "--max-rate", "20k",
                "--durability", "flush", "--splits", "15");
        assertEquals(1_500_000, o.genConfig().entities());
        assertEquals(250, o.commitIntervalMs());
        assertEquals(120_000, o.maxLatencyMs());
        assertEquals(3_600_000, o.writerTimeoutMs());
        assertEquals(7_000, o.reportIntervalMs());
        assertEquals(20_000, o.maxRate());
        assertEquals(Durability.FLUSH, o.durability());
        assertEquals(15, o.splits());
        assertEquals(2_000_000_000L, parse("dry-run", "--entities", "2B").genConfig().entities());
    }

    @Test
    void triplesMapToAStableEntityCount() {
        final LoaderOptions a = parse("dry-run", "--triples", "3M");
        final LoaderOptions b = parse("dry-run", "--triples", "3M");
        assertEquals(a.genConfig().entities(), b.genConfig().entities());
        final double triples = a.genConfig().entities() * LoaderOptions.triplesPerEntity(a.genConfig());
        assertEquals(3_000_000, triples, 30_000);
    }

    @Test
    void passwordOnlyFromTheEnvironment() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("load", "--table-prefix", "t_", "--entities", "10", "--password", "x"));
        assertTrue(e.getMessage().contains("RYA_ACCUMULO_PASSWORD"));
        final LoaderOptions o = LoaderOptions.parse(new String[] {"load", "--table-prefix", "t_", "--entities", "10"},
                Map.of("RYA_ACCUMULO_PASSWORD", "pw", "RYA_ACCUMULO_ZOOKEEPERS", "zk:2181", "RYA_ACCUMULO_USER", "loader"));
        assertEquals("pw", o.password());
        assertEquals("zk:2181", o.zookeepers());
        assertEquals("loader", o.user());
        assertNull(o.instance());
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> parse("frobnicate"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--entities", "10"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "10", "--bogus", "1"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "10", "stray"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "lots"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "10", "--part", "2", "--parts", "2"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "10", "--threads", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "10", "--resume=maybe"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "10", "--class-weights", "Person"));
        assertThrows(IllegalArgumentException.class, () -> parse("load", "--table-prefix", "t_", "--entities", "10", "--threads"));
    }

    @Test
    void checkNeedsNoStore() {
        assertEquals(LoaderOptions.Command.CHECK, parse("check").command);
        assertEquals("t_", parse("check", "--table-prefix", "t_").tablePrefix());
    }

    @Test
    void dropNeedsTheExactPrefix() {
        assertThrows(IllegalArgumentException.class, () -> parse("drop", "--table-prefix", "t_"));
        assertThrows(IllegalArgumentException.class, () -> parse("drop", "--table-prefix", "t_", "--confirm", "t"));
        assertEquals(LoaderOptions.Command.DROP, parse("drop", "--table-prefix", "t_", "--confirm", "t_").command);
    }

    @Test
    void partsCoverTheRangeWithoutGapsOrOverlap() {
        for (int parts = 1; parts <= 7; parts++) {
            long next = 10;
            for (int part = 0; part < parts; part++) {
                final long[] r = parse("load", "--table-prefix", "t_", "--entities", "1001", "--start-entity", "10",
                        "--end-entity", "900", "--part", Integer.toString(part), "--parts", Integer.toString(parts)).partRange();
                assertEquals(next, r[0]);
                assertTrue(r[1] >= r[0]);
                next = r[1];
            }
            assertEquals(900, next);
        }
        // --end-entity beyond the dataset stops at its end.
        assertEquals(1001, parse("load", "--table-prefix", "t_", "--entities", "1001", "--end-entity", "5000").partRange()[1]);
    }

    @Test
    void datasetArgumentsReproduceTheDataset() {
        final LoaderOptions o = parse("prepare", "--table-prefix", "t_", "--triples", "2M", "--seed", "7", "--labels", "A,B",
                "--graphs", "3", "--text-index", "--text-predicates", "rdfs:label,o:email", "--class-weights", "Person=500",
                "--stats", "off", "--threads", "3");
        final List<String> args = new ArrayList<>(List.of("load"));
        args.addAll(List.of(o.datasetArgs().split(" ")));
        final LoaderOptions again = LoaderOptions.parse(args.toArray(new String[0]), Map.of());
        assertEquals(o.genConfig(), again.genConfig());
        assertEquals(o.genConfig().fingerprint(), again.genConfig().fingerprint());
        assertEquals(List.of("http://www.w3.org/2000/01/rdf-schema#label", o.genConfig().ontology("email")), again.textPredicates());
        assertTrue(again.textIndex());
        assertFalse(again.geoIndex());
        assertFalse(again.stats());
        assertEquals(8, again.threads(), "load settings are not part of the dataset");
    }

    @Test
    void mainReportsUsageAndArgumentErrors() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        final PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(0, LoaderMain.run(new String[] {"--help"}, Map.of(), o, e));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Commands:"));
        assertEquals(0, LoaderMain.run(new String[] {"load", "--help"}, Map.of(), o, e));
        assertEquals(2, LoaderMain.run(new String[] {"load", "--entities", "5"}, Map.of(), o, e));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--table-prefix is required"));
        err.reset();
        assertEquals(2, LoaderMain.run(new String[] {"load", "--table-prefix", "t_", "--entities", "5"}, Map.of(), o, e));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--zookeepers"), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void dryRunReportsCostsAndPrintsStatements() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int code = LoaderMain.run(new String[] {"dry-run", "--entities", "3000", "--threads", "2", "--print", "5",
            "--text-index", "--geo-index", "--labels", "A"}, Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                System.err);
        assertEquals(0, code);
        final String text = out.toString(StandardCharsets.UTF_8);
        final List<String> quads = text.lines().filter(l -> l.startsWith("<urn:rya-gen:")).toList();
        assertEquals(5, quads.size(), text);
        assertTrue(quads.stream().allMatch(l -> l.contains(" .")), text);
        for (final String table : List.of("spo", "po", "osp", "eval", "text", "geo", "total")) {
            assertTrue(text.lines().anyMatch(l -> l.startsWith(table + " ")), table + " missing in\n" + text);
        }
        assertTrue(text.contains("triples/s"), text);
    }
}
