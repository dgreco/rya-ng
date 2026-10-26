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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
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

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.EvalStatsMutations;
import io.github.dgreco.rya.accumulo.index.IndexSearcher;
import io.github.dgreco.rya.accumulo.index.IndexType;
import io.github.dgreco.rya.accumulo.index.IndexVerifier;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;
import io.github.dgreco.rya.loader.gen.EntityGenerator;
import io.github.dgreco.rya.loader.gen.ReferenceData;
import io.github.dgreco.rya.rdftriplestore.RyaAccumuloSailFactory;
import io.github.dgreco.rya.test.accumulo.AccumuloITBase;

/** The loader against MiniAccumulo: prepare, parallel parts, stop and resume, failures, verify and drop. */
class LoaderIT extends AccumuloITBase {
    private static final int ENTITIES = 2000;
    private static final String[] AUTHS = {"A", "B"};

    @TempDir
    Path dir;

    private record Run(int code, String out, String err) {
        String all() {
            return out + err;
        }
    }

    private Map<String, String> env() {
        return env(getUsername(), getPassword());
    }

    private Map<String, String> env(final String user, final String password) {
        return Map.of("RYA_ACCUMULO_ZOOKEEPERS", getZookeepers(), "RYA_ACCUMULO_INSTANCE", getInstanceName(),
                "RYA_ACCUMULO_USER", user, "RYA_ACCUMULO_PASSWORD", password);
    }

    /** The dataset options every command repeats; later options override earlier ones. */
    private List<String> dataset(final String... extra) {
        final List<String> a = new ArrayList<>(List.of("--table-prefix", getTablePrefix(), "--entities",
                Integer.toString(ENTITIES), "--seed", "7", "--labels", "A,B", "--labelled-fraction", "0.3", "--graphs", "4",
                "--text-index", "--geo-index"));
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
        final Run r = run(env(), "prepare", dataset("--splits", "7", "--grant-auths"));
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

        final AccumuloRdfConfiguration conf = assertStoreHoldsExactlyTheDataset();
        assertEquals(7, getClient().tableOperations().listSplits(getTablePrefix() + "spo").size());
        assertFalse(getClient().tableOperations().listSplits(getTablePrefix() + "text").isEmpty());
        assertFalse(getClient().tableOperations().listSplits(getTablePrefix() + "geo").isEmpty());
        for (final IndexType type : IndexType.values()) {
            final IndexVerifier.Report report = IndexVerifier.verify(getClient(), conf, type);
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
        try (AccumuloClient client = ClientFactory.create(o)) {
            final Loader loader = new Loader(client, o, new PrintStream(OutputStream.nullOutputStream()));
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

    @Test
    void writeFailureStopsTheLoadAndCanBeResumed() throws Exception {
        prepare();
        final String user = "loader" + getTablePrefix().replace("_", "");
        final String password = "loader-it-password";
        getClient().securityOperations().createLocalUser(user, new PasswordToken(password));
        try {
            // May read the store (so the checks and the DAOs' init pass), may not write: the data writes fail.
            for (final String suffix : LoaderMain.SUFFIXES) {
                getClient().securityOperations().grantTablePermission(user, getTablePrefix() + suffix, TablePermission.READ);
            }
            final Run failed = run(env(user, password), "load", load(0, 1, "--writer-timeout", "30s"));
            assertEquals(1, failed.code(), failed.all());
            assertTrue(failed.out().contains("Load failed") && failed.out().contains("--resume"), failed.all());
            assertTrue(failed.out().contains("PERMISSION_DENIED"), failed.all());
            assertEquals(0, committedBlocks(progressFile(0, 1)));

            for (final String suffix : LoaderMain.SUFFIXES) {
                getClient().securityOperations().grantTablePermission(user, getTablePrefix() + suffix, TablePermission.WRITE);
            }
            final Run resumed = run(env(user, password), "load", load(0, 1, "--resume"));
            assertEquals(0, resumed.code(), resumed.all());
            assertStoreHoldsExactlyTheDataset();
        } finally {
            getClient().securityOperations().dropLocalUser(user);
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
        assertTrue(getClient().tableOperations().exists(getTablePrefix() + "spo"));
        final Run dropped = run(env(), "drop", List.of("--table-prefix", getTablePrefix(), "--confirm", getTablePrefix()));
        assertEquals(0, dropped.code(), dropped.all());
        assertTrue(getClient().tableOperations().list().stream().noneMatch(t -> t.startsWith(getTablePrefix())));
    }

    // ---- checks ----

    /** spo holds exactly the generated statements and the reference data; eval holds exactly their summed counters. */
    private AccumuloRdfConfiguration assertStoreHoldsExactlyTheDataset() throws Exception {
        final LoaderOptions o = options();
        final AccumuloRdfConfiguration conf = o.rdfConf();
        conf.setAuths(AUTHS);
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final List<RyaStatement> statements = new ArrayList<>(ReferenceData.statements(generator));
        for (long e = 0; e < ENTITIES; e++) {
            statements.addAll(generator.generate(e));
        }

        final Set<String> expected = statements.stream().map(Verify::key).collect(Collectors.toSet());
        final Set<String> actual = new HashSet<>();
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);
        try (Scanner spo = getClient().createScanner(getTablePrefix() + "spo", new Authorizations(AUTHS))) {
            for (final Map.Entry<Key, Value> e : spo) {
                final Key k = e.getKey();
                actual.add(Verify.key(context.deserializeTriple(TABLE_LAYOUT.SPO, new TripleRow(k.getRowData().toArray(),
                        k.getColumnFamilyData().toArray(), k.getColumnQualifierData().toArray(), k.getTimestamp(),
                        k.getColumnVisibilityData().toArray(), e.getValue().get()))));
            }
        }
        assertSame("spo", expected, actual);

        final EvalStatsMutations.CounterBatch batch = new EvalStatsMutations.CounterBatch();
        statements.forEach(st -> batch.add(st, 1));
        final Map<String, Long> expectedCounts = new HashMap<>();
        for (final Mutation m : batch.drain()) {
            for (final ColumnUpdate u : m.getUpdates()) {
                expectedCounts.merge(cell(m.getRow(), u.getColumnFamily(), u.getColumnQualifier(), u.getColumnVisibility()),
                        Long.parseLong(new String(u.getValue(), StandardCharsets.UTF_8)), Long::sum);
            }
        }
        final Map<String, Long> actualCounts = new HashMap<>();
        try (Scanner eval = getClient().createScanner(getTablePrefix() + "eval", new Authorizations(AUTHS))) {
            for (final Map.Entry<Key, Value> e : eval) {
                final Key k = e.getKey();
                actualCounts.merge(cell(k.getRowData().toArray(), k.getColumnFamilyData().toArray(),
                        k.getColumnQualifierData().toArray(), k.getColumnVisibilityData().toArray()),
                        Long.parseLong(e.getValue().toString()), Long::sum);
            }
        }
        assertSame("eval", expectedCounts.entrySet().stream().map(Object::toString).collect(Collectors.toSet()),
                actualCounts.entrySet().stream().map(Object::toString).collect(Collectors.toSet()));
        return conf;
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
    private void assertQueriesAgreeWithAndWithoutIndexes(final AccumuloRdfConfiguration conf) throws Exception {
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

        final AccumuloRdfConfiguration queryConf = conf.clone();
        queryConf.setInfer(false);
        final SailRepository repo = RyaAccumuloSailFactory.createRepository(getClient(), queryConf);
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
                    PREFIX text: <urn:rya-ng:text#>
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

    private void deleteOneEntityStatement(final AccumuloRdfConfiguration conf) throws Exception {
        final String subject = options().genConfig().entity(0);
        try (Scanner spo = getClient().createScanner(getTablePrefix() + "spo", new Authorizations(AUTHS))) {
            spo.setRange(Range.prefix(subject + "\u0000"));
            final Map.Entry<Key, Value> first = spo.iterator().next();
            final Key k = first.getKey();
            try (BatchWriter writer = getClient().createBatchWriter(getTablePrefix() + "spo")) {
                final Mutation m = new Mutation(k.getRow());
                m.putDelete(k.getColumnFamily(), k.getColumnQualifier(), new ColumnVisibility(k.getColumnVisibility()),
                        k.getTimestamp());
                writer.addMutation(m);
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
