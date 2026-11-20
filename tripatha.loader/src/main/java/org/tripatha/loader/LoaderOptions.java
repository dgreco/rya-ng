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
package org.tripatha.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.tripatha.hbase.HBaseLabels;
import org.tripatha.hbase.HBaseStoreProvider;
import org.tripatha.store.EvalStatsMutations.CounterPolicy;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Durability;
import org.tripatha.loader.gen.EntityClass;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.loader.gen.GenConfig;

/** Command-line options of the loader. Parsing never reads the password from the arguments. */
public final class LoaderOptions {

    public enum Command {
        PREPARE("prepare"), LOAD("load"), BUILD("build"), REBUILD("rebuild"), DRY_RUN("dry-run"), VERIFY("verify"), DROP("drop"),
        CHECK("check"), LABELS("labels");

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
            throw new IllegalArgumentException("unknown command '" + word + "' (prepare, load, build, rebuild, dry-run, verify, drop, check, labels)");
        }
    }

    /** Options without a value. */
    private static final Set<String> FLAGS = Set.of("text-index", "geo-index", "entity-index", "resume", "grant-auths", "help");
    /** Every option with a value. */
    private static final Set<String> VALUED = Set.of("table-prefix", "accumulo-properties", "zookeepers", "instance", "user",
            "backend", "rocksdb-path", "hbase-properties", "hbase-site", "hbase-conf", "hbase-user", "hbase-counters", "hbase-counters-jar",
            "hbase-labels-table", "grant-to",
            "text-predicates", "entity-shards", "stats", "stats-ones", "entities", "triples", "start-entity", "end-entity", "seed", "timestamp", "graphs",
            "default-graph-fraction", "labels", "labelled-fraction", "geo-scale", "polygon-fraction", "text-scale",
            "link-skew", "word-skew", "class-weights", "rdf12-fraction", "base-iri", "part", "parts", "threads", "block-size",
            "commit-interval", "batch-memory-mb", "writer-threads", "max-latency", "writer-timeout", "durability",
            "max-rate", "report-interval", "progress-file", "splits", "tablet-size", "compression", "print", "link-mbps",
            "work-dir", "spill-memory-mb", "phase",
            "counter-sample", "sample", "auths", "confirm");
    /** Options that change the generated data: repeated in the commands printed by prepare. */
    private static final List<String> GENERATION = List.of("seed", "timestamp", "graphs", "default-graph-fraction", "labels",
            "labelled-fraction", "geo-scale", "polygon-fraction", "text-scale", "link-skew", "word-skew", "class-weights",
            "rdf12-fraction", "base-iri");
    /** The settings an --hbase-properties file may hold, besides {@code hbase.conf.<key>}. */
    private static final List<String> HBASE_FILE_KEYS = List.of(HBaseStoreProvider.SITE, HBaseStoreProvider.USER,
            HBaseStoreProvider.COUNTERS, HBaseStoreProvider.COUNTERS_JAR, HBaseStoreProvider.LABELS_TABLE);

    public final Command command;
    public final boolean help;
    private final Map<String, String> values;
    private final Map<String, String> env;

    private GenConfig genConfig;
    private Properties hbaseSettings;

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
                throw new IllegalArgumentException("pass the password in the TRIPATHA_ACCUMULO_PASSWORD environment variable, not as an argument");
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
        final String backend = backend();
        if (!backend.equals("accumulo") && !backend.equals("hbase") && !backend.equals("rocksdb")) {
            throw new IllegalArgumentException("--backend must be accumulo, hbase or rocksdb, not " + backend);
        }
        if (!backend.equals("hbase") && hbaseProperties() != null) {
            throw new IllegalArgumentException("--hbase-properties is for HBase, but --backend is " + backend);
        }
        if (backend.equals("rocksdb") && command != Command.DRY_RUN && rocksdbPath() == null) {
            throw new IllegalArgumentException("The RocksDB backend needs the directory of its database: --rocksdb-path DIR "
                    + "(or TRIPATHA_ROCKSDB_PATH)");
        }
        if (backend.equals("hbase") && command != Command.DRY_RUN) {
            hbaseSettings(); // reads the file and checks the values
        }
        if (command == Command.LABELS && labelList().isEmpty()) {
            throw new IllegalArgumentException("labels needs --labels A,B,...");
        }
        if (command != Command.DRY_RUN && command != Command.CHECK && command != Command.LABELS && tablePrefix() == null) {
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

    public Path accumuloProperties() {
        final String p = values.getOrDefault("accumulo-properties", env.get("TRIPATHA_ACCUMULO_PROPERTIES"));
        return p == null || p.isBlank() ? null : Path.of(p);
    }

    public String zookeepers() {
        return values.getOrDefault("zookeepers", env.get("TRIPATHA_ACCUMULO_ZOOKEEPERS"));
    }

    public String instance() {
        return values.getOrDefault("instance", env.get("TRIPATHA_ACCUMULO_INSTANCE"));
    }

    public String user() {
        return values.getOrDefault("user", env.getOrDefault("TRIPATHA_ACCUMULO_USER", "root"));
    }

    /** Only from the environment. */
    public String password() {
        return env.get("TRIPATHA_ACCUMULO_PASSWORD");
    }

    /**
     * {@code accumulo} (the default), {@code hbase} or {@code rocksdb}: {@code --backend}, else
     * {@code hbase} when {@code --hbase-properties} is given, else {@code rocksdb} when
     * {@code --rocksdb-path} is, else {@code TRIPATHA_BACKEND}.
     */
    public String backend() {
        final String explicit = values.get("backend");
        if (explicit != null) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        if (hbaseProperties() != null) {
            return "hbase";
        }
        if (values.get("rocksdb-path") != null) {
            return "rocksdb";
        }
        final String fromEnv = env.get("TRIPATHA_BACKEND");
        return fromEnv == null || fromEnv.isBlank() ? "accumulo" : fromEnv.trim().toLowerCase(Locale.ROOT);
    }

    /** The directory of the RocksDB database: {@code --rocksdb-path}, else {@code TRIPATHA_ROCKSDB_PATH}; null if not given. */
    public Path rocksdbPath() {
        final String given = values.get("rocksdb-path");
        final String p = given != null ? given : env.get("TRIPATHA_ROCKSDB_PATH");
        return p == null || p.isBlank() ? null : Path.of(p.trim());
    }

    /** The HBase settings file ({@code tripatha.loader/configure.sh} writes one); null if not given. */
    public Path hbaseProperties() {
        final String p = values.get("hbase-properties");
        return p == null || p.isBlank() ? null : Path.of(p.trim());
    }

    /**
     * The settings of the HBase backend (see {@link HBaseStoreProvider}). The {@code --hbase-*}
     * options win over the {@code --hbase-properties} file, which wins over {@code TRIPATHA_HBASE_*}.
     * An empty {@code --hbase-user} or {@code --hbase-site} clears the file's value.
     */
    public Properties hbaseSettings() {
        if (hbaseSettings == null) {
            hbaseSettings = buildHBaseSettings();
        }
        final Properties copy = new Properties();
        copy.putAll(hbaseSettings);
        return copy;
    }

    private Properties buildHBaseSettings() {
        final Properties s = new Properties();
        // The environment.
        setOrClear(s, HBaseStoreProvider.SITE, env.get("TRIPATHA_HBASE_SITE"));
        putConf(s, env.get("TRIPATHA_HBASE_CONF"), "TRIPATHA_HBASE_CONF");
        setOrClear(s, HBaseStoreProvider.USER, env.get("TRIPATHA_HBASE_USER"));
        // The file.
        final Path file = hbaseProperties();
        if (file != null) {
            final Properties f = new Properties();
            // Read as ISO-8859-1, like every .properties file (configure.sh escapes anything else).
            try (InputStream in = Files.newInputStream(file)) {
                f.load(in);
            } catch (final IOException e) {
                throw new IllegalArgumentException("can't read --hbase-properties " + file + " (" + e.getMessage() + ")");
            }
            for (final String key : f.stringPropertyNames()) {
                if (!key.startsWith(HBaseStoreProvider.CONF_PREFIX) && !HBASE_FILE_KEYS.contains(key)) {
                    throw new IllegalArgumentException(file + ": unknown setting " + key + " (HBase client settings are "
                            + "written hbase.conf.<key>, such as hbase.conf.hbase.zookeeper.quorum; the others are "
                            + String.join(", ", HBASE_FILE_KEYS) + ")");
                }
                s.setProperty(key, f.getProperty(key).trim());
            }
        }
        // The options.
        if (values.containsKey("hbase-site")) {
            setOrClear(s, HBaseStoreProvider.SITE, values.get("hbase-site"));
        }
        putConf(s, values.get("hbase-conf"), "--hbase-conf");
        if (values.containsKey("hbase-user")) {
            setOrClear(s, HBaseStoreProvider.USER, values.get("hbase-user"));
        }
        if (values.containsKey("hbase-counters-jar")) {
            setOrClear(s, HBaseStoreProvider.COUNTERS_JAR, values.get("hbase-counters-jar"));
        }
        if (values.containsKey("hbase-counters")) {
            s.setProperty(HBaseStoreProvider.COUNTERS, values.get("hbase-counters"));
        }
        if (values.containsKey("hbase-labels-table")) {
            s.setProperty(HBaseStoreProvider.LABELS_TABLE, values.get("hbase-labels-table"));
        }
        // The defaults.
        final String counters = s.getProperty(HBaseStoreProvider.COUNTERS, "coprocessor").trim().toLowerCase(Locale.ROOT);
        if (!counters.equals("coprocessor") && !counters.equals("increment")) {
            throw new IllegalArgumentException("the HBase counters are coprocessor or increment, not '" + counters + "'");
        }
        s.setProperty(HBaseStoreProvider.COUNTERS, counters);
        final String labelsTable = s.getProperty(HBaseStoreProvider.LABELS_TABLE, "");
        s.setProperty(HBaseStoreProvider.LABELS_TABLE, labelsTable.isBlank() ? HBaseLabels.DEFAULT_TABLE : labelsTable.trim());
        return s;
    }

    /** Sets the value, or removes the setting when the value is empty (null leaves it as it is). */
    private static void setOrClear(final Properties s, final String key, final String value) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            s.remove(key);
        } else {
            s.setProperty(key, value.trim());
        }
    }

    /** key=value pairs separated by ';' (values such as a ZooKeeper quorum hold commas). */
    private static void putConf(final Properties s, final String conf, final String source) {
        if (conf == null) {
            return;
        }
        for (final String pair : conf.split(";")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                s.setProperty(HBaseStoreProvider.CONF_PREFIX + pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            } else if (!pair.isBlank()) {
                throw new IllegalArgumentException(source + " takes key=value pairs separated by ';' (got '" + pair + "')");
            }
        }
    }

    /** The HBase table of label canaries; must match the endpoint's tripatha.hbase.labels-table. */
    public String hbaseLabelsTable() {
        return backend().equals("hbase") ? hbaseSettings().getProperty(HBaseStoreProvider.LABELS_TABLE) : HBaseLabels.DEFAULT_TABLE;
    }

    /** The account the labels command gives labels to; null for the connected one. */
    public String grantTo() {
        return values.get("grant-to");
    }

    /** {@code --labels}, as a list. */
    public List<String> labelList() {
        final String raw = values.get("labels");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final String l : raw.split(",")) {
            if (!l.isBlank()) {
                out.add(l.trim());
            }
        }
        return out;
    }

    // ---- store settings (must match the endpoint) ----

    public boolean textIndex() {
        return flag("text-index");
    }

    public boolean geoIndex() {
        return flag("geo-index");
    }

    /** Whether to keep the entity index (SCALE-STUDY.md O23). */
    public boolean entityIndex() {
        return flag("entity-index");
    }

    /**
     * The entity index's shard count: {@code --entity-shards}, or about one per 200 M triples
     * of the dataset, 16 at least. A shard is one row, which no backend splits.
     */
    public int entityShards() {
        final String raw = values.get("entity-shards");
        if (raw != null && !raw.isBlank() && !raw.equals("auto")) {
            return Integer.parseInt(raw.trim());
        }
        final double triples = genConfig().entities() * triplesPerEntity(genConfig());
        return (int) Math.max(16, Math.min(1_000_000, Math.ceil(triples / 200e6)));
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

    /**
     * Which statistics counters a load writes: by default it leaves out a subject's counters whose
     * total is 1 ({@code --stats-ones skip}), which the planner assumes for a missing counter.
     * Exact because a block holds whole entities, and no subject spans two entities.
     */
    public CounterPolicy statsOnes() {
        final String v = values.getOrDefault("stats-ones", "skip").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "skip" -> CounterPolicy.SKIP_SUBJECT_ONES;
            case "keep" -> CounterPolicy.ALL;
            default -> throw new IllegalArgumentException("--stats-ones takes skip or keep (got '" + values.get("stats-ones") + "')");
        };
    }

    /** The store configuration for this prefix and these index settings. */
    public StoreRdfConfiguration rdfConf() {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        // dry-run needs no prefix; any prefix gives the same row bytes.
        conf.setTablePrefix(tablePrefix() == null ? "dryrun_" : tablePrefix());
        conf.setTextIndex(textIndex());
        conf.setGeoIndex(geoIndex());
        conf.setEntityIndex(entityIndex());
        conf.setEntityIndexShards(entityShards());
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
                    values.getOrDefault("base-iri", "urn:tripatha-gen:"), (int) longValue("graphs", 8),
                    doubleValue("default-graph-fraction", 0.2), labels,
                    doubleValue("labelled-fraction", labels.isEmpty() ? 0 : 0.1), doubleValue("geo-scale", 1.0),
                    doubleValue("polygon-fraction", 0.25), doubleValue("text-scale", 1.0), doubleValue("link-skew", 0.8),
                    doubleValue("word-skew", 1.0), weights, doubleValue("rdf12-fraction", 0));
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
        return Path.of("tripatha-loader-" + tablePrefix() + (subRange ? "e" + startEntity() + "-" + endEntity() + "_" : "")
                + "part" + part() + "of" + parts() + ".progress");
    }

    public boolean resume() {
        return flag("resume");
    }

    /** The bulk build's work directory: its sorted runs, files and progress (default tripatha-build-PREFIX). */
    public String workDir() {
        return values.getOrDefault("work-dir", (command == Command.REBUILD ? "tripatha-rebuild-" : "tripatha-build-") + tablePrefix());
    }

    /** The memory a build process gives the cells it holds before spilling them, all threads together. */
    public long spillMemoryBytes() {
        return longValue("spill-memory-mb", 1024) << 20;
    }

    /** The bulk build's phases to run ({@code --phase sort,merge,import}, all by default). */
    public java.util.Set<BulkBuild.Phase> phases() {
        return phases(BulkBuild.Phase.class);
    }

    /** The phases of {@code --phase} (all by default) among a command's own. */
    public <E extends Enum<E>> java.util.Set<E> phases(final Class<E> type) {
        final String v = values.getOrDefault("phase", "all");
        if (v.equals("all")) {
            return java.util.EnumSet.allOf(type);
        }
        final java.util.Set<E> phases = java.util.EnumSet.noneOf(type);
        for (final String p : v.split(",")) {
            try {
                phases.add(Enum.valueOf(type, p.trim().toUpperCase(Locale.ROOT)));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("--phase takes all or " + java.util.Arrays.stream(type.getEnumConstants())
                        .map(c -> c.name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining(", ")) + " (got '" + p + "')");
            }
        }
        return phases;
    }

    /** Whether {@code --phase} names phases, rather than all of them. */
    public boolean phasesListed() {
        final String v = values.get("phase");
        return v != null && !v.equals("all");
    }

    /** Whether an option was given (rather than left to its default). */
    public boolean given(final String option) {
        return values.containsKey(option);
    }

    /**
     * Splits per table for prepare: -1 for automatic, each table's estimated size on disk over
     * {@link #tabletSize()}, and at least 4 per tablet server, minus one.
     */
    public int splits() {
        final String s = values.getOrDefault("splits", "auto");
        return s.equals("auto") ? -1 : Integer.parseInt(s);
    }

    /**
     * The size on disk a tablet or region should have, in bytes ({@code --tablet-size}, 10G by
     * default; K, M, G and T are powers of 1024, as Accumulo and HBase read them). prepare plans
     * the splits for it and makes it the tables' split threshold.
     */
    public long tabletSize() {
        return bytes("tablet-size", 10L << 30);
    }

    /**
     * How many times smaller than the keys it writes a table is on disk ({@code --compression},
     * 7 by default: what the statement tables measured, SCALE-STUDY.md 5.1), for prepare's size
     * estimates.
     */
    public double compression() {
        final double c = doubleValue("compression", 7);
        if (c < 1) {
            throw new IllegalArgumentException("--compression is a ratio of at least 1 (got " + c + ")");
        }
        return c;
    }

    /** Entities whose counters dry-run sums in full, to report what is stored (0: none). */
    public long counterSample() {
        return values.containsKey("counter-sample") ? size("counter-sample") : 20_000;
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
        if (entityIndex()) {
            b.append(" --entity-index --entity-shards ").append(entityShards());
        }
        if (values.containsKey("text-predicates")) {
            b.append(" --text-predicates ").append(values.get("text-predicates"));
        }
        if (!stats()) {
            b.append(" --stats off");
        } else if (statsOnes() == CounterPolicy.ALL) {
            b.append(" --stats-ones keep");
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

    /** A count with an optional K, M, B or T (thousand, million, billion, trillion) suffix. */
    long size(final String name) {
        final String v = values.get(name).trim().toUpperCase(Locale.ROOT).replace("_", "");
        final long unit = switch (v.isEmpty() ? ' ' : v.charAt(v.length() - 1)) {
            case 'K' -> 1_000L;
            case 'M' -> 1_000_000L;
            case 'B', 'G' -> 1_000_000_000L;
            case 'T' -> 1_000_000_000_000L;
            default -> 1L;
        };
        final String number = unit == 1 ? v : v.substring(0, v.length() - 1);
        try {
            return Math.round(Double.parseDouble(number) * unit);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " takes a count like 5000000, 5M, 1.5B or 1T (got '" + values.get(name) + "')");
        }
    }

    /** A size in bytes with an optional K, M, G or T suffix, powers of 1024. */
    private long bytes(final String name, final long dflt) {
        final String raw = values.get(name);
        if (raw == null) {
            return dflt;
        }
        final String v = raw.trim().toUpperCase(Locale.ROOT).replace("_", "").replaceAll("I?B$", "");
        final int shift = switch (v.isEmpty() ? ' ' : v.charAt(v.length() - 1)) {
            case 'K' -> 10;
            case 'M' -> 20;
            case 'G' -> 30;
            case 'T' -> 40;
            default -> 0;
        };
        final String number = shift == 0 ? v : v.substring(0, v.length() - 1);
        try {
            final long n = Math.round(Double.parseDouble(number) * (1L << shift));
            if (n < 1 << 20) {
                throw new IllegalArgumentException("--" + name + " must be at least 1M (got '" + raw + "')");
            }
            return n;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " takes a size like 10G or 512M (got '" + raw + "')");
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
                Tripatha loader: generates a synthetic RDF dataset and loads it into a Tripatha store, in parallel.

                Usage: java -jar tripatha.loader.jar <command> [options]     (tripatha.loader/run.sh sets up the connection)

                Commands:
                  dry-run   generate without a cluster: speed, bytes per triple, network ceiling, storage estimate
                  prepare   once per dataset: create the tables, pre-split them, write the ontology and reference data
                  load      write the dataset (or this process's part of it); resumable with --resume
                  build     write it as sorted files and import them, bypassing the write path; resumable with --resume
                  rebuild   build the store's indexes that aren't ready (--text-index, --geo-index, --entity-index, as the
                            endpoint has them) from spo, as sorted files imported; resumable, and the endpoint may write
                            meanwhile (graph drops wait). Any store, loaded by this tool or not
                  verify    regenerate a sample of entities and compare them with the store
                  drop      delete every table of the prefix (needs --confirm <prefix>)
                  check     connect and report the backend, its servers and the account's rights
                            (with --table-prefix, also whether that store exists)
                  labels    give an account labels: --labels A,B [--grant-to ACCOUNT]; on HBase also registers them
                            and writes the canaries the endpoint checks at startup (needs an HBase superuser)

                Backend: --backend accumulo|hbase|rocksdb (default accumulo; also TRIPATHA_BACKEND).
                Accumulo: --accumulo-properties FILE (tripatha.loader/configure.sh writes one), or --zookeepers,
                  --instance, --user plus the password in the TRIPATHA_ACCUMULO_PASSWORD environment variable (also
                  TRIPATHA_ACCUMULO_ZOOKEEPERS/_INSTANCE/_USER/_PROPERTIES). A properties file without auth.token
                  takes the password from TRIPATHA_ACCUMULO_PASSWORD.
                HBase: --hbase-properties FILE (tripatha.loader/configure.sh writes one; selects HBase), and/or
                  --hbase-site FILES (hbase-site.xml, and core-site.xml for Kerberos; comma-separated),
                  --hbase-conf 'key=value;key=value' (for example hbase.zookeeper.quorum=zk1,zk2), --hbase-user U
                  (simple authentication only; empty: the process user), --hbase-counters coprocessor|increment,
                  --hbase-counters-jar hdfs:///PATH (for prepare, when the coprocessor isn't on the region servers'
                  classpath), --hbase-labels-table T. Options win over the file, the file over TRIPATHA_HBASE_SITE,
                  TRIPATHA_HBASE_CONF and TRIPATHA_HBASE_USER.
                RocksDB: --rocksdb-path DIR (selects RocksDB; also TRIPATHA_ROCKSDB_PATH): a store in a local directory,
                  without a cluster. One process at a time can open it: stop the endpoint while loading, and use
                  --threads instead of --part.

                Dataset (repeat on every command; prepare prints the exact arguments):
                  --table-prefix P          required; tables P<spo|po|osp|ns|eval|graphs|text|geo|entity>
                  --triples N | --entities N  size, with K/M/B/T suffixes (about 7.5 triples per entity)
                  --text-index --geo-index  maintain the search indexes (must match the endpoint's settings)
                  --entity-index            maintain the entity index, for stars intersected shard by shard
                  --entity-shards N|auto    its shard count (default auto: one per 200M triples, 16 at least)
                  --text-predicates LIST    predicates to text-index (default all; rdfs:, rdf:, o: prefixes)
                  --stats on|off            keep join-ordering statistics (default on)
                  --stats-ones skip|keep    skip (default): don't write a subject's counters whose total is 1,
                                            which the planner assumes for a missing counter; keep: write them
                  --seed 42  --timestamp 1767225600000  --graphs 8  --default-graph-fraction 0.2
                  --labels A,B  --labelled-fraction 0.1  --geo-scale 1  --polygon-fraction 0.25
                  --text-scale 1  --link-skew 0.8  --word-skew 1  --class-weights Person=260,…  --base-iri urn:tripatha-gen:
                  --rdf12-fraction 0        share of links annotated with RDF 1.2 triple terms, and of
                                            language-tagged labels given a base direction (0: RDF 1.1 only)

                Load:
                  --part K --parts N        load slice K of N (run N processes, on one or more machines)
                  --start-entity --end-entity  load a sub-range
                  --threads 8  --block-size 1000  --commit-interval 15s  --batch-memory-mb 32  --writer-threads 4
                  --max-latency 5s  --writer-timeout 15m  --durability default|sync|flush|log|none  --max-rate N
                  --report-interval 10s  --progress-file F  --resume

                Build:   --work-dir DIR (default tripatha-build-PREFIX)  --spill-memory-mb 1024 (all threads)
                         --phase all|sort,merge,import  and the load options --threads, --block-size, --part/--parts
                Rebuild: --work-dir DIR (default tripatha-rebuild-PREFIX)  --spill-memory-mb 1024  --threads 8
                         --phase all|start,sort,merge,import,finish  --part K --parts N (finish once, after every
                         part imported)  --tablet-size 10G --compression 7 (the index tables' splits)
                         --entity-shards N (when enabling the entity index; default: the stored count, or 16)
                Prepare: --splits auto|N  (auto: each table's estimated size over --tablet-size, at least
                           4 per server)  --tablet-size 10G (also the tables' split threshold)  --compression 7
                           --grant-auths (give the account the --labels; on HBase as the labels command)
                Dry run: --print N (print the first statements)  --link-mbps 940  --counter-sample 20K
                         (entities whose counters are summed in full, to report what is stored; 0: none)
                Verify:  --sample 1000  --auths A,B
                Drop:    --confirm <prefix>

                Exit codes: 0 done, 1 failed or interrupted (resumable), 2 bad arguments or configuration.
                """;
    }
}
