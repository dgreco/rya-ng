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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.security.TablePermission;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.DatatypeMarkers;
import org.tripatha.store.EvalStatsMutations;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.index.IndexManager;
import org.tripatha.store.index.IndexSearcher;
import org.tripatha.store.index.IndexState;
import org.tripatha.store.index.RebuildLog;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.IndexVerifier;
import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.RdfCloudTripleStoreConstants;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.api.resolver.triple.TripleRow;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.loader.gen.ReferenceData;
import org.tripatha.rdftriplestore.RyaSailFactory;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.WriterOptions;
import org.tripatha.test.StoreITBase;
import org.tripatha.test.accumulo.AccumuloTestBackend;
import org.tripatha.test.accumulo.MiniAccumuloSingleton;

/**
 * The loader against the test cluster of {@code -Dtripatha.test.backend}: prepare, parallel parts, stop and
 * resume, failures, verify and drop.
 */
class LoaderIT extends StoreITBase {
    private static final int ENTITIES = 2000;
    private static final String[] AUTHS = {"A", "B"};

    @TempDir
    Path dir;

    private record Run(int code, String out, String err) {
        String all() {
            return out + err;
        }
    }

    /** How the loader reaches the test cluster, as the test account. */
    private Map<String, String> env() {
        if (onBackend("accumulo")) {
            return env(MiniAccumuloSingleton.ROOT_USER, MiniAccumuloSingleton.ROOT_PASSWORD);
        }
        final Properties settings = backend().storeSettings();
        if (onBackend("rocksdb")) {
            return Map.of("TRIPATHA_BACKEND", "rocksdb", "TRIPATHA_ROCKSDB_PATH", settings.getProperty("rocksdb.path"));
        }
        final List<String> conf = new ArrayList<>();
        for (final String key : settings.stringPropertyNames()) {
            if (key.startsWith("hbase.conf.")) {
                conf.add(key.substring("hbase.conf.".length()) + "=" + settings.getProperty(key));
            }
        }
        return Map.of("TRIPATHA_BACKEND", "hbase", "TRIPATHA_HBASE_CONF", String.join(";", conf),
                "TRIPATHA_HBASE_USER", settings.getProperty("hbase.user"));
    }

    /** Accumulo only: another Accumulo user. */
    private Map<String, String> env(final String user, final String password) {
        final var cluster = MiniAccumuloSingleton.getCluster();
        return Map.of("TRIPATHA_ACCUMULO_ZOOKEEPERS", cluster.getZooKeepers(), "TRIPATHA_ACCUMULO_INSTANCE", cluster.getInstanceName(),
                "TRIPATHA_ACCUMULO_USER", user, "TRIPATHA_ACCUMULO_PASSWORD", password);
    }

    /** The dataset options every command repeats; later options override earlier ones. */
    private List<String> dataset(final String... extra) {
        final List<String> a = new ArrayList<>(List.of("--table-prefix", getTablePrefix(), "--entities",
                Integer.toString(ENTITIES), "--seed", "7", "--labels", "A,B", "--labelled-fraction", "0.3", "--graphs", "4",
                "--rdf12-fraction", "0.3", "--text-index", "--geo-index", "--entity-index", "--entity-shards", "4"));
        if (onBackend("hbase")) {
            // The test cluster's counter mode (-Dtripatha.test.hbaseCounters), which the loader takes as an option.
            a.addAll(List.of("--hbase-counters", backend().storeSettings().getProperty("hbase.counters")));
        }
        a.addAll(List.of(extra));
        return a;
    }

    private List<String> load(final int part, final int parts, final String... extra) {
        final List<String> a = dataset("--part", Integer.toString(part), "--parts", Integer.toString(parts), "--threads", "4",
                "--block-size", "25", "--commit-interval", "1s", "--report-interval", "1s", "--progress-file",
                progressFile(part, parts).toString());
        a.addAll(List.of(extra));
        return a;
    }

    private Path progressFile(final int part, final int parts) {
        return dir.resolve("p" + part + "of" + parts + ".progress");
    }

    private Run run(final Map<String, String> env, final String command, final List<String> args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final String[] all = Stream.concat(Stream.of(command), args.stream()).toArray(String[]::new);
        final int code = LoaderMain.run(all, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void prepare() {
        // On HBase granting labels needs a superuser, which the loader's test account isn't: the test grants them.
        if (!onBackend("accumulo")) {
            grantAuthorizations(AUTHS);
        }
        final Run r = run(env(), "prepare", onBackend("accumulo") ? dataset("--splits", "7", "--grant-auths")
                : dataset("--splits", "7"));
        assertEquals(0, r.code(), r.all());
    }

    private LoaderOptions options() {
        return LoaderOptions.parse(Stream.concat(Stream.of("load"), dataset().stream()).toArray(String[]::new), env());
    }

    @Test
    void loadsExactlyTheGeneratedDataInParallelParts() throws Exception {
        prepare();
        assertTrue(getUserAuthorizations().containsAll(List.of(AUTHS)), "prepare --grant-auths adds the labels");
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<Run> p0 = pool.submit(() -> run(env(), "load", load(0, 2)));
            final Future<Run> p1 = pool.submit(() -> run(env(), "load", load(1, 2)));
            assertEquals(0, p0.get().code(), p0.get().all());
            assertEquals(0, p1.get().code(), p1.get().all());
            assertTrue(p0.get().out().contains("Done:"), p0.get().out());
        } finally {
            pool.shutdownNow();
        }

        final StoreRdfConfiguration conf = assertStoreHoldsExactlyTheDataset();
        if (!onBackend("rocksdb")) {
            // A RocksDB store is one local database: there is nothing to split.
            assertEquals(7, getStore().tables().splitCount(getTablePrefix() + "spo"));
            assertTrue(getStore().tables().splitCount(getTablePrefix() + "text") > 0);
            assertTrue(getStore().tables().splitCount(getTablePrefix() + "geo") > 0);
            assertEquals(3, getStore().tables().splitCount(getTablePrefix() + "entity"), "one row per shard, split at each");
        }
        for (final IndexType type : IndexType.values()) {
            if (!type.isEnabled(conf)) {
                continue;
            }
            final IndexVerifier.Report report = IndexVerifier.verify(getStore(), conf, type);
            assertTrue(report.isClean(), type + ": " + report);
        }
        assertQueriesAgreeWithAndWithoutIndexes(conf);

        // verify: clean with every label and with none, then catches a missing statement.
        final Run all = run(env(), "verify", dataset("--auths", "A,B", "--sample", Integer.toString(ENTITIES)));
        assertEquals(0, all.code(), all.all());
        assertTrue(all.out().contains(" 0 missing, 0 unexpected, 0 counter mismatches"), all.out());
        final Run none = run(env(), "verify", dataset("--sample", "300"));
        assertEquals(0, none.code(), none.all());
        deleteOneEntityStatement(conf);
        final Run broken = run(env(), "verify", dataset("--auths", "A,B", "--sample", Integer.toString(ENTITIES)));
        assertEquals(1, broken.code(), broken.all());
        assertTrue(broken.out().contains(" 1 missing, 0 unexpected"), broken.out());
    }

    @Test
    void stopAndResumeWritesEverythingExactlyOnce() throws Exception {
        prepare();
        final LoaderOptions o = LoaderOptions.parse(Stream.concat(Stream.of("load"),
                load(0, 1, "--max-rate", "4000", "--commit-interval", "300ms").stream()).toArray(String[]::new), env());
        final Loader.Result stopped;
        try (Store store = StoreFactory.open(o)) {
            final Loader loader = new Loader(store, o, new PrintStream(OutputStream.nullOutputStream()));
            final ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                final Future<Loader.Result> result = pool.submit(loader::run);
                final long deadline = System.currentTimeMillis() + 60_000;
                while (committedBlocks(progressFile(0, 1)) == 0) {
                    if (System.currentTimeMillis() > deadline || result.isDone()) {
                        fail("no block was committed before the deadline");
                    }
                    Thread.sleep(50);
                }
                loader.requestStop();
                stopped = result.get();
            } finally {
                pool.shutdownNow();
            }
        }
        assertFalse(stopped.complete());
        assertTrue(stopped.interrupted());
        assertTrue(stopped.committedBlocks() > 0 && stopped.committedBlocks() < stopped.blocks(), stopped.toString());

        final Run again = run(env(), "load", load(0, 1));
        assertEquals(2, again.code(), "an existing progress file needs --resume: " + again.all());
        final Run resumed = run(env(), "load", load(0, 1, "--resume"));
        assertEquals(0, resumed.code(), resumed.all());
        assertTrue(resumed.out().contains("resuming after " + stopped.committedBlocks() + " done blocks"), resumed.out());
        assertStoreHoldsExactlyTheDataset();
    }

    /** Accumulo only: needs a user that may read the tables but not write them. */
    @Test
    void writeFailureStopsTheLoadAndCanBeResumed() throws Exception {
        assumeBackend("accumulo");
        prepare();
        final String user = "loader" + getTablePrefix().replace("_", "");
        final String password = "loader-it-password";
        try (AccumuloClient root = AccumuloTestBackend.newClient()) {
            writeFailure(root, user, password);
        }
    }

    private void writeFailure(final AccumuloClient root, final String user, final String password) throws Exception {
        root.securityOperations().createLocalUser(user, new PasswordToken(password));
        try {
            // May read the store (so the checks and the DAOs' init pass), may not write: the data writes fail.
            for (final String suffix : LoaderMain.SUFFIXES) {
                root.securityOperations().grantTablePermission(user, getTablePrefix() + suffix, TablePermission.READ);
            }
            final Run failed = run(env(user, password), "load", load(0, 1, "--writer-timeout", "30s"));
            assertEquals(1, failed.code(), failed.all());
            assertTrue(failed.out().contains("Load failed") && failed.out().contains("--resume"), failed.all());
            assertTrue(failed.out().contains("PERMISSION_DENIED"), failed.all());
            assertEquals(0, committedBlocks(progressFile(0, 1)));

            for (final String suffix : LoaderMain.SUFFIXES) {
                root.securityOperations().grantTablePermission(user, getTablePrefix() + suffix, TablePermission.WRITE);
            }
            final Run resumed = run(env(user, password), "load", load(0, 1, "--resume"));
            assertEquals(0, resumed.code(), resumed.all());
            assertStoreHoldsExactlyTheDataset();
        } finally {
            root.securityOperations().dropLocalUser(user);
        }
    }

    @Test
    void refusesAStoreThatDoesNotMatch() {
        final Run unprepared = run(env(), "load", load(0, 1));
        assertEquals(2, unprepared.code(), unprepared.all());
        assertTrue(unprepared.err().contains("run prepare first"), unprepared.err());

        prepare();
        final Run otherSeed = run(env(), "load", load(0, 1, "--seed", "8"));
        assertEquals(2, otherSeed.code(), otherSeed.all());
        assertTrue(otherSeed.err().contains("different dataset"), otherSeed.err());

        final Run noGeo = run(env(), "load", load(0, 1).stream().filter(a -> !a.equals("--geo-index")).toList());
        assertEquals(2, noGeo.code(), noGeo.all());
        assertTrue(noGeo.err().contains("geo index"), noGeo.err());

        final Run otherShards = run(env(), "load", load(0, 1, "--entity-shards", "5"));
        assertEquals(2, otherShards.code(), otherShards.all());
        assertTrue(otherShards.err().contains("other settings"), otherShards.err());

        final Run otherPredicates = run(env(), "load", load(0, 1, "--text-predicates", "rdfs:label"));
        assertEquals(2, otherPredicates.code(), otherPredicates.all());
        assertTrue(otherPredicates.err().contains("other settings"), otherPredicates.err());

        final Run prepareOther = run(env(), "prepare", dataset("--seed", "8"));
        assertEquals(2, prepareOther.code(), prepareOther.all());
        assertTrue(prepareOther.err().contains("different dataset"), prepareOther.err());

        final Run prepareAgain = run(env(), "prepare", dataset("--splits", "7"));
        assertEquals(0, prepareAgain.code(), prepareAgain.all());
        assertTrue(prepareAgain.out().contains("Already prepared"), prepareAgain.out());
    }

    @Test
    void dropDeletesTheStoreOnlyWhenConfirmed() throws Exception {
        prepare();
        final Run unconfirmed = run(env(), "drop", List.of("--table-prefix", getTablePrefix()));
        assertEquals(2, unconfirmed.code(), unconfirmed.all());
        assertTrue(getStore().tables().exists(getTablePrefix() + "spo"));
        final Run dropped = run(env(), "drop", List.of("--table-prefix", getTablePrefix(), "--confirm", getTablePrefix()));
        assertEquals(0, dropped.code(), dropped.all());
        assertTrue(getStore().tables().list().stream().noneMatch(t -> t.startsWith(getTablePrefix())));
    }

    /** The labels command gives an account labels; on HBase it runs as a superuser and also writes the canaries. */
    @Test
    void labelsCommandGrantsLabels() {
        final String label = "L" + getTablePrefix().replace("_", "");
        final Map<String, String> asAdmin = new HashMap<>(env());
        final List<String> args = new ArrayList<>(List.of("--labels", label));
        if (onBackend("hbase")) {
            // The operator's account: the cluster's superuser (the process user), granting to the test account.
            asAdmin.put("TRIPATHA_HBASE_USER", System.getProperty("user.name"));
            args.addAll(List.of("--grant-to", backend().storeSettings().getProperty("hbase.user")));
        }
        final Run r = run(asAdmin, "labels", args);
        assertEquals(0, r.code(), r.all());
        if (onBackend("rocksdb")) {
            // No accounts: every label is held, and the command says so.
            assertTrue(r.out().contains("every label is held"), r.all());
            return;
        }
        assertTrue(getUserAuthorizations().contains(label), getUserAuthorizations().toString());
        if (onBackend("hbase")) {
            assertEquals(List.of(), org.tripatha.hbase.HBaseLabels.missingCanaries((org.tripatha.hbase.HBaseStore) getStore(),
                    org.tripatha.hbase.HBaseLabels.DEFAULT_TABLE, List.of(label)));
            final Run asLoader = run(env(), "labels", List.of("--labels", label + "x"));
            assertEquals(2, asLoader.code(), "a normal account can't register labels: " + asLoader.all());
        }
    }

    /** Accumulo only: an accumulo-client.properties file. */
    @Test
    void checkConnectsWithAPropertiesFileThatLeavesThePasswordOut() throws Exception {
        assumeBackend("accumulo");
        final Properties props = new Properties();
        props.putAll(MiniAccumuloSingleton.getClientProperties());
        props.remove("auth.token");
        final Path file = dir.resolve("accumulo-client.properties");
        try (java.io.OutputStream out = Files.newOutputStream(file)) {
            props.store(out, null);
        }
        final Run noPassword = run(Map.of(), "check", List.of("--accumulo-properties", file.toString()));
        assertEquals(2, noPassword.code(), noPassword.all());
        assertTrue(noPassword.err().contains("TRIPATHA_ACCUMULO_PASSWORD"), noPassword.err());

        final Map<String, String> env = Map.of("TRIPATHA_ACCUMULO_PASSWORD", MiniAccumuloSingleton.ROOT_PASSWORD);
        final Run before = run(env, "check", List.of("--accumulo-properties", file.toString(), "--table-prefix", getTablePrefix()));
        assertEquals(0, before.code(), before.all());
        assertTrue(before.out().contains("Connected to Accumulo instance " + MiniAccumuloSingleton.getCluster().getInstanceName()),
                before.out());
        assertTrue(before.out().contains("Servers: 1"), before.out());
        assertTrue(before.out().contains("May create tables: yes"), before.out());
        assertTrue(before.out().contains("Store " + getTablePrefix() + ": not found"), before.out());

        final List<String> args = new ArrayList<>(dataset("--splits", "0"));
        args.addAll(List.of("--accumulo-properties", file.toString()));
        assertEquals(0, run(env, "prepare", args).code());
        final Run after = run(env, "check", List.of("--accumulo-properties", file.toString(), "--table-prefix", getTablePrefix()));
        assertTrue(after.out().contains("Store " + getTablePrefix() + ": tables [" + getTablePrefix() + "spo"), after.out());
    }

    // ---- checks ----

    /**
     * spo holds exactly the generated statements and the reference data; eval holds exactly their
     * summed counters, without the subject counters of 1 the load leaves out by default, and one
     * datatype marker per predicate, object type and label (whose count is the number of adds
     * that wrote it).
     */
    private StoreRdfConfiguration assertStoreHoldsExactlyTheDataset() throws Exception {
        return assertStoreHoldsExactlyTheDataset(false);
    }

    /**
     * @param built the entities were written by a bulk build: its counter totals of 1 are left out
     *        but p's (the reference data, written by prepare, keeps the loader's rule), so a key with
     *        1 from each reads 1, the drift of SCALE-STUDY.md 6.3
     */
    private StoreRdfConfiguration assertStoreHoldsExactlyTheDataset(final boolean built) throws Exception {
        final LoaderOptions o = options();
        final StoreRdfConfiguration conf = o.rdfConf();
        conf.setAuths(AUTHS);
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final List<RyaStatement> reference = ReferenceData.statements(generator);
        final List<RyaStatement> entities = new ArrayList<>();
        for (long e = 0; e < ENTITIES; e++) {
            entities.addAll(generator.generate(e));
        }
        final List<RyaStatement> statements = new ArrayList<>(reference);
        statements.addAll(entities);

        final Set<String> expected = statements.stream().map(Verify::key).collect(Collectors.toSet());
        final Set<String> actual = new HashSet<>();
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);
        try (CellScanner spo = getStore().scan(getTablePrefix() + "spo", KeyRange.all(), ScanSpec.of(Auths.of(AUTHS)))) {
            while (spo.hasNext()) {
                final Cell k = spo.next();
                actual.add(Verify.key(context.deserializeTriple(TABLE_LAYOUT.SPO,
                        new TripleRow(k.row(), k.family(), k.qualifier(), k.timestamp(), k.visibility(), k.value()))));
            }
        }
        assertSame("spo", expected, actual);

        final EvalStatsMutations.CounterBatch batch = new EvalStatsMutations.CounterBatch();
        (built ? reference : statements).forEach(st -> batch.add(st, 1));
        final Map<String, Long> expectedCounts = new HashMap<>();
        for (final RowMutation m : batch.drain(o.statsOnes())) {
            for (final RowMutation.Update u : m.updates()) {
                expectedCounts.merge(cell(m.row(), u.family(), u.qualifier(), u.visibility()), u.delta(), Long::sum);
            }
        }
        if (built) {
            final EvalStatsMutations.CounterBatch bulk = new EvalStatsMutations.CounterBatch();
            entities.forEach(st -> bulk.add(st, 1));
            bulk.forEachSum((key, sum) -> {
                final boolean one = sum == 1 && EvalStatsMutations.COUNTER_FAMILIES.contains(key.family()) && !key.family().equals("p");
                if (sum != 0 && !one) {
                    expectedCounts.merge(cell(key.row().getBytes(StandardCharsets.UTF_8), key.family().getBytes(StandardCharsets.UTF_8),
                            key.graph().getBytes(StandardCharsets.UTF_8), key.visibility().getBytes(StandardCharsets.ISO_8859_1)),
                            sum, Long::sum);
                }
            });
        }
        final Set<String> expectedMarkers = statements.stream()
                .map(st -> cell(st.getPredicate().getData().getBytes(StandardCharsets.UTF_8),
                        DatatypeMarkers.FAMILY.getBytes(StandardCharsets.UTF_8),
                        st.getObject().getDataType().stringValue().getBytes(StandardCharsets.UTF_8),
                        st.getColumnVisibility() == null ? new byte[0] : st.getColumnVisibility()))
                .collect(Collectors.toSet());
        final Map<String, Long> actualCounts = new HashMap<>();
        final Set<String> actualMarkers = new HashSet<>();
        try (CellScanner eval = getStore().scan(getTablePrefix() + "eval", KeyRange.all(), ScanSpec.of(Auths.of(AUTHS)))) {
            while (eval.hasNext()) {
                final Cell k = eval.next();
                final String key = cell(k.row(), k.family(), k.qualifier(), k.visibility());
                if (DatatypeMarkers.FAMILY.equals(new String(k.family(), StandardCharsets.UTF_8))) {
                    assertTrue(getStore().counterValue(k.value()) >= 1, key);
                    actualMarkers.add(key);
                } else {
                    actualCounts.merge(key, getStore().counterValue(k.value()), Long::sum);
                }
            }
        }
        assertSame("datatype markers", expectedMarkers, actualMarkers);
        assertSame("eval", expectedCounts.entrySet().stream().map(Object::toString).collect(Collectors.toSet()),
                actualCounts.entrySet().stream().map(Object::toString).collect(Collectors.toSet()));
        return conf;
    }

    // ------------------------------------------------------------------ the bulk build

    private List<String> build(final String... extra) {
        final List<String> a = dataset("--threads", "4", "--block-size", "25", "--work-dir", dir.resolve("work").toString(),
                "--spill-memory-mb", "1");
        a.addAll(List.of(extra));
        return a;
    }

    /** The same store as load writes: the dataset, the indexes, verify, and the cells of a load into another prefix. */
    /** Whether this store's backend imports files, counters included (HBase's increment mode doesn't). */
    private boolean buildsHere() {
        return getStore().bulk().map(b -> b.refusesCounters(getTablePrefix() + "eval").isEmpty()).orElse(false);
    }

    @Test
    void aBuildWritesWhatALoadWrites() throws Exception {
        prepare();
        assumeTrue(buildsHere(), getStore().backend() + " can't import this store's files");
        BulkBuild.fanIn = 2;
        try {
            for (final String phase : List.of("sort", "merge", "import")) {
                final Run r = run(env(), "build", build("--phase", phase));
                assertEquals(0, r.code(), r.all());
            }
            // Every phase is done: sorting again does nothing, and importing again imports nothing.
            final Run again = run(env(), "build", build());
            assertEquals(0, again.code(), again.all());
            assertTrue(again.out().contains("Sort: done already"), again.out());
            assertTrue(again.out().contains("Import: 0 files"), again.out());
        } finally {
            BulkBuild.fanIn = BulkBuild.FAN_IN;
        }
        final StoreRdfConfiguration conf = assertStoreHoldsExactlyTheDataset(true);
        for (final IndexType type : IndexType.values()) {
            if (type.isEnabled(conf)) {
                final IndexVerifier.Report report = IndexVerifier.verify(getStore(), conf, type);
                assertTrue(report.isClean(), type + ": " + report);
            }
        }
        assertQueriesAgreeWithAndWithoutIndexes(conf);
        final Run verify = run(env(), "verify", dataset("--auths", "A,B", "--sample", Integer.toString(ENTITIES)));
        assertEquals(0, verify.code(), verify.all());
        assertTrue(verify.out().contains(" 0 missing, 0 unexpected, 0 counter mismatches"), verify.out());

        // The same dataset through load, under another prefix: every table but eval holds the same cells.
        final String loaded = getTablePrefix() + "l_";
        final Run prepared = run(env(), "prepare", Stream.concat(dataset("--splits", "7").stream(),
                Stream.of("--table-prefix", loaded)).toList());
        assertEquals(0, prepared.code(), prepared.all());
        final Run load = run(env(), "load", load(0, 1, "--table-prefix", loaded));
        assertEquals(0, load.code(), load.all());
        for (final String table : List.of("spo", "po", "osp", "text", "geo", "entity")) {
            assertSame(table, cells(loaded + table, true), cells(getTablePrefix() + table, true));
        }
        assertSame("graphs", cells(loaded + "graphs", false), cells(getTablePrefix() + "graphs", false));
    }

    /** A crash between a spill and its record: the resume deletes that spill and writes its blocks once. */
    @Test
    void aBuildResumesAfterACrashWithoutCountingTwice() throws Exception {
        prepare();
        assumeTrue(buildsHere(), getStore().backend() + " can't import this store's files");
        final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        BulkBuild.beforeCommit = () -> {
            if (commits.incrementAndGet() == 3) {
                throw new IllegalStateException("a crash before the third commit");
            }
        };
        try {
            final Run crashed = run(env(), "build", build());
            assertEquals(1, crashed.code(), crashed.all());
        } finally {
            BulkBuild.beforeCommit = null;
        }
        final Run again = run(env(), "build", build());
        assertEquals(2, again.code(), "a build that was started needs --resume: " + again.all());
        final Run resumed = run(env(), "build", build("--resume"));
        assertEquals(0, resumed.code(), resumed.all());
        assertStoreHoldsExactlyTheDataset(true);
    }

    /** Where counters can't be imported (HBase's increment mode), build stops before writing anything. */
    @Test
    void aBuildIsRefusedWhereCountersCantBeImported() throws Exception {
        prepare();
        assumeTrue(getStore().bulk().isPresent() && !buildsHere(), "counters can be imported here");
        final Run refused = run(env(), "build", build());
        assertEquals(2, refused.code(), refused.all());
        assertTrue(refused.err().contains("increment mode"), refused.all());
    }

    // ------------------------------------------------------------------ rebuild (O5)

    /** The dataset options without the indexes: a store the indexes are added to later. */
    private List<String> withoutIndexes(final List<String> args) {
        final List<String> a = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            final String arg = args.get(i);
            if (arg.equals("--entity-shards")) {
                i++;
            } else if (!arg.equals("--text-index") && !arg.equals("--geo-index") && !arg.equals("--entity-index")) {
                a.add(arg);
            }
        }
        return a;
    }

    /** Prepares and loads the dataset with no index, as a store an index is enabled on later. */
    private void loadWithoutIndexes() {
        if (!onBackend("accumulo")) {
            grantAuthorizations(AUTHS);
        }
        final Run prepared = run(env(), "prepare", withoutIndexes(onBackend("accumulo") ? dataset("--splits", "7", "--grant-auths")
                : dataset("--splits", "7")));
        assertEquals(0, prepared.code(), prepared.all());
        final Run loaded = run(env(), "load", withoutIndexes(load(0, 1)));
        assertEquals(0, loaded.code(), loaded.all());
        assertFalse(getStore().tables().exists(getTablePrefix() + "entity"));
    }

    private List<String> rebuild(final String... extra) {
        final List<String> a = dataset("--threads", "4", "--work-dir", dir.resolve("rebuild").toString(), "--spill-memory-mb", "1");
        a.addAll(List.of(extra));
        return a;
    }

    /** Every enabled index matches spo, is READY, and no job or recorded delete is left. */
    private void assertIndexesRebuilt() throws Exception {
        final StoreRdfConfiguration conf = options().rdfConf();
        conf.setAuths(AUTHS);
        for (final IndexType type : IndexType.values()) {
            final IndexState state = IndexManager.readState(getStore(), conf.getTableLayoutStrategy(), type);
            assertEquals(IndexState.Status.READY, state.status(), type + " is READY");
            final IndexVerifier.Report report = IndexVerifier.verify(getStore(), conf, type);
            assertTrue(report.isClean(), type + ": " + report);
        }
        assertEquals(null, IndexManager.readJob(getStore(), conf.getTableLayoutStrategy()), "the job's record is gone");
        assertTrue(RebuildLog.read(getStore(), conf.getTableLayoutStrategy(), Auths.ALL_HELD).isEmpty(), "the recorded deletes are gone");
    }

    /**
     * Indexes enabled on a loaded store: the rebuild writes the cells a load with the indexes
     * writes, splits the index tables, and a second run has nothing to do.
     */
    @Test
    void aRebuildWritesTheIndexesALoadWrites() throws Exception {
        loadWithoutIndexes();
        IndexRebuild.fanIn = 2;
        try {
            final Run r = run(env(), "rebuild", rebuild());
            assertEquals(0, r.code(), r.all());
            assertTrue(r.out().contains("Finish: 0 deletes recorded"), r.out());
        } finally {
            IndexRebuild.fanIn = IndexRebuild.FAN_IN;
        }
        assertIndexesRebuilt();
        assertQueriesAgreeWithAndWithoutIndexes(options().rdfConf());
        if (!onBackend("rocksdb")) {
            assertEquals(3, getStore().tables().splitCount(getTablePrefix() + "entity"), "split at each of its 4 shards");
            assertTrue(getStore().tables().splitCount(getTablePrefix() + "text") > 0);
        }
        final Run again = run(env(), "rebuild", rebuild());
        assertEquals(0, again.code(), again.all());
        assertTrue(again.out().contains("Nothing to rebuild"), again.out());

        final String loaded = getTablePrefix() + "l_";
        final Run prepared = run(env(), "prepare", Stream.concat(dataset("--splits", "7").stream(),
                Stream.of("--table-prefix", loaded)).toList());
        assertEquals(0, prepared.code(), prepared.all());
        final Run load = run(env(), "load", load(0, 1, "--table-prefix", loaded, "--progress-file",
                dir.resolve("indexed.progress").toString()));
        assertEquals(0, load.code(), load.all());
        for (final String table : List.of("text", "geo", "entity")) {
            assertSame(table, cells(loaded + table, true), cells(getTablePrefix() + table, true));
        }
    }

    /**
     * Writes while the job runs, between its sort and its import: deletes (which the imported
     * files undo), deletes then adds again, and adds. The index tables are compacted before the
     * import, so Accumulo and HBase drop the deletes' markers, as a major compaction may on a
     * cluster: the import then brings the deleted cells back on every backend, and the finish
     * removes them. A writer that was open sees the indexes READY, graph drops wait for the job,
     * and another store can't redefine an index the job builds.
     */
    @Test
    void aRebuildReplaysTheDeletesMadeWhileItRan() throws Exception {
        loadWithoutIndexes();
        final Run sorted = run(env(), "rebuild", rebuild("--phase", "start,sort"));
        assertEquals(0, sorted.code(), sorted.all());

        final StoreRdfConfiguration conf = options().rdfConf();
        conf.setAuths(AUTHS);
        conf.setIndexStateRefresh(100);
        final RyaStoreDAO writer = new RyaStoreDAO(getStore(), conf);
        writer.init();
        try {
            assertFalse(writer.getIndexCatalog().isReady(IndexType.ENTITY), "BUILDING while the job runs");
            assertEquals(RdfCloudTripleStoreConstants.VERSION_WITH_REBUILD_JOBS, writer.getVersion(),
                    "a job raises the store's version: older writers don't record their deletes");
            final List<RyaStatement> stored = new ArrayList<>();
            final RyaTripleContext context = RyaTripleContext.getInstance(conf);
            try (CellScanner spo = getStore().scan(getTablePrefix() + "spo", KeyRange.all(), ScanSpec.of(Auths.of(AUTHS)))) {
                for (int i = 0; spo.hasNext() && stored.size() < 60; i++) {
                    final Cell k = spo.next();
                    // In a named graph: a pattern without one would match every graph.
                    if (i % 7 == 0 && k.family().length > 0) {
                        stored.add(context.deserializeTriple(TABLE_LAYOUT.SPO,
                                new TripleRow(k.row(), k.family(), k.qualifier(), k.timestamp(), k.visibility(), k.value())));
                    }
                }
            }
            for (final RyaStatement st : stored) {
                writer.delete(st, conf);
            }
            // A third of them come back, later.
            final long later = System.currentTimeMillis();
            for (int i = 0; i < stored.size(); i += 3) {
                final RyaStatement again = stored.get(i);
                again.setTimestamp(later);
                writer.add(again);
            }
            final RyaIRI added = new RyaIRI("urn:tripatha-gen:rebuild-test");
            writer.add(new RyaStatement(added, new RyaIRI("http://www.w3.org/2000/01/rdf-schema#label"),
                    new org.tripatha.api.domain.RyaType("added while rebuilding"), new RyaIRI("urn:tripatha-gen:graph/0")));
            writer.flush();
            assertEquals(stored.size(), RebuildLog.read(getStore(), conf.getTableLayoutStrategy(), Auths.ALL_HELD).size());

            final RyaDAOException refused = assertThrows(RyaDAOException.class,
                    () -> writer.dropGraph(conf, new RyaIRI("urn:tripatha-gen:graph/1")));
            assertTrue(refused.getMessage().contains("rebuild job"), refused.getMessage());
            assertThrows(RyaDAOException.class, writer::rebuildIndexes);
            // A store started to rebuild in its own process leaves the indexes to the job.
            final StoreRdfConfiguration inProcess = new StoreRdfConfiguration(conf);
            inProcess.setRebuildIndexes(true);
            final RyaStoreDAO starting = new RyaStoreDAO(getStore(), inProcess);
            starting.init();
            assertFalse(starting.getIndexCatalog().isReady(IndexType.ENTITY));
            starting.destroy();
            // Nor may a store with other index settings clear what the job builds.
            final StoreRdfConfiguration redefined = new StoreRdfConfiguration(inProcess);
            redefined.setTextIndexPredicates(List.of("http://www.w3.org/2000/01/rdf-schema#label"));
            final RyaStoreDAO other = new RyaStoreDAO(getStore(), redefined);
            final RyaDAOException clash = assertThrows(RyaDAOException.class, other::init);
            assertTrue(clash.getMessage().contains("other settings"), clash.getMessage());

            for (final String table : List.of("text", "geo", "entity")) {
                backend().compact(getTablePrefix() + table);
            }
            final Run imported = run(env(), "rebuild", rebuild("--phase", "merge,import"));
            assertEquals(0, imported.code(), imported.all());
            final IndexVerifier.Report undone = IndexVerifier.verify(getStore(), conf, IndexType.ENTITY);
            assertFalse(undone.extra().isEmpty(), "the import brings back postings of deleted statements: " + undone);

            final Run finished = run(env(), "rebuild", rebuild("--phase", "finish"));
            assertEquals(0, finished.code(), finished.all());
            final int again = (stored.size() + 2) / 3;
            assertTrue(finished.out().contains("Finish: " + stored.size() + " deletes recorded during the job, "
                    + (stored.size() - again) + " replayed, " + again + " written again"), finished.out());
            assertIndexesRebuilt();

            final long deadline = System.currentTimeMillis() + 10_000;
            while (!writer.getIndexCatalog().isReady(IndexType.ENTITY) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(writer.getIndexCatalog().isReady(IndexType.ENTITY), "the open writer sees the index READY");
            writer.dropGraph(conf, new RyaIRI("urn:tripatha-gen:graph/1"));
            writer.flush();
            for (final IndexType type : IndexType.values()) {
                final IndexVerifier.Report report = IndexVerifier.verify(getStore(), conf, type);
                assertTrue(report.isClean(), type + " after the drop: " + report);
            }
        } finally {
            writer.destroy();
        }
    }

    /** A crash between writing a range's runs and recording them: the resume reads that range again. */
    @Test
    void aRebuildResumesAfterACrash() throws Exception {
        loadWithoutIndexes();
        // The second of spo's ranges, or its only one (a RocksDB store has no splits).
        final int crashAt = getStore().tables().splitRows(getTablePrefix() + "spo", Integer.MAX_VALUE).isEmpty() ? 1 : 2;
        final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        IndexRebuild.beforeCommit = () -> {
            if (commits.incrementAndGet() == crashAt) {
                throw new IllegalStateException("a crash before commit " + crashAt);
            }
        };
        try {
            final Run crashed = run(env(), "rebuild", rebuild());
            assertEquals(1, crashed.code(), crashed.all());
        } finally {
            IndexRebuild.beforeCommit = null;
        }
        final Run again = run(env(), "rebuild", rebuild());
        assertEquals(2, again.code(), "a rebuild that was started needs --resume: " + again.all());
        final Run resumed = run(env(), "rebuild", rebuild("--resume"));
        assertEquals(0, resumed.code(), resumed.all());
        assertIndexesRebuilt();
    }

    /** Every cell of a table, under the dataset's labels, as text; with or without its timestamp. */
    private Set<String> cells(final String table, final boolean timestamps) {
        final Set<String> out = new HashSet<>();
        try (CellScanner cells = getStore().scan(table, KeyRange.all(), ScanSpec.of(Auths.of(AUTHS)))) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                out.add(cell(c.row(), c.family(), c.qualifier(), c.visibility()) + (timestamps ? " @" + c.timestamp() : "") + " = "
                        + java.util.HexFormat.of().formatHex(c.value()));
            }
        }
        return out;
    }

    private static String cell(final byte[] row, final byte[] cf, final byte[] cq, final byte[] cv) {
        return new String(row, StandardCharsets.UTF_8) + " | " + new String(cf, StandardCharsets.UTF_8) + " | "
                + new String(cq, StandardCharsets.UTF_8) + " | " + new String(cv, StandardCharsets.UTF_8);
    }

    private static void assertSame(final String what, final Set<String> expected, final Set<String> actual) {
        if (expected.equals(actual)) {
            return;
        }
        final Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        final Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        fail(what + ": " + missing.size() + " missing, e.g. " + missing.stream().limit(5).toList() + "; " + extra.size()
                + " unexpected, e.g. " + extra.stream().limit(5).toList());
    }

    /** Text and geo searches over the generated data return the same rows with and without the indexes. */
    private void assertQueriesAgreeWithAndWithoutIndexes(final StoreRdfConfiguration conf) throws Exception {
        final EntityGenerator generator = new EntityGenerator(options().genConfig());
        String word = null;
        String point = null;
        for (long e = 0; e < ENTITIES && (word == null || point == null); e++) {
            for (final RyaStatement st : generator.generate(e)) {
                final String value = st.getObject().getData();
                if (word == null && st.getPredicate().getData().endsWith("givenName") && value.matches("[A-Za-z]{4,}")) {
                    word = value.toLowerCase(java.util.Locale.ROOT);
                }
                if (point == null && value.startsWith("POINT")) {
                    point = value;
                }
            }
        }
        assertTrue(word != null && point != null);
        final String wkt = point;
        final String[] lonLat = point.substring(point.indexOf('(') + 1, point.indexOf(')')).trim().split("\\s+");
        final double lon = Double.parseDouble(lonLat[0]);
        final double lat = Double.parseDouble(lonLat[1]);
        final String box = String.format(java.util.Locale.ROOT, "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))", lon - 2,
                lat - 2, lon + 2, lat - 2, lon + 2, lat + 2, lon - 2, lat + 2, lon - 2, lat - 2);

        final StoreRdfConfiguration queryConf = conf.clone();
        queryConf.setInfer(false);
        final SailRepository repo = RyaSailFactory.createRepository(getStore(), queryConf);
        repo.init();
        try {
            final Set<String> found = assertAgrees(repo, "SELECT ?d ?l WHERE { ?d ?p ?l FILTER(text:matches(?l, \"" + word
                    + "\")) }");
            assertFalse(found.isEmpty(), "nothing matches " + word);
            final Set<String> within = assertAgrees(repo, "SELECT ?g ?w WHERE { ?g geo:asWKT ?w "
                    + "FILTER(geof:sfWithin(?w, \"" + box + "\"^^geo:wktLiteral)) }");
            assertTrue(within.stream().anyMatch(r -> r.contains(wkt)), within.toString());
            try (RepositoryConnection conn = repo.getConnection(); RepositoryResult<Resource> ids = conn.getContextIDs()) {
                final Set<String> graphs = new HashSet<>();
                ids.forEach(r -> graphs.add(r.stringValue()));
                final Set<String> expected = new HashSet<>();
                for (int k = 0; k < 4; k++) {
                    expected.add(generator.config().graph(k));
                }
                assertEquals(expected, graphs);
            }
        } finally {
            repo.shutDown();
        }
    }

    private static Set<String> assertAgrees(final SailRepository repo, final String query) {
        final Set<String> plain = rows(repo, query, false);
        final long before = IndexSearcher.getSearchCount();
        final Set<String> indexed = rows(repo, query, true);
        assertTrue(IndexSearcher.getSearchCount() > before, "the index was not used: " + query);
        assertEquals(plain, indexed, query);
        return plain;
    }

    private static Set<String> rows(final SailRepository repo, final String query, final boolean useIndex) {
        final Set<String> rows = new HashSet<>();
        try (RepositoryConnection conn = repo.getConnection()) {
            final TupleQuery q = conn.prepareTupleQuery("""
                    PREFIX text: <urn:tripatha:text#>
                    PREFIX geo: <http://www.opengis.net/ont/geosparql#>
                    PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
                    """ + query);
            q.setBinding(RdfCloudTripleStoreConfiguration.CONF_USE_INDEX, SimpleValueFactory.getInstance().createLiteral(useIndex));
            try (TupleQueryResult result = q.evaluate()) {
                while (result.hasNext()) {
                    final BindingSet bs = result.next();
                    final StringBuilder b = new StringBuilder();
                    for (final String name : new TreeSet<>(bs.getBindingNames())) {
                        if (!name.startsWith("query.")) {
                            b.append(name).append('=').append(bs.getValue(name)).append(' ');
                        }
                    }
                    rows.add(b.toString());
                }
            }
        }
        return rows;
    }

    private void deleteOneEntityStatement(final StoreRdfConfiguration conf) throws Exception {
        final String subject = options().genConfig().entity(0);
        try (CellScanner spo = getStore().scan(getTablePrefix() + "spo",
                KeyRange.prefix((subject + "\u0000").getBytes(StandardCharsets.UTF_8)), ScanSpec.of(Auths.of(AUTHS)))) {
            final Cell k = spo.next();
            try (CellWriter writer = getStore().newWriter(WriterOptions.DEFAULT)) {
                writer.table(getTablePrefix() + "spo")
                        .write(new RowMutation(k.row()).delete(k.family(), k.qualifier(), k.visibility(), k.timestamp()));
            }
        }
    }

    private static long committedBlocks(final Path file) throws Exception {
        if (!Files.exists(file)) {
            return 0;
        }
        final Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (final java.nio.file.NoSuchFileException e) {
            return 0;
        }
        return Long.parseLong(props.getProperty("committedBlocks", "0"));
    }
}
