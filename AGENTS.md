# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

rya-ng is an RDF triple store on Accumulo or HBase. It's an independent project derived from Apache Rya (see `NOTICE`): a lean Maven build with nine modules (plus `rya.coverage` under `-Pcoverage`), groupId `io.github.dgreco.rya`, version `1.0.0-SNAPSHOT`, Java packages under `io.github.dgreco.rya`. `BACKENDS-STUDY.md` is the design of the storage layer and of the HBase mapping.

| Component | Version | Notes |
|---|---|---|
| Java | 25 | |
| RDF4J | 6.1 | Jakarta-based |
| Accumulo | 2.1.6 | client API only: `AccumuloClient`, never `Connector`/`Instance` |
| HBase | 3.0.0 | the plain `hbase-client` (not the shaded one), with `VisibilityController` for labels |
| Spring Boot | 4.1 | `rya.sparql` only |
| JUnit | 6 | |

Each module's role is in the README table.

## Build and test

Set `JAVA_HOME` to a JDK 25. If a local path is needed, it's in `CLAUDE.local.md` (git-ignored).

```bash
mvn clean verify                      # everything: *Test (surefire) + *IT on MiniAccumuloCluster (failsafe), ~15 min
mvn test                              # unit tests only, no cluster
mvn -pl rya.sail -am install -DskipTests -DskipITs    # failsafe ignores -DskipTests alone

# the store, Sail, loader and endpoint ITs on an in-process HBase cluster instead
mvn verify -pl rya.test,rya.sail,rya.loader,rya.sparql -Drya.test.backend=hbase
mvn verify -pl rya.test -Drya.test.backend=hbase -Drya.test.hbaseCounters=increment   # counters without the coprocessor

# single test: install upstream first, then run one module without -am
mvn -pl rya.api test -Dtest=TypeEncodingDelimiterTest
mvn -pl rya.sail verify -Dit.test=InferenceIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

- **Naming:** `*Test` = pure unit test, `*IT` = needs a cluster. There is no IT profile; `verify` always runs ITs.
- **Coverage and CI:**
  - `-Pcoverage` attaches JaCoCo's agent to surefire (`target/jacoco.exec`) and failsafe (`target/jacoco-it.exec`). It also adds `rya.coverage`, whose `report-aggregate` merges both into `rya.coverage/target/site/jacoco-aggregate`.
  - The agent travels in the `argLine` property, so failsafe's `argLine` must keep `@{argLine}`. Any new surefire/failsafe `argLine` must include it too.
  - `.github/workflows/ci.yml` runs `mvn clean verify -Pcoverage` (job `build`), and the ITs on HBase in both counter modes (job `hbase`). `.github/scripts/ci_summary.py` turns the test XML and `jacoco.csv` into the run summary and the badge JSON. Pushes to `main` deploy the report to GitHub Pages (`/coverage/`, plus `/badge.json` and `/tests.json`, which feed the README's shields.io endpoint badges).
- **IT base classes** (`rya.test`):
  - `StoreITBase` gives each test a `Store` on the backend named by `-Drya.test.backend` (`accumulo` by default; `TestBackends`), and a unique table prefix whose tables are deleted afterwards. `grantAuthorizations(...)` gives the test account labels: Accumulo rejects scans with labels the user lacks and HBase silently ignores them, so any visibility test must call it. `assumeBackend("accumulo")` marks a backend-only test.
  - `TestBackend` is a backend's shared cluster: `openStore()`, `grantLabels`, `compact(table)`, and `storeSettings()`/`endpointProperties()` for code that connects on its own (the loader, the endpoint ITs, `DevServer`).
  - `AccumuloITBase` (a root `AccumuloClient` on the MAC) is for Accumulo-only tests; they run on Accumulo whatever the backend.
  - `rya.sail` tests use `RyaSailITBase`, which adds `newConf()`, `newDao(conf)` and `newStore(conf, inference)`.
  - `rya.test`'s own tests are the store-level ITs (`RyaStoreDAOIT`, `RangeParityIT`, `LabelParityIT`, `CounterIT`, …), run on the selected backend.
- **The HBase test cluster** (`MiniHBaseSingleton`, recipe in BACKENDS-STUDY.md phase 0): ZooKeeper plus HBase in the test JVM, on the local file system (in-process HDFS clashes with Accumulo's Jersey 3), with `VisibilityController` and `hbase.security.authorization=true`. The process user is an HBase superuser, who sees every cell, so tests run as the normal account `ryatest`. The coprocessor is on the cluster's classpath through `rya.test`'s dependencies. It binds to and advertises the loopback address: with two interfaces on one network, the host name can resolve to the wrong one mid-run.
- **Speed:** MAC tests cost about 5 s each, mostly creating the six Rya tables; HBase tests about the same (table deletes run in the background).
- **Hadoop override:** the root POM forces Hadoop 3.5.0 everywhere: Accumulo's default, 3.3.x, fails on JDK 23+ with `getSubject is not supported`, and HBase's 3.4.3 is untested here. `hbase-client` excludes `hadoop-common`/`hadoop-auth` and uses `hadoop-client-api`/`-runtime`, like Accumulo; only the test cluster (`hbase-testing-util`) brings the full Hadoop jars.
- **`metrics-core`:** required at test scope; without it the MAC ZooKeeper dies with `NoClassDefFoundError: com/codahale/metrics/Reservoir`.
- **Logging:** non-Boot modules bind `slf4j-simple` in tests. Do not add it to `rya.sparql`, where Spring Boot requires Logback to be the only SLF4J provider.

## Architecture

Data flows `rya.api` → `rya.store` → `rya.sail` → `rya.sparql`, with a backend (`rya.accumulo` or `rya.hbase`) plugged in under `rya.store`. `rya.loader` writes through `rya.store` only.

### Storage (`rya.store`)

The storage SPI (`store.spi`) is cell-shaped: `Store` (`scan`, `batchScan`, `deleteVisible`, `newWriter`, `counterValue`), `RowMutation` (puts, deletes, counter deltas), `KeyRange` (explicit inclusive flags, and a `Kind` so a backend builds its native range the same way), `ScanSpec` (labels as `Auths`, columns, row and qualifier regexes, a time range, Accumulo-only iterators), `TableAdmin`, `StoreAccess`, `Capabilities`. A backend is a `StoreProvider` found by `ServiceLoader` (`Stores.open(id, settings)`). Everything above it is written once: `RyaStoreDAO`, `RyaStoreQueryEngine`, `RyaStoreEvalStatsDAO`, `GraphSizes`, the indexes and the trace.

`RyaStoreDAO` writes each `RyaStatement` to:
- `<prefix>spo`, `<prefix>po`, `<prefix>osp`: every statement, once per table, so any triple pattern becomes a range scan on one table (`TABLE_LAYOUT`, `TriplePatternStrategy`). The named graph is the column family, the visibility label is the cell visibility.
- `<prefix>eval`: +1/−1 cardinality counters per subject, predicate, object and pair, summed server side by the backend (`EvalStatsMutations` writes `addToCounter` deltas). `RyaStoreEvalStatsDAO` reads them, and they feed RDF4J's join optimizer through `RdfCloudTripleStoreEvaluationStatistics`.
- `<prefix>graphs`: named-graph markers, used by `getContextIDs()`.
- **Batched adds:** `add(Iterator)` sums the counters of the whole call in a `CounterBatch` (one cell per counter key, drained every 50k keys) and writes each graph marker once per call. The sail adds one statement per call, so this only matters for bulk callers such as the loader. Markers are not cached across calls: `getContextIDs()` deletes markers whose graph has no visible data.
- `add` is `synchronized`: one DAO serves one writer thread at a time. `setFlush(false)` and `setWriterOptions(...)` (before `init()`) make it a bulk writer.
- Graph IRIs and qualifiers containing U+0000 are refused on every backend (HBase uses that byte to separate column names).
- `<prefix>ns`: namespaces, plus the storage version (row `rya`, cf `version`). There is no version triple in SPO.
- `<prefix>text`, `<prefix>geo`: optional secondary indexes (`index.text`, `index.geo`), maintained by `IndexManager` in the same writes as spo. Their state (READY/BUILDING plus definition) is in the ns table (row `rya`, cf `index`).
  - Key layout (`IndexKeys`): row = `term-or-cell 0x00 predicate 0x00 subject 0x00 hex(objectHash)`, cf = graph, cq = spo qualifier, and the statement's own visibility and timestamp.
  - Text terms come from `TextAnalyzer` (JDK only). Geo cells are geohash covers from `GeoCells`, with WKT parsed by RDF4J's `SpatialSupport`.

### Backends

- **Accumulo** (`rya.accumulo`): `AccumuloStore` makes each SPI call one Accumulo call, built as rya-ng always built it (same `Range`s, iterator names `riq`/`fi`/`ri` and priorities 8/9/11), so traces and behaviour are unchanged. Counter tables get a `SummingCombiner` over decimal text. `AccumuloRdfConfiguration` keeps the Accumulo-only settings (credentials, `setAdditionalIterators`).
- **HBase** (`rya.hbase`, BACKENDS-STUDY.md sections 3 and 4):
  - **Cells** (`HBaseCodec`): row = the rya-ng row (the empty row is stored as `00`), one family `c`, qualifier = `family 00 qualifier 00 visibility`, plus the label as HBase cell visibility. The label must be in the qualifier: HBase's key has no visibility, so two labels on one key would collapse, and tags can't be read back. The encoding sorts like Accumulo's keys (`QualifierOrderTest`).
  - **Labels:** "no labels" is sent as the reserved label `rya.none`, because HBase reads an empty set as "every label I hold"; "every label held" (`Auths.ALL_HELD`) is sent as no labels. Expressions are parsed by the neutral `VisibilityExpression` and written fully parenthesized. Labels must be registered first (`HBaseLabels`). A store refuses to open if, asking for no labels, it can see a label canary (`rya_labels`): its account is a superuser or the cluster doesn't enforce labels. Without the canary table (no `labels` run yet) it only warns.
  - **Reads:** an exact row is a `Get`, a range a `Scan`; `HBaseBatchScan` reads many ranges as one multi-get plus one scan per region (`MultiRowRangeFilter`), in parallel.
  - **Writes:** one `BufferedMutator` per table, all flushed at once; one HBase mutation per operation and label.
  - **Counters:** `coprocessor` mode (default): deltas are plain puts, `rya.hbase.coprocessor`'s `CounterObserver` gives each a unique timestamp and sums them on reads, flushes and major compactions. `increment` mode: HBase increments; dropping counters sets them to zero instead of deleting them (an increment's timestamp can run ahead of the clock, and a delete there would hide the next ones). A table's own descriptor decides its mode; an account that can't read descriptors uses its configured mode, so every writer must be configured alike.
  - Rows over 32,767 bytes are refused.
- **Trace:** `StoreTrace` (scope, reasons, `Trace`) is neutral. Accumulo reports through client proxies (`AccumuloTrace.wrap`, applied by `AccumuloStore`), HBase through `TracedScans` and its writer.

### Loader (`rya.loader`)

A command-line stress loader (`LoaderMain`: `dry-run`, `prepare`, `load`, `verify`, `drop`, `check`, `labels`), packaged as a Boot executable jar and run with `rya.loader/run.sh`.
- **Generator** (`gen/`): `EntityGenerator.generate(i)` returns entity `i`'s statements from the seed and `i` alone (`SplitMix64` streams), so any process can produce any range, and a rerun writes identical keys (one fixed timestamp per dataset). `ClassLayout` interleaves the classes over 1000 slots. Literals use the exact lexical form the resolvers read back. `GenConfig.fingerprint()` identifies a dataset.
- **`prepare`** runs once, from one process: one DAO init creates the tables with READY indexes (concurrent inits would race), `SplitPlanner` adds split points at quantiles of the rows a sample would write (same serializers as the DAO), and `ReferenceData` writes the ontology plus the dataset description holding the fingerprint.
- **`load`:** `StoreCheck.forLoad` refuses a store that wasn't prepared for the same dataset and index settings. Each worker thread has its own DAO, claims blocks of entities from a shared cursor, and writes each block with one `add`. A block is recorded in the `Progress` file only after a later flush; `--resume` skips recorded blocks.
- **Connection:** `StoreFactory` opens `--backend accumulo|hbase`. For Accumulo, `ClientFactory` uses `AccumuloClients.fromFile` (in `rya.accumulo`, shared with the endpoint); a properties file with password auth and no `auth.token` takes the password from `RYA_ACCUMULO_PASSWORD`. For HBase, `LoaderOptions.hbaseSettings()` layers the provider's settings: `RYA_HBASE_*`, then the `--hbase-properties` file (which selects HBase; only provider keys, client settings as `hbase.conf.<key>`), then `--hbase-site`, `--hbase-conf 'k=v;k=v'`, `--hbase-user`, `--hbase-counters`, `--hbase-counters-jar`. An empty `--hbase-user=` clears the account, so `labels` can run as the process user. `check` reports what the store reaches. An account that sees label canaries (`HBaseLabels.NotEnforced`) is a configuration error, exit 2.
- **`labels`** (and `prepare --grant-auths`) gives an account labels; on HBase it also registers them, grants `rya.none`, and writes their canaries (needs an HBase superuser).
- **`configure.sh`** asks for the backend first, then writes `accumulo-client.properties` or `hbase.properties` (for `--hbase-properties`), with mode 600. Test it as `/bin/bash rya.loader/configure.sh` with piped answers and `--defaults` for both backends. Values are escaped the way Java reads `.properties` (ISO-8859-1): anything outside printable ASCII becomes `\uXXXX` via `iconv` and `od`, which must stay bash-3.2 compatible.
- **`verify`** regenerates a sample of entities and compares them with spo and the subject counters, under the reader's labels.
- `LoaderIT` checks spo and eval cell by cell against the regenerated data, after parallel parts, a stop and resume, and a write failure followed by a resume.

### Row encoding (`rya.api` `resolver`)

- Row = s `0x00` p `0x00` objData `0x01` marker (column order differs per table).
- Values are encoded by mango `LexiTypeEncoders`, one resolver per datatype (marker bytes in the `*RyaTypeResolver` classes; `xsd:int`=13 and `xsd:integer`=5 are separate).
- **Encoded values must never contain `0x00`/`0x01`.** `RyaTypeResolverImpl.checkNoReservedBytes` enforces this, and `TypeEncodingDelimiterTest` guards it. Do not switch to Accumulo lexicoders: they emit raw bytes.

### Query path (`rya.sail`)

- **Connection:** `RdfCloudTripleStoreConnection.evaluateInternal` uses RDF4J's stock `DefaultEvaluationStrategy`, subclassed as `RyaEvaluationStrategy`, over `StoreTripleSource`.
- **Optimizers:** `RyaQueryOptimizerPipeline` mirrors RDF4J's `StandardQueryOptimizerPipeline`, with `InferenceOptimizer` (the 15 `*Visitor` rewrites in `inference/`) inserted before `QueryJoinOptimizer`. When copying RDF4J internals, read the sources jars in `~/.m2` (for example `rdf4j-queryalgebra-model-6.1.0-sources.jar`).
- **Batched joins:** `RyaEvaluationStrategy.prepare(Join)` returns a `BatchedJoinStep` when the right side is a plain statement pattern, optionally under filters, and there is no dataset, merge join, scope change or `query.limit`. Otherwise it uses RDF4J's own join.
  - The step reads up to `query.join.batchsize` left rows (default 1000), and `StoreTripleSource.prefetch` fetches all their lookups via `BatchLookup` (one `batchQuery` per table and graph).
  - RDF4J's own statement-pattern step then runs per row against that cache, so join semantics stay RDF4J's.
  - `BatchLookup` matches results to lookups on exact encoded values and deduplicates cells. Never register a partial prefetch: a missing key must mean "ask the store".
  - Run the ITs with `-Drya.test.joinBatchSize=2` (and `-Drya.join-batch-size=2` for `rya.sparql`) to push every test through chunk boundaries.
  - Run them with `-Drya.test.indexes=true` to maintain both indexes in every test. `RyaSailITBase` then checks each test's index tables against spo with `IndexVerifier`.
- **Search:** `text:matches` (`TextMatchesFunction`, registered in `META-INF/services`) and RDF4J's `geof:` functions always evaluate exactly.
  - `TextQueryValidator` (always on) rejects malformed constant text queries.
  - `IndexSearchOptimizer`, run after inference and before join ordering, swaps the statement patterns those filters cover for `IndexedStatementPattern`s. It leaves the filter in place.
  - `RyaEvaluationStrategy` runs those patterns with RDF4J's own `StatementPatternQueryEvaluationStep` over an `IndexTripleSource`. That source uses `IndexSearcher` when subject and object are unbound, and otherwise defers to the store.
  - `IndexSearcher` resolves every hit against spo with the query's conf, so the index can only cost time, never change results.
- **Inference nodes:** `RyaEvaluationStrategy.prepare(StatementPattern)` handles the two node types inference produces. `FixedStatementPattern` is matched from in-memory ontology statements; `TransitivePropertySP` goes through `InferenceEngine.findTransitiveProperty`.
- **`InferenceEngine`:** loads the ontology into memory (`ValueGraph` for the subClassOf/subPropertyOf hierarchies) and reloads it on a timer. Ontology changes are invisible until the next `refreshGraph()`; tests call it explicitly.
- **Wiring:** `RyaSailFactory` builds DAO + stats + inference + sail from a `Store` and a `StoreRdfConfiguration`. The caller owns the store.
- **Graph sizes:** `GraphSizeOptimizer`, run just before inference, replaces `?g stats:approxSize ?n` patterns with a `FixedStatementPattern` built from `GraphSizes`.
  - `GraphSizes` sums each graph's eval `p` counters (qualifier = graph), for graphs that still have a marker.
  - It finds the predicates with a skip scan of `po` (one seek each) and keeps the list 5 minutes per set of auths.
  - Don't scan eval by column family instead: without a locality group that reads the whole table.

### Endpoint (`rya.sparql`)

- `SparqlController` implements the SPARQL 1.1 Protocol at `/sparql`, with content negotiation in `ContentNegotiation` and errors mapped in `SparqlExceptionHandler`.
- Settings are `RyaProperties` (`rya.*` in `application.yaml`). `rya.backend` picks the store (`RyaStoreConfiguration`); `rya.hbase.*` configures HBase (`conf[key]` for client settings). On HBase the endpoint refuses to start when its account can't read a label of `rya.sparql.authorizations` (their canaries).
- A request's `timeout=` (seconds) replaces `rya.sparql.query-timeout`, up to `rya.sparql.max-query-timeout`; the query page sends it from its Timeout field.
- **Tracing:** `trace=<id>` streams the request's storage operations as Server-Sent Events on `/sparql/trace/<id>` (`TraceController`, `TraceHub`), shown by `ui/rya-trace.js`.
  - Recording lives in `rya.store`'s `trace` package, and a `ThreadLocal` scope collects the events (see Backends for how each backend reports).
  - New code that reads should open `StoreTrace.reason(() -> "…")` around the read, so traces say why it scans.
  - A traced query must run exactly like an untraced one: never turn on RDF4J's result-size or time tracking for it (that disables batched joins). `QueryTraceIT` checks this.
  - Always close the scope in a `finally`: server threads are pooled.
- **Query page** (`src/main/resources/static`): `index.html` plus `ui/` (our code: `rya-ui.js` sets up YASGUI on `sparql`, `rya-map.js` is the Map result tab, `wkt.js` turns WKT into GeoJSON).
  - Third-party files go to `static/vendor` at build time. The `maven-antrun-plugin` execution in `rya.sparql/pom.xml` downloads pinned npm tarballs into `~/.m2/repository/.cache/rya-ng-ui` and checks their SHA-512.
  - To upgrade one, change its `*.version` and `*.sha512` properties (the hex of npm's `integrity`), and regenerate `static/vendor/THIRD-PARTY-NOTICES.txt`.
  - `SparqlUiIT` checks that every asset is served. JavaScript isn't tested in the build; check UI changes in a browser.
- **Launch wizard:** `configure.sh` (bash 3.2, so macOS's stock bash works) writes a launch script.
  - Settings go in as `--rya.*` arguments. Spring's environment-variable form of a dashed name drops the dash (`RYA_ACCUMULO_CLIENTPROPERTIES`), so arguments are less error-prone.
  - Only the password travels in the environment (`RYA_ACCUMULO_PASSWORD`). It is read when the script starts and never written. HBase needs none (simple authentication or Kerberos).
  - To test it, pipe answers in (one per line) or use `--defaults`, then run `bash -n` on the output.
- `DevServer`, under test sources, runs the endpoint on a throwaway in-process cluster (`mvn -pl rya.sparql spring-boot:test-run -Dspring-boot.run.main-class=io.github.dgreco.rya.sparql.DevServer`; add `-Dspring-boot.run.jvmArguments=-Drya.test.backend=hbase` for HBase).

## Gotchas

- **RDF4J 6 `Var` nodes may have only one parent** (assertion in `Var.setParentNode`; tests run with `-ea`). When building new query nodes from existing variables, clone them: the inference visitors use `AbstractInferVisitor.c(var)`.
- **`null` context means different things.**
  - In a Rya `RyaStatement` pattern: any graph.
  - In RDF4J: the default graph.
  - So default-graph lookups and deletes go through `StoreTripleSource` / `RyaDAO.delete(pattern, filter, conf)`, and `dropGraph` with a `null` element means the default graph (no graphs at all means everything).
- **Blank nodes** are stored, and returned by the sail, as IRIs in `RyaSchema.BNODE_NAMESPACE`. The inference engine casts these to `IRI` throughout, so don't change this in `RyaToRdfConversions` without reworking inference. The endpoint maps them back to real blank nodes only in its output (`BlankNodeOutput`).
- **Per-connection configuration:** each connection clones the store configuration. Set per-request auths or visibility (`conf.cv`) on `RdfCloudTripleStoreConnection.getConf()`, never on the store's shared configuration.
- **Exceptions:** `RyaDAOException` is unchecked, and RDF4J 6 iterations have no exception type parameter.
- **Writes are not transactional:** commit = flush, and rollback does nothing.
- **`rya.sparql` build quirks:** Failsafe is pointed at `target/classes` (the Boot repackage hides classes inside the jar), and `jcl-over-slf4j` is excluded because Spring 7 bundles commons-logging.
- **Index invariants.** Breaking any of these loses search results or leaves stale index entries.
  - The terms of a statement must depend only on what spo stores. `deleteMatches` recomputes them from the stored match.
  - Index puts and deletes use the statement's timestamp.
  - Changing `TextAnalyzer` or `GeoCells` rules means bumping `TextAnalyzer.VERSION` / `GeoCells.DEFINITION`, so existing indexes require a rebuild.
  - Don't add a `wktLiteral` type resolver: it would change the stored object encoding.
  - The index tables follow `dropGraph`'s auth-scoped deletes, because cf = graph.
  - RDF4J's LuceneSail is not usable here: it needs a NotifyingSail and ignores visibility labels.
- **DAO init and permissions:** `init()` must not write when the store is already set up (an unchanged index state is not rewritten), and `ensureSettings` skips the stats-combiner check when the user may not read table settings (that needs ALTER_TABLE on Accumulo). Read-only users, such as an endpoint account, must be able to open a store.
- **HBase labels differ from Accumulo's** (BACKENDS-STUDY.md 4.2): superusers see every cell, an empty set means every held label, unheld labels are dropped silently, unregistered ones are refused on write, and `addLabels`/`setAuths` report failures inside the response. Never compute "my labels" with `getAuths` (empty for normal accounts): use `Auths.ALL_HELD`. Labels are only enforced with `hbase.security.authorization=true`.
- **HBase counters in coprocessor mode keep every version** until a flush or major compaction folds them; readers must add up every cell they get for a counter (the DAO and `GraphSizes` do). Puts take shared row locks, so `CounterObserver` must stamp timestamps atomically (`CounterIT` hammers this).
- **Loader:** run `prepare` before any `load`, with the same dataset and index options (`prepare` prints them). The endpoint must use the same table prefix and index settings. Counters can drift only for blocks in flight when a load fails, since a resume writes them again.
- **License headers.**
  - Files that came from Apache Rya keep their ASF header unchanged, including heavily rewritten ones. The Apache License requires it.
  - New files get the standard Apache 2.0 header from the appendix of `LICENSE` (`Copyright 2026 David Greco`), not the ASF one.
- **Attribution:** keep `NOTICE` and the README's "derived from Apache Rya" line. Don't call the project "Apache Rya": that name is an ASF trademark.

## Contribution conventions

PRs describe the change and the tests that cover it.
