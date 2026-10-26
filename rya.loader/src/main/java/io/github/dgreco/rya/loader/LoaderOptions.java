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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.Durability;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.loader.gen.EntityClass;
import io.github.dgreco.rya.loader.gen.EntityGenerator;
import io.github.dgreco.rya.loader.gen.GenConfig;

/** Command-line options of the loader. Parsing never reads the password from the arguments. */
public final class LoaderOptions {

    public enum Command {
        PREPARE("prepare"), LOAD("load"), DRY_RUN("dry-run"), VERIFY("verify"), DROP("drop");

        private final String word;

        Command(final String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }

        static Command of(final String word) {
            for (final Command c : values()) {
                if (c.word.equals(word)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("unknown command '" + word + "' (prepare, load, dry-run, verify, drop)");
        }
    }

    /** Options without a value. */
    private static final Set<String> FLAGS = Set.of("text-index", "geo-index", "resume", "grant-auths", "help");
    /** Every option with a value. */
    private static final Set<String> VALUED = Set.of("table-prefix", "client-properties", "zookeepers", "instance", "user",
            "text-predicates", "stats", "entities", "triples", "start-entity", "end-entity", "seed", "timestamp", "graphs",
            "default-graph-fraction", "labels", "labelled-fraction", "geo-scale", "polygon-fraction", "text-scale",
            "link-skew", "word-skew", "class-weights", "base-iri", "part", "parts", "threads", "block-size",
            "commit-interval", "batch-memory-mb", "writer-threads", "max-latency", "writer-timeout", "durability",
            "max-rate", "report-interval", "progress-file", "splits", "print", "link-mbps", "sample", "auths", "confirm");
    /** Options that change the generated data: repeated in the commands printed by prepare. */
    private static final List<String> GENERATION = List.of("seed", "timestamp", "graphs", "default-graph-fraction", "labels",
            "labelled-fraction", "geo-scale", "polygon-fraction", "text-scale", "link-skew", "word-skew", "class-weights",
            "base-iri");

    public final Command command;
    public final boolean help;
    private final Map<String, String> values;
    private final Map<String, String> env;

    private GenConfig genConfig;

    private LoaderOptions(final Command command, final boolean help, final Map<String, String> values,
            final Map<String, String> env) {
        this.command = command;
        this.help = help;
        this.values = values;
        this.env = env;
    }

    public static LoaderOptions parse(final String[] args, final Map<String, String> env) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help")) {
            return new LoaderOptions(null, true, Map.of(), env);
        }
        final Command command = Command.of(args[0]);
        final Map<String, String> values = new LinkedHashMap<>();
        boolean help = false;
        for (int i = 1; i < args.length; i++) {
            final String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + arg + "'");
            }
            String name = arg.substring(2);
            String value = null;
            final int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (FLAGS.contains(name)) {
                if (value != null && !value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException("--" + name + " takes no value");
                }
                values.put(name, value == null ? "true" : value);
                help |= name.equals("help") && !"false".equals(value);
            } else if (VALUED.contains(name)) {
                if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--" + name + " needs a value");
                    }
                    value = args[++i];
                }
                values.put(name, value);
            } else if (name.equals("password")) {
                throw new IllegalArgumentException("pass the password in the RYA_ACCUMULO_PASSWORD environment variable, not as an argument");
            } else {
                throw new IllegalArgumentException("unknown option --" + name);
            }
        }
        final LoaderOptions o = new LoaderOptions(command, help, values, env);
        if (!help) {
            o.validate();
        }
        return o;
    }

    private void validate() {
        if (command != Command.DRY_RUN && tablePrefix() == null) {
            throw new IllegalArgumentException("--table-prefix is required (there is no default, so nothing lands in a "
                    + "store by accident)");
        }
        if ((command == Command.PREPARE || command == Command.LOAD || command == Command.VERIFY || command == Command.DRY_RUN)
                && !values.containsKey("entities") && !values.containsKey("triples")) {
            throw new IllegalArgumentException("give the dataset size with --triples or --entities");
        }
        if (part() < 0 || part() >= parts()) {
            throw new IllegalArgumentException("--part must be between 0 and --parts - 1");
        }
        if (threads() < 1 || blockSize() < 1) {
            throw new IllegalArgumentException("--threads and --block-size must be at least 1");
        }
        if (command == Command.DROP && !tablePrefix().equals(values.get("confirm"))) {
            throw new IllegalArgumentException("drop deletes every table of the prefix: confirm with --confirm " + tablePrefix());
        }
        genConfig(); // fails early on bad generation options
    }

    // ---- connection ----

    public String tablePrefix() {
        return values.get("table-prefix");
    }

    public Path clientProperties() {
        final String p = values.getOrDefault("client-properties", env.get("RYA_ACCUMULO_CLIENT_PROPERTIES"));
        return p == null || p.isBlank() ? null : Path.of(p);
    }

    public String zookeepers() {
        return values.getOrDefault("zookeepers", env.get("RYA_ACCUMULO_ZOOKEEPERS"));
    }

    public String instance() {
        return values.getOrDefault("instance", env.get("RYA_ACCUMULO_INSTANCE"));
    }

    public String user() {
        return values.getOrDefault("user", env.getOrDefault("RYA_ACCUMULO_USER", "root"));
    }

    /** Only from the environment. */
    public String password() {
        return env.get("RYA_ACCUMULO_PASSWORD");
    }

    // ---- store settings (must match the endpoint) ----

    public boolean textIndex() {
        return flag("text-index");
    }

    public boolean geoIndex() {
        return flag("geo-index");
    }

    public List<String> textPredicates() {
        final String raw = values.get("text-predicates");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final String p : raw.split(",")) {
            final String t = p.trim();
            if (!t.isEmpty()) {
                out.add(expand(t));
            }
        }
        return out;
    }

    private String expand(final String term) {
        if (term.startsWith("rdfs:")) {
            return "http://www.w3.org/2000/01/rdf-schema#" + term.substring(5);
        }
        if (term.startsWith("rdf:")) {
            return "http://www.w3.org/1999/02/22-rdf-syntax-ns#" + term.substring(4);
        }
        if (term.startsWith("o:")) {
            return genConfig().ontology(term.substring(2));
        }
        return term;
    }

    public boolean stats() {
        return !"off".equalsIgnoreCase(values.getOrDefault("stats", "on"));
    }

    /** The store configuration for this prefix and these index settings. */
    public AccumuloRdfConfiguration rdfConf() {
        final AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
        // dry-run needs no prefix; any prefix gives the same row bytes.
        conf.setTablePrefix(tablePrefix() == null ? "dryrun_" : tablePrefix());
        conf.setTextIndex(textIndex());
        conf.setGeoIndex(geoIndex());
        conf.setTextIndexPredicates(textPredicates());
        conf.setMaintainStats(stats());
        conf.setUseStats(stats());
        final List<String> auths = auths();
        if (!auths.isEmpty()) {
            conf.setAuths(auths.toArray(new String[0]));
        }
        return conf;
    }

    // ---- dataset ----

    /** The dataset: with --triples, the entity count comes from a fixed calibration sample. */
    public synchronized GenConfig genConfig() {
        if (genConfig == null) {
            final Map<EntityClass, Integer> weights = classWeights();
            final List<String> labels = list("labels");
            final GenConfig base = new GenConfig(longValue("seed", 42), 1_000_000, longValue("timestamp", 1_767_225_600_000L),
                    values.getOrDefault("base-iri", "urn:rya-gen:"), (int) longValue("graphs", 8),
                    doubleValue("default-graph-fraction", 0.2), labels,
                    doubleValue("labelled-fraction", labels.isEmpty() ? 0 : 0.1), doubleValue("geo-scale", 1.0),
                    doubleValue("polygon-fraction", 0.25), doubleValue("text-scale", 1.0), doubleValue("link-skew", 0.8),
                    doubleValue("word-skew", 1.0), weights);
            final long entities;
            if (values.containsKey("entities")) {
                entities = size("entities");
            } else if (values.containsKey("triples")) {
                entities = Math.max(1, (long) Math.ceil(size("triples") / triplesPerEntity(base)));
            } else {
                entities = 1;
            }
            genConfig = base.withEntities(entities);
        }
        return genConfig;
    }

    /** Average triples per entity over a fixed sample, so --triples always maps to the same entity count. */
    public static double triplesPerEntity(final GenConfig base) {
        final EntityGenerator g = new EntityGenerator(base.withEntities(1_000_000));
        long total = 0;
        final int sample = 20_000;
        for (int i = 0; i < sample; i++) {
            total += g.generate(i).size();
        }
        return (double) total / sample;
    }

    private Map<EntityClass, Integer> classWeights() {
        final Map<EntityClass, Integer> weights = GenConfig.defaultWeights();
        final String raw = values.get("class-weights");
        if (raw != null && !raw.isBlank()) {
            for (final String part : raw.split(",")) {
                final String[] kv = part.split("=");
                if (kv.length != 2) {
                    throw new IllegalArgumentException("--class-weights takes Class=weight,… (got '" + part + "')");
                }
                weights.put(EntityClass.byName(kv[0].trim()), Integer.parseInt(kv[1].trim()));
            }
        }
        return new EnumMap<>(weights);
    }

    /** The first entity of the range to load (whole dataset by default). */
    public long startEntity() {
        return values.containsKey("start-entity") ? size("start-entity") : 0;
    }

    public long endEntity() {
        return values.containsKey("end-entity") ? Math.min(size("end-entity"), genConfig().entities()) : genConfig().entities();
    }

    public int part() {
        return (int) longValue("part", 0);
    }

    public int parts() {
        return (int) longValue("parts", 1);
    }

    /** This part's slice of [startEntity, endEntity). */
    public long[] partRange() {
        final long start = startEntity();
        final long end = endEntity();
        final long span = Math.max(0, end - start);
        return new long[] {start + span * part() / parts(), start + span * (part() + 1) / parts()};
    }

    // ---- writing ----

    public int threads() {
        return (int) longValue("threads", 8);
    }

    public int blockSize() {
        return (int) longValue("block-size", 1000);
    }

    public long commitIntervalMs() {
        return duration("commit-interval", 15_000);
    }

    public long batchMemoryBytes() {
        return longValue("batch-memory-mb", 32) * 1024 * 1024;
    }

    public int writerThreads() {
        return (int) longValue("writer-threads", 4);
    }

    public long maxLatencyMs() {
        return duration("max-latency", 5_000);
    }

    public long writerTimeoutMs() {
        return duration("writer-timeout", 15 * 60_000);
    }

    public Durability durability() {
        return Durability.valueOf(values.getOrDefault("durability", "default").toUpperCase(Locale.ROOT));
    }

    /** Most triples per second this process writes; 0 for no limit. */
    public long maxRate() {
        return values.containsKey("max-rate") ? size("max-rate") : 0;
    }

    public long reportIntervalMs() {
        return duration("report-interval", 10_000);
    }

    /** Default: named after the prefix, the entity range (when not the whole dataset) and the part. */
    public Path progressFile() {
        final String p = values.get("progress-file");
        if (p != null) {
            return Path.of(p);
        }
        final boolean subRange = values.containsKey("start-entity") || values.containsKey("end-entity");
        return Path.of("rya-loader-" + tablePrefix() + (subRange ? "e" + startEntity() + "-" + endEntity() + "_" : "")
                + "part" + part() + "of" + parts() + ".progress");
    }

    public boolean resume() {
        return flag("resume");
    }

    /** Splits per table for prepare: -1 for automatic (4 per tablet server, minus one). */
    public int splits() {
        final String s = values.getOrDefault("splits", "auto");
        return s.equals("auto") ? -1 : Integer.parseInt(s);
    }

    public boolean grantAuths() {
        return flag("grant-auths");
    }

    public int print() {
        return (int) longValue("print", 0);
    }

    public double linkMbps() {
        return doubleValue("link-mbps", 940);
    }

    public int sample() {
        return (int) longValue("sample", 1000);
    }

    public List<String> auths() {
        return list("auths");
    }

    /** The options to repeat on every later command for the same dataset. */
    public String datasetArgs() {
        final StringBuilder b = new StringBuilder("--table-prefix " + tablePrefix() + " --entities " + genConfig().entities());
        for (final String name : GENERATION) {
            if (values.containsKey(name)) {
                b.append(" --").append(name).append(' ').append(values.get(name));
            }
        }
        if (textIndex()) {
            b.append(" --text-index");
        }
        if (geoIndex()) {
            b.append(" --geo-index");
        }
        if (values.containsKey("text-predicates")) {
            b.append(" --text-predicates ").append(values.get("text-predicates"));
        }
        if (!stats()) {
            b.append(" --stats off");
        }
        return b.toString();
    }

    // ---- parsing helpers ----

    private boolean flag(final String name) {
        return "true".equals(values.get(name));
    }

    private List<String> list(final String name) {
        final String raw = values.get(name);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private long longValue(final String name, final long dflt) {
        final String v = values.get(name);
        try {
            return v == null ? dflt : Long.parseLong(v.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " takes a whole number (got '" + v + "')");
        }
    }

    private double doubleValue(final String name, final double dflt) {
        final String v = values.get(name);
        try {
            return v == null ? dflt : Double.parseDouble(v.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " takes a number (got '" + v + "')");
        }
    }

    /** A count with an optional K, M or B (thousand, million, billion) suffix. */
    long size(final String name) {
        final String v = values.get(name).trim().toUpperCase(Locale.ROOT).replace("_", "");
        final long unit = switch (v.isEmpty() ? ' ' : v.charAt(v.length() - 1)) {
            case 'K' -> 1_000L;
            case 'M' -> 1_000_000L;
            case 'B', 'G' -> 1_000_000_000L;
            default -> 1L;
        };
        final String number = unit == 1 ? v : v.substring(0, v.length() - 1);
        try {
            return Math.round(Double.parseDouble(number) * unit);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " takes a count like 5000000, 5M or 1.5B (got '" + values.get(name) + "')");
        }
    }

    /** A duration such as 250ms, 15s, 5m or 1h; a plain number is seconds. */
    private long duration(final String name, final long dfltMs) {
        final String v = values.get(name);
        if (v == null) {
            return dfltMs;
        }
        final String t = v.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("ms")) {
                return Long.parseLong(t.substring(0, t.length() - 2));
            }
            final long unit = switch (t.charAt(t.length() - 1)) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                default -> 0L;
            };
            return unit == 0 ? Long.parseLong(t) * 1000 : Long.parseLong(t.substring(0, t.length() - 1)) * unit;
        } catch (final NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("--" + name + " takes a duration like 250ms, 15s, 5m or 1h (got '" + v + "')");
        }
    }

    public static String usage() {
        return """
                rya-ng loader: generates a synthetic RDF dataset and loads it into a Rya store, in parallel.

                Usage: java -jar rya.loader.jar <command> [options]     (rya.loader/run.sh sets up the connection)

                Commands:
                  dry-run   generate without a cluster: speed, bytes per triple, network ceiling, storage estimate
                  prepare   once per dataset: create the tables, pre-split them, write the ontology and reference data
                  load      write the dataset (or this process's part of it); resumable with --resume
                  verify    regenerate a sample of entities and compare them with the store
                  drop      delete every table of the prefix (needs --confirm <prefix>)

                Connection: --client-properties FILE, or --zookeepers, --instance, --user plus the password in the
                  RYA_ACCUMULO_PASSWORD environment variable (also RYA_ACCUMULO_ZOOKEEPERS/_INSTANCE/_USER).

                Dataset (repeat on every command; prepare prints the exact arguments):
                  --table-prefix P          required; tables P<spo|po|osp|ns|eval|graphs|text|geo>
                  --triples N | --entities N  size, with K/M/B suffixes (about 7.5 triples per entity)
                  --text-index --geo-index  maintain the search indexes (must match the endpoint's settings)
                  --text-predicates LIST    predicates to text-index (default all; rdfs:, rdf:, o: prefixes)
                  --stats on|off            keep join-ordering statistics (default on)
                  --seed 42  --timestamp 1767225600000  --graphs 8  --default-graph-fraction 0.2
                  --labels A,B  --labelled-fraction 0.1  --geo-scale 1  --polygon-fraction 0.25
                  --text-scale 1  --link-skew 0.8  --word-skew 1  --class-weights Person=260,…  --base-iri urn:rya-gen:

                Load:
                  --part K --parts N        load slice K of N (run N processes, on one or more machines)
                  --start-entity --end-entity  load a sub-range
                  --threads 8  --block-size 1000  --commit-interval 15s  --batch-memory-mb 32  --writer-threads 4
                  --max-latency 5s  --writer-timeout 15m  --durability default|sync|flush|log|none  --max-rate N
                  --report-interval 10s  --progress-file F  --resume

                Prepare: --splits auto|N  --grant-auths (add --labels to the user's authorizations)
                Dry run: --print N (print the first statements)  --link-mbps 940
                Verify:  --sample 1000  --auths A,B
                Drop:    --confirm <prefix>

                Exit codes: 0 done, 1 failed or interrupted (resumable), 2 bad arguments or configuration.
                """;
    }
}
