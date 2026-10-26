# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

rya-ng is an RDF triple store on Accumulo. It's an independent project derived from Apache Rya (see `NOTICE`): a lean Maven build with five modules, groupId `io.github.dgreco.rya`, version `1.0.0-SNAPSHOT`, Java packages under `io.github.dgreco.rya`.

| Component | Version | Notes |
|---|---|---|
| Java | 25 | |
| RDF4J | 6.1 | Jakarta-based |
| Accumulo | 2.1.6 | client API only: `AccumuloClient`, never `Connector`/`Instance` |
| Spring Boot | 4.1 | `rya.sparql` only |
| JUnit | 6 | |

Each module's role is in the README table.

## Build and test

Set `JAVA_HOME` to a JDK 25. If a local path is needed, it's in `CLAUDE.local.md` (git-ignored).

```bash
mvn clean verify                      # everything: *Test (surefire) + *IT on MiniAccumuloCluster (failsafe), ~15 min
mvn test                              # unit tests only, no Accumulo
mvn -pl rya.sail -am install -DskipTests

# single test: install upstream first, then run one module without -am
mvn -pl rya.api test -Dtest=TypeEncodingDelimiterTest
mvn -pl rya.sail verify -Dit.test=InferenceIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

- **Naming:** `*Test` = pure unit test, `*IT` = needs Accumulo. There is no IT profile; `verify` always runs ITs.
- **IT base classes:**
  - `rya.test.accumulo`'s `AccumuloITBase` gives each test a root `AccumuloClient` and a unique table prefix (tables are deleted afterwards), plus `grantAuthorizations(...)`. A real cluster rejects scans with auths the user lacks, so any visibility test must call it.
  - `rya.sail` tests use `RyaSailITBase`, which adds `newConf()`, `newDao(conf)` and `newStore(conf, inference)`.
- **Speed:** MAC tests cost about 5 s each, mostly creating the six Rya tables.
- **Hadoop override:** the root POM forces Hadoop client 3.5.0. Accumulo's default, 3.3.x, fails on JDK 23+ with `getSubject is not supported`.
- **`metrics-core`:** required at test scope; without it the MAC ZooKeeper dies with `NoClassDefFoundError: com/codahale/metrics/Reservoir`.
- **Logging:** non-Boot modules bind `slf4j-simple` in tests. Do not add it to `rya.sparql`, where Spring Boot requires Logback to be the only SLF4J provider.

## Architecture

Data flows `rya.api` → `rya.accumulo` → `rya.sail` → `rya.sparql`.

### Storage (`rya.accumulo`)

`AccumuloRyaDAO` writes each `RyaStatement` to:
- `<prefix>spo`, `<prefix>po`, `<prefix>osp`: every statement, once per table, so any triple pattern becomes a range scan on one table (`TABLE_LAYOUT`, `TriplePatternStrategy`). The named graph is the column family, the visibility label is the cell visibility.
- `<prefix>eval`: +1/−1 cardinality counters per subject, predicate, object and pair, summed server side by Accumulo's built-in `SummingCombiner` (`EvalStatsMutations`). `AccumuloRdfEvalStatsDAO` reads them, and they feed RDF4J's join optimizer through `RdfCloudTripleStoreEvaluationStatistics`.
- `<prefix>graphs`: named-graph markers, used by `getContextIDs()`.
- `<prefix>ns`: namespaces, plus the storage version (row `rya`, cf `version`). There is no version triple in SPO.
- `<prefix>text`, `<prefix>geo`: optional secondary indexes (`index.text`, `index.geo`), maintained by `IndexManager` in the same writes as spo. Their state (READY/BUILDING plus definition) is in the ns table (row `rya`, cf `index`).
  - Key layout (`IndexKeys`): row = `term-or-cell 0x00 predicate 0x00 subject 0x00 hex(objectHash)`, cf = graph, cq = spo qualifier, and the statement's own visibility and timestamp.
  - Text terms come from `TextAnalyzer` (JDK only). Geo cells are geohash covers from `GeoCells`, with WKT parsed by RDF4J's `SpatialSupport`.

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
- **Wiring:** `RyaAccumuloSailFactory` builds DAO + stats + inference + sail from an `AccumuloClient` and an `AccumuloRdfConfiguration`. The caller owns the client.

### Endpoint (`rya.sparql`)

- `SparqlController` implements the SPARQL 1.1 Protocol at `/sparql`, with content negotiation in `ContentNegotiation` and errors mapped in `SparqlExceptionHandler`.
- Settings are `RyaProperties` (`rya.*` in `application.yaml`).
- `DevServer`, under test sources, runs the endpoint on a throwaway MAC (`mvn -pl rya.sparql spring-boot:test-run -Dspring-boot.run.main-class=io.github.dgreco.rya.sparql.DevServer`).

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
- **License headers.**
  - Files that came from Apache Rya keep their ASF header unchanged, including heavily rewritten ones. The Apache License requires it.
  - New files get the standard Apache 2.0 header from the appendix of `LICENSE` (`Copyright 2026 David Greco`), not the ASF one.
- **Attribution:** keep `NOTICE` and the README's "derived from Apache Rya" line. Don't call the project "Apache Rya": that name is an ASF trademark.

## Contribution conventions

PRs describe the change and the tests that cover it.
