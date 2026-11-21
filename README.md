<!-- Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License. -->

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/tripatha-logo-dark.svg">
    <img src="docs/assets/tripatha-logo-light.svg" alt="Tripatha" height="96">
  </picture>
</p>

# Tripatha

[![CI](https://github.com/dgreco/tripatha/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/dgreco/tripatha/actions/workflows/ci.yml)
[![coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Ftripatha.org%2Fbadge.json)](https://tripatha.org/coverage/)
[![tests](https://img.shields.io/endpoint?url=https%3A%2F%2Ftripatha.org%2Ftests.json)](#build-and-test)
[![license](https://img.shields.io/github/license/dgreco/tripatha?color=blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25%2B-orange)](#requirements)

Tripatha is an RDF triple store on [Apache Accumulo](https://accumulo.apache.org/) or [Apache HBase](https://hbase.apache.org/), or, without a cluster, on an embedded [RocksDB](https://rocksdb.org/) database in a local directory. It has two parts:
- an [RDF4J](https://rdf4j.org/) Sail you can embed;
- a standalone [SPARQL Protocol](https://www.w3.org/TR/sparql11-protocol/) endpoint for queries and updates.

It supports:
- RDF 1.2 and SPARQL 1.2: triple terms, reified and annotated statements, and literals with a text direction (see [RDF 1.2](#rdf-12));
- named graphs;
- cell-level visibility labels and scan authorizations, with the same meaning on every backend;
- optional query-time OWL/RDFS inference;
- join ordering driven by statistics that are kept up to date on every write;
- batched join lookups: the matches for many left-hand rows are fetched in one batched read, and so are all the attributes a query reads for many entities, `OPTIONAL` ones included;
- full-text search and GeoSPARQL filters, sped up by optional text and geo indexes that honour visibility labels.

Tripatha is derived from [Apache Rya](https://github.com/apache/rya). It keeps Rya's storage model and query-time inference, rebuilt for Accumulo 2.1, RDF4J 6 and Java 25, with everything else removed. It is an independent project, not part of the Apache Software Foundation. See `NOTICE`.

[ARCH.md](ARCH.md) explains how Tripatha works inside, with diagrams: the storage layout, the write and query paths, statistics, batched joins, inference and the search indexes. [BACKENDS-STUDY.md](BACKENDS-STUDY.md) explains how the storage layer is split from its backends, and how Tripatha maps onto HBase.

## Requirements

- **Java 25.** RDF4J 6 requires it.
- **Maven 3.9 or newer.**
- **Accumulo 2.1.x or HBase 3.0.x** to run against (see [Storage backends](#storage-backends)). Tests and the dev server start their own in-process cluster.

## Build and test

```bash
mvn clean verify                          # build, unit tests (*Test) and MiniAccumuloCluster tests (*IT)
mvn clean install -DskipTests -DskipITs   # build only
mvn test                                  # unit tests only (fast, no cluster)

# the integration tests on an in-process HBase cluster instead of Accumulo
mvn install -DskipTests -DskipITs
mvn verify -pl tripatha.test,tripatha.sail,tripatha.loader,tripatha.sparql -Dtripatha.test.backend=hbase
mvn verify -pl tripatha.test -Dtripatha.test.backend=hbase -Dtripatha.test.hbaseCounters=increment

# ...or on RocksDB, which needs no cluster and runs them in about two minutes
mvn verify -pl tripatha.rocksdb,tripatha.test,tripatha.sail,tripatha.loader,tripatha.sparql -Dtripatha.test.backend=rocksdb

# one test class: install the modules once, then run a single module
mvn -pl tripatha.api test -Dtest=TypeEncodingDelimiterTest
mvn -pl tripatha.test verify -Dit.test=RyaStoreDAOIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false

# coverage of the unit and integration tests together, in tripatha.coverage/target/site/jacoco-aggregate
mvn clean verify -Pcoverage
```

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn clean verify -Pcoverage` on every push and pull request, and the integration tests again on HBase and on RocksDB:
- Each run's summary lists the tests and the coverage per module, and the HTML coverage report is attached to the run.
- For pushes to `main`, the report is published at <https://tripatha.org/coverage/>. The coverage and tests badges above show its line coverage and test count.

The endpoint is packaged as `tripatha.sparql/target/tripatha.sparql-<version>.jar`. `tripatha.standalone` packages it with the RocksDB backend only, as a jar and as a native executable (see [Standalone](#standalone-one-program-no-cluster-no-jvm)).

## Modules

| Module | Purpose |
|---|---|
| `tripatha.api` | Storage-independent model (`RyaStatement`, `RyaIRI`, `RyaType`), DAO interfaces, and row and value encoding |
| `tripatha.store` | The storage SPI, and what is written once on top of it: `RyaStoreDAO` (each statement in the SPO, PO and OSP tables, statistics, the named-graph list), the query engine, the search indexes and the trace |
| `tripatha.accumulo` | The Accumulo backend |
| `tripatha.hbase` | The HBase backend |
| `tripatha.hbase.coprocessor` | Code for HBase's region servers: the counter table's observer, and the endpoint that intersects the entity index |
| `tripatha.hbase.bulk` | HBase's files for the loader's bulk build: HFiles with visibility tags, bulk-loaded (needs hbase-server, so only the loader depends on it) |
| `tripatha.accumulo.iterators` | Server-side iterators for Accumulo's tablet servers: the entity index's intersection (optional) |
| `tripatha.rocksdb` | The RocksDB backend: a store in a local directory, without a cluster |
| `tripatha.sail` | RDF4J Sail (`RdfCloudTripleStore`), query planning, inference, and `RyaSailFactory` |
| `tripatha.sparql` | Spring Boot SPARQL endpoint |
| `tripatha.loader` | Synthetic data generator and parallel loader, for stress tests |
| `tripatha.spark` | The bulk build as a Spark job, for Accumulo: the generated dataset or N-Triples and N-Quads files, sorted by Spark into RFiles and imported |
| `tripatha.standalone` | The endpoint with the RocksDB backend only: an executable jar, and with `-Pnative` a native executable |
| `tripatha.test` | Test-only harness: one shared cluster per backend per JVM, and the store-level integration tests |

## Benchmarks

### Tripatha on one machine, against engines built for one machine

Tripatha is built to run on distributed stores, Accumulo and HBase clusters, with cell-level labels and up to 10^12 triples. Its RocksDB backend runs the same code on a single machine. How far does that cost it against engines built only for one machine? This compares them on **WatDiv**, a standard SPARQL benchmark that none of the three engines was tuned for.

**The engines,** on one machine, each measured alone, over HTTP:

| Engine | What it is | Here |
|---|---|---|
| **Tripatha** | This project: RocksDB inside, the same query engine and storage interface as on Accumulo and HBase | its native executable, profile-guided build, `-Xms6g -Xmx6g`, the entity index on |
| [QLever](https://github.com/ad-freiburg/qlever) 0.6.0 | A single-machine engine for large analytical queries, over an index it builds in advance | Homebrew build, `-m 6G` |
| [Oxigraph](https://github.com/oxigraph/oxigraph) 0.5.13 | An embedded store which, like Tripatha here, keeps its data in RocksDB | the pinned release, read-only |

**What Tripatha carries that the others don't.** Everything in it is designed so that the same plan holds on a cluster of servers:
- **A storage interface three backends share.** Each call is one native operation of a distributed sorted store (a range scan, a batch of ranges, a write), and the query engine works on generic labelled cells, not on structures only one machine could hold.
- **A visibility label on every statement,** checked on every cell read, with the same meaning on every backend.
- **Terms stored whole in every key,** with no dictionary of integer IDs. At 10^12 triples a dictionary would hold hundreds of billions of terms, and whether one pays there is an open decision (SCALE-STUDY.md O11, M5).
- **Every statement three times** (`spo`, `po`, `osp`), plus cardinality counters and an entity index, all kept up to date on every write: it takes updates online.
- **RDF4J's evaluation,** binding set by binding set.

**The results,** measured on 2026-11-21 with `compare.sh`:
- **The setup:** commit `c1e1968`, on one machine (12 cores, 24 GB, macOS, NVMe).
- **The benchmark:** WatDiv at scale factor 100, 10,867,277 statements, 5 queries from each of its 20 templates.
- **The checks:** all three engines held the same statements, and every query returned the same rows on all three.
- **The times:** the median of 10 timed runs, to the last byte of a TSV result over HTTP, with a warm cache.

Geometric means, in ms:

| Queries | Count | Tripatha | QLever | Oxigraph |
|---|---|---|---|---|
| **All** | 100 | **2.3** | 9.5 | 3.8 |
| Linear (L) | 25 | **1.3** | 3.7 | 1.5 |
| Star (S) | 35 | **1.1** | 6.0 | 2.0 |
| Snowflake (F) | 25 | 1.5 | 14.9 | **1.4** |
| Complex (C) | 15 | **54.7** | 65.0 | 398.7 |
| Fastest on (templates of 20) | | **13** (5 tied with Oxigraph) | 3 | 9 |

<details>
<summary>Per template: the median of its 5 queries, in ms</summary>

| Template | Rows | Tripatha | QLever | Oxigraph |
|---|---|---|---|---|
| L1 | 0–3 | **0.5** | 4.1 | **0.5** |
| L2 | 12–212 | 9.9 | **4.4** | 12.5 |
| L3 | 9–26 | **0.4** | 2.2 | **0.4** |
| L4 | 46–66 | **1.4** | 2.8 | 1.7 |
| L5 | 49–275 | **4.1** | 5.9 | 6.2 |
| S1 | 0–9 | **1.4** | 66.2 | 1.5 |
| S2 | 16–128 | **1.0** | 3.4 | 1.5 |
| S3 | 0 | 5.4 | 5.6 | **4.7** |
| S4 | 0–5 | 3.6 | **3.1** | 43.8 |
| S5 | 0 | **0.4** | 5.2 | 3.2 |
| S6 | 0–16 | 2.0 | 2.9 | **1.3** |
| S7 | 0 | **0.2** | 4.6 | **0.2** |
| F1 | 0–10 | **2.5** | 9.0 | 8.3 |
| F2 | 1–7 | **1.1** | 20.9 | **1.1** |
| F3 | 1–2 | 0.6 | 10.2 | **0.4** |
| F4 | 0–60 | 4.0 | 39.2 | **2.0** |
| F5 | 13–51 | **1.2** | 10.0 | **1.2** |
| C1 | 26 | **11.7** | 21.8 | 12.9 |
| C2 | 6 | 47.8 | **35.7** | 291.0 |
| C3 | 444,070 | **289.8** | 354.7 | 16,693.3 |

</details>

**What it shows:**
- **Ahead overall, despite the extra work.** Over all 100 queries, Oxigraph takes 1.7 times Tripatha's time and QLever 4.2 times.
- **The 85 small queries** (linear, star, snowflake): 1.28 ms on Tripatha, 1.66 on Oxigraph, 6.79 on QLever.
  - Tripatha leads on the stars and the linear queries.
  - It trails Oxigraph on four templates (S3, S6, F3, F4), by 0.2 to 2 ms. There its time goes into building and hashing the keys of thousands of lookups, where Oxigraph joins integer IDs (SCALE-STUDY.md 4.26).
  - QLever spends a few milliseconds even on the smallest: 4.1 ms on L1, which returns 0 to 3 rows.
- **The complex queries:** Tripatha is 16 % faster than QLever and 7 times faster than Oxigraph.
  - QLever stays ahead on C2 (35.7 against 47.8 ms), and on L2 and S4.
  - C3, the largest answer (444,070 rows), takes 290 ms against QLever's 355. Its patterns whose values the query never returns are counted, not read (O26).
- **Since the comparison of 2026-11-20** (commit `70ca405`), the geometric mean of all 100 went from 2.6 to 2.3 ms. QLever and Oxigraph also measured 5 to 6 % faster on this run, so part of it is the machine. The changes in between are O5, O6, O8, O9 and O27, which reads planning's counters together and matters most on Accumulo and HBase.
- **The cost is on the write side.**
  - Loading took 99 s, against 11 s for each of the others. Tripatha loads through the write path of online updates: labelled cells, three orders, statistics and its entity index.
  - Its store is 0.99 GB, against 0.28 GB for QLever's index and 1.22 GB for Oxigraph.
- **What it doesn't measure:** concurrent queries, writes while querying, a cold cache, or data larger than memory. Like any one benchmark on one machine, it is a guide, not a ranking.

### Run it yourself

The comparison is one script, `tripatha.loader/compare.sh`. It needs macOS or Linux and about 3 GB of disk at the default scale. The first run takes 30 to 45 minutes; later runs, which reuse the data and the loaded stores, about 25.
1. **Install what it needs.** `tripatha.loader/compare.sh --check` lists anything still missing, with the command that installs it. [tripatha.loader/README.md](tripatha.loader/README.md) has the full list for macOS and for Linux:
   - a JDK 25 with GraalVM's `native-image`: Oracle GraalVM gives the profile-guided build measured here, GraalVM Community the default build;
   - Maven;
   - a C++ compiler and Boost, for WatDiv;
   - QLever.

   Oxigraph and WatDiv are downloaded and checked by the script.
2. **Run it** from the repository's root:

   ```bash
   tripatha.loader/compare.sh --build
   ```

   - It builds Tripatha's native executable.
   - It downloads, checks and builds WatDiv.
   - It generates the data (scale factor 100, about 10.9M triples) and 5 queries per template.
   - It loads every engine.
   - It measures each engine alone, with 1 run to warm up, then 3 warm-up and 10 timed runs per query.

   The data, the queries and the loaded stores are kept, so a second run only measures.
3. **Read the report:** `tripatha.loader/target/compare/compare-<date>.md`, and the same as CSV. It starts with each engine's version, settings, load time and size. It checks that the engines hold the same statements and return the same rows, and it leaves out of the means any query on which they don't. [Reading the comparison](tripatha.loader/README.md#reading-the-comparison) explains how each figure is made.

Options: `--scale N` (WatDiv's scale factor), `--per-template N`, `--engines tripatha,qlever,oxigraph`, `--only C1,C2` (templates), `--warmup N`, `--runs N`.

WatDiv is the work of G. Aluç, O. Hartig, M. T. Özsu and K. Daudjee: *[Diversified stress testing of RDF data management systems](https://doi.org/10.1007/978-3-319-11964-9_13)*, ISWC 2014. It publishes no license, so it isn't part of this repository: the script fetches it from its publisher. It seeds its generator from the clock, so each machine gets its own dataset: compare figures only within one.

### Tripatha's own benchmark

The **join benchmark** (`tripatha.loader/bench.sh`) measures Tripatha's own optimizations on any of its three backends, in-process, over a generated store of 10M triples. It is how each optimization in [SCALE-STUDY.md](SCALE-STUDY.md) was measured, and it isn't used against other engines. See [tripatha.loader/README.md](tripatha.loader/README.md).

## Storage backends

`tripatha.backend` (endpoint) or `--backend` (loader) picks the store: `accumulo` (the default), `hbase` or `rocksdb`. They behave the same, labels included; the integration tests run on all three. One difference: asking for labels the account doesn't hold fails on Accumulo, but returns fewer rows on HBase. The endpoint checks its labels at startup on HBase; code that embeds the Sail must grant its account the labels it asks for. Data is not moved between them.

**HBase** needs:
- **HBase 3.0.x**, with `org.apache.hadoop.hbase.security.visibility.VisibilityController` in both `hbase.coprocessor.master.classes` and `hbase.coprocessor.region.classes`, and `hbase.security.authorization=true`. Without that setting HBase ignores labels; Tripatha then refuses labelled data.
- **A normal account** for the endpoint and the loader: HBase superusers, and holders of its `system` label, see every cell whatever they ask for. Once labels have been registered through Tripatha (below), a store checks this when it opens and refuses to run as one. Before that, there is no canary to check against: it only logs a warning.
- **Registered labels.** HBase only accepts labels registered in advance. Register them, as a superuser, with the loader:
  ```bash
  tripatha.loader/run.sh labels --backend hbase --hbase-site /etc/hbase/conf/hbase-site.xml --labels A,B --grant-to tripatha
  ```
  This also writes a *canary* cell per label, which the endpoint reads at startup to check that its account holds every label of `tripatha.sparql.authorizations` (HBase would otherwise drop the missing ones silently).
- **The coprocessor jar**, for fast statistics, and for stars intersected and lookups counted in the servers: put `tripatha.hbase.coprocessor/target/tripatha.hbase.coprocessor-<version>.jar` on the region servers' classpath, or give its hdfs path as `tripatha.hbase.counters-jar`. Without it, set `tripatha.hbase.counters=increment`: HBase increments work everywhere but lock a row and sync the WAL for every counter write, and stars are intersected and lookups counted on the client.
  - Configure the same mode for every endpoint and loader of a store. A writer reads the counter table's mode from its descriptor, but an account that may not read descriptors (with HBase's `AccessController`, that needs ADMIN or CREATE) falls back to its configured mode. Writing in the wrong mode loses counts.
- Literals of more than about 32 KB (rows over 32,767 bytes, HBase's limit) are refused.
- **Finding the cluster:** give the cluster's `hbase-site.xml` (`tripatha.hbase.site`), or only a ZooKeeper quorum (`tripatha.hbase.conf[hbase.zookeeper.quorum]`), in which case Tripatha finds the cluster through ZooKeeper. HBase 3's client otherwise asks its masters (`hbase.client.bootstrap.servers`, by default `localhost:16000`).

**Accumulo**, with the entity index on (`tripatha.entity-index`), intersects its shards in the tablet servers if they have `tripatha.accumulo.iterators/target/tripatha.accumulo.iterators-<version>.jar` on their classpath (`lib/ext`). It depends only on Accumulo. The same jar counts in the tablet servers the lookups of patterns whose object a query never uses (`RangeCountingIterator`), so one number per range crosses the network instead of every match. Without it, stars are intersected and lookups counted on the client: same answers, more data moved. The store checks once per table and logs which it uses. RocksDB intersects with native seeks, in the process. **HBase** intersects in its region servers, with the coprocessor jar above: the store attaches the endpoint `org.tripatha.hbase.coprocessor.IntersectionEndpoint` to the index tables it creates, and logs which way it intersects. It also attaches `org.tripatha.hbase.coprocessor.RangeCountEndpoint` to its statement tables, which counts the lookups of patterns whose object a query never uses; on a table created before it, add it the same way. An index table created before this release is intersected on the client, which is about as fast as without the index. To add the endpoint to one: `alter '<prefix>entity', METHOD => 'table_att', 'coprocessor' => '|org.tripatha.hbase.coprocessor.IntersectionEndpoint|1073741823|'` (with the jar's hdfs path before the first `|` if it isn't on the classpath).

**RocksDB** is a store in a local directory, in the endpoint's or the loader's own process: nothing to install or run besides Tripatha.

```bash
java -jar tripatha.sparql/target/tripatha.sparql-1.0.0-SNAPSHOT.jar --tripatha.backend=rocksdb --tripatha.rocksdb.path=/var/lib/tripatha
tripatha.loader/run.sh prepare --rocksdb-path /var/lib/tripatha --table-prefix tripatha_ --triples 10M
```

- **One process at a time** can open the directory. Stop the endpoint to run the loader on it, and load with `--threads`, not with one process per `--part`.
- **Labels are checked by Tripatha, not by a server.** There are no accounts, so every label counts as held: a request reads what its `auths` allow, up to `tripatha.sparql.authorizations`. Whoever can read the directory can read every statement, so protect it with file permissions.
- **A flush is atomic:** the statement tables, the indexes and the statistics of the writes since the last flush are stored together or not at all.
- **Rewriting a statement** keeps the last write. Accumulo and HBase keep the write with the newest timestamp, which differs only when timestamps are set out of order.
- Splits mean nothing here: `prepare` plans none.
- It is one database on one machine: no replication, and its size and speed are that machine's.

### Standalone: one program, no cluster, no JVM

`tripatha.standalone` is the endpoint and its query page with the RocksDB backend and nothing of Accumulo, HBase or Hadoop's clusters. It builds two things:

```bash
# an executable jar (needs Java 25)
mvn -pl tripatha.standalone -am package -DskipTests -DskipITs
java -jar tripatha.standalone/target/tripatha-1.0.0-SNAPSHOT.jar --tripatha.backend=rocksdb --tripatha.rocksdb.path=/var/lib/tripatha

# a native executable (needs GraalVM 25 with native-image to build; nothing to run)
mvn -Pnative -pl tripatha.standalone -am package -DskipTests -DskipITs
tripatha.standalone/target/tripatha --tripatha.backend=rocksdb --tripatha.rocksdb.path=/var/lib/tripatha
```

- The native executable takes the same settings as the jar, as arguments or as `TRIPATHA_*` environment variables. It starts in well under a second and uses far less memory than the JVM.
- It is built for the machine it is compiled on (Linux x64 and arm64, macOS). It unpacks RocksDB's native library into the temporary directory when it starts, so that directory must be writable.
- **Give a server a minimum heap** as well as a maximum, for example `-Xms4g -Xmx4g` before the other arguments. The executable's collector sizes its young generation from the minimum heap. Without one it stays small and collects every few tens of milliseconds on large queries.
  - On WatDiv's complex queries, `-Xms6g` made it about 21 % faster, and C3 about 38 %.
  - The cost: its memory then peaks at 1.7 GB instead of 0.4 GB.
- **A faster build:** `tripatha.standalone/build-optimized.sh` builds it with profile-guided optimization. On WatDiv it was 11–16 % faster overall, and 10–19 % on the complex queries.
  - It needs [Oracle GraalVM](https://www.graalvm.org/downloads/) for JDK 25, which is free to use and to redistribute at no charge under the [GraalVM Free Terms and Conditions](https://www.oracle.com/downloads/licenses/graal-free-license.html), but isn't open source. GraalVM Community, used by CI and the Docker image, has no profile-guided optimization.
  - It trains an instrumented build on the join benchmark's queries over a small generated store, then builds from that profile. That takes about 5 minutes after the modules are built.
- `tripatha.standalone/smoke-test.sh http://localhost:8080` runs a started endpoint through its result formats, updates, searches, triple terms, tracing and the query page's files. It empties the store first.
- The loader is not part of it: load through SPARQL updates, or with the loader jar while the endpoint is stopped.

## Running the SPARQL endpoint

Build the endpoint, let the wizard write a launch script, then start it:

```bash
mvn -pl tripatha.sparql -am package -DskipTests   # tripatha.sparql/target/tripatha.sparql-<version>.jar
tripatha.sparql/configure.sh                        # asks for the settings, writes ./tripatha-sparql.sh
./tripatha-sparql.sh                                # asks for the Accumulo password, then starts
```

**What the wizard asks for:**
- **Java and the jar:** the paths, and the heap size.
- **Connection:** either the ZooKeeper servers, instance name and user, or an `accumulo-client.properties` file.
- **Password source:** where the launch script gets the password when it starts: the terminal, the `TRIPATHA_ACCUMULO_PASSWORD` variable, or a file only you can read. The password is never stored in the script.
- **Store:** table prefix, text and geo indexes, inference and statistics.
- **Security:** the visibility labels requests may read, and the label written on inserted data.
- **Server:** port, listen address, query timeout, the longest timeout a request may ask for, and whether requests may be traced.

**Options and overrides:**
- `--output FILE` writes the script elsewhere.
- `--defaults` accepts every default without asking.
- Arguments given to the launch script are passed to the endpoint, for example `./tripatha-sparql.sh --server.port=9090`.

Without the wizard, pass the settings as arguments. Pass the password in the environment, not as an argument: other users can see arguments with `ps`.

```bash
read -rs -p 'Accumulo password: ' TRIPATHA_ACCUMULO_PASSWORD; export TRIPATHA_ACCUMULO_PASSWORD
java -jar tripatha.sparql/target/tripatha.sparql-1.0.0-SNAPSHOT.jar \
  --tripatha.accumulo.instance=accumulo --tripatha.accumulo.zookeepers=zk1:2181 --tripatha.accumulo.user=tripatha
```

On HBase, give the client configuration instead, and no password:

```bash
java -jar tripatha.sparql/target/tripatha.sparql-1.0.0-SNAPSHOT.jar --tripatha.backend=hbase \
  --tripatha.hbase.site=/etc/hbase/conf/hbase-site.xml --tripatha.sparql.authorizations=A,B
```

Instead of the connection arguments, `--tripatha.accumulo.client-properties` can point at an `accumulo-client.properties` file, such as the one `tripatha.loader/configure.sh` writes. If the file has no password, it comes from `TRIPATHA_ACCUMULO_PASSWORD`. Settings can also come from environment variables, for example `TRIPATHA_ACCUMULO_ZOOKEEPERS`. Defaults are in `tripatha.sparql/src/main/resources/application.yaml`.

| Setting | Default | Meaning |
|---|---|---|
| `tripatha.backend` | `accumulo` | The store: `accumulo`, `hbase` or `rocksdb` |
| `tripatha.rocksdb.path` | | With `rocksdb`: the directory of the store, created if missing |
| `tripatha.hbase.site` | `[]` | `hbase-site.xml` files (and `core-site.xml` for Kerberos) |
| `tripatha.hbase.conf[KEY]` | | Any HBase client setting, for example `tripatha.hbase.conf[hbase.zookeeper.quorum]=zk1,zk2` |
| `tripatha.hbase.user` | | With simple authentication only, the account to connect as |
| `tripatha.hbase.counters` | `coprocessor` | How new counter tables store deltas: `coprocessor` or `increment` |
| `tripatha.hbase.counters-jar` | | The hdfs path region servers load the coprocessor from, when it isn't on their classpath |
| `tripatha.hbase.labels-table` | `tripatha_labels` | The label canary table |
| `tripatha.table-prefix` | `tripatha_` | Prefix of the Tripatha tables, which are created if missing |
| `tripatha.inference` | `false` | Query-time OWL/RDFS inference |
| `tripatha.inference-refresh` | `5m` | How often the ontology is reloaded. Ontology changes take effect after the next reload. |
| `tripatha.stats` | `true` | Keep cardinality statistics on write and use them for join ordering |
| `tripatha.join-batch-size` | `1000` | Left-hand join rows whose lookups are fetched together in one batch scan, at most (the first batch is 64 rows, and each next one doubles up to this size); `1` turns batching off |
| `tripatha.entity-prefetch` | `true` | Fetch the attributes a query reads for a batch of entities (its joins and `OPTIONAL`s on them) in one batch scan, instead of one per join or per row |
| `tripatha.join-merge-ratio` | the backend's: 35 on Accumulo, 20 on HBase, 8 on RocksDB | Merge joins of a star's constant patterns (`?x :p :a ; :q :b`): a pattern up to this many times longer than the star's shortest is read in subject order and intersected with it, instead of looked up once per row; `0` turns them off |
| `tripatha.join-merge-single` | `10000` | A star pattern on its own (`?x a :C`, alone, counted, or driving a chain) whose list is at least this long is read like a merge of one list: its subjects only, in those slices; `0` leaves it to a plain scan |
| `tripatha.join-lookup-threads` | the store's: `4` on RocksDB, `1` on Accumulo and HBase | A batch of lookups whose ranges can't overlap (an entity chain's links) is read on this many threads, each reading, decoding and matching its share; `1` reads every batch on the request's thread |
| `tripatha.join-prefetch-ahead` | the store's: on for RocksDB, off for Accumulo and HBase | An entity prefetch (a query's joins and `OPTIONAL`s over a batch of entities) fetches its next window on another thread while the current one is joined |
| `tripatha.join-count-unused` | `true` | A joined pattern whose object no other part of the query uses (`?p :knows ?f`, `?f` nowhere else) has its lookups counted by the store instead of read, and each row repeated that many times |
| `tripatha.join-merge-slices` | `4` | Merge joins read their lists in up to this many slices of the subject space, cut at the store's split points and intersected on threads of their own; `1` reads each list whole |
| `tripatha.text-index` | `false` | Keep the full-text index (see [Search](#search)) |
| `tripatha.text-index-predicates` | `[]` | Predicates whose literals are text-indexed; empty means all |
| `tripatha.geo-index` | `false` | Keep the geospatial index |
| `tripatha.entity-index` | `false` | Keep the entity index (`<prefix>entity`): every subject filed under its predicate and object in a fixed number of shards, so stars of constant patterns are intersected shard by shard, and their counts, or a class's size, are counted by the store. Raises the store's version to 1.2.0 |
| `tripatha.entity-index-shards` | the store's | The entity index's shard count, fixed when it is built: about one per 200M triples of the expected size, 16 at least |
| `tripatha.rebuild-indexes` | `false` | At startup, build indexes that don't cover the stored data yet (for a large store, use the loader's `rebuild` instead) |
| `tripatha.sparql.authorizations` | `[]` | The most a request may read with. The store account must hold all of them; on HBase the endpoint checks it at startup. |
| `tripatha.sparql.default-visibility` | `""` | Visibility label written on inserted statements |
| `tripatha.sparql.allow-request-visibility` | `false` | Let an update set its own label with `visibility=` |
| `tripatha.sparql.query-timeout` | `120s` | Maximum query or update time, unless the request sets `timeout=` |
| `tripatha.sparql.max-query-timeout` | `30m` | Longest time a request may ask for with `timeout=`; `0s` means no limit |
| `tripatha.sparql.allow-trace` | `true` | Let requests be traced (`trace=` and `/sparql/trace`, see [Tracing a query](#tracing-a-query)) |

To try the endpoint without a cluster (add `-Dspring-boot.run.jvmArguments=-Dtripatha.test.backend=hbase` for HBase):

```bash
mvn -pl tripatha.sparql -am install -DskipTests
mvn -pl tripatha.sparql spring-boot:test-run -Dspring-boot.run.main-class=org.tripatha.sparql.DevServer
```

Or with Docker, which needs neither a JDK nor Maven. It runs the same throwaway server, with both search indexes on:

```bash
docker compose up --build                     # then open http://localhost:8080/
TRIPATHA_BACKEND=hbase docker compose up --build   # on HBase instead of Accumulo
TRIPATHA_BACKEND=rocksdb docker compose up --build # on RocksDB: the data is kept in a volume
```

The first build downloads the dependencies and takes several minutes. `TRIPATHA_PORT` changes the published port.
- **Accumulo and HBase** run on a JVM with an in-process cluster. The data is lost when the container stops.
- **RocksDB** runs the [native executable](#standalone-one-program-no-cluster-no-jvm) on a small base image: it starts in about a second, and the data is kept in the `tripatha-data` volume until `docker compose down -v`. Compiling the executable takes a few more minutes and several GB of memory.

Either way this is for trying Tripatha out, not for running it.

### Query page

Open the server's root URL (for example `http://localhost:8080/`) for a query page built on [YASGUI](https://github.com/zazuko/Yasgui).

**Writing queries:**
- Syntax highlighting, autocompletion and several query tabs, which the browser remembers.
- An **Examples** menu with queries to load sample data (Rome landmarks), browse it, size the named graphs, run text and geo searches, and try RDF 1.2 annotations, triple terms and text directions.
- SPARQL 1.2 syntax is accepted and highlighted. YASGUI itself checks SPARQL 1.1 only, so a query that uses 1.2 syntax is checked by the server when it runs, not while you type.
- Every new tab starts with the `rdf:`, `rdfs:`, `xsd:`, `text:`, `geo:`, `geof:`, `uom:` and `stats:` prefixes.

**Result tabs:**
- **Table** and **Response**, as in YASGUI. The table shows triple terms as `<<( s p o )>>` and marks a literal's text direction (`"..."@ar--rtl`).
- `CONSTRUCT` and `DESCRIBE` results are fetched as Turtle. When a result holds triple terms or directions, the page fetches it again as N-Triples, which it can read with those in them, so that is what **Response** then shows. A query written in SPARQL 1.2 is fetched as N-Triples straight away.
- **Map** draws every `geo:wktLiteral` in the results on an OpenStreetMap background. The background tiles need internet access; the rest of the page doesn't.
- **Chart** shows bar, line and pie charts of numeric results.

**Header fields,** remembered by the browser:
- **Authorizations** is sent as `auths=`, so a query reads with fewer labels than the server allows.
- **Timeout (s)** is sent as `timeout=`, so a long query can run for longer than the server's default, up to `tripatha.sparql.max-query-timeout`. Empty uses the default.
- **Trace** opens a panel on the right that shows what each query does in the store while it runs (see [Tracing a query](#tracing-a-query)).

The page's JavaScript and CSS are packaged into the jar at build time. Their licenses are listed at `/vendor/THIRD-PARTY-NOTICES.txt`.

### Using it

The endpoint is `/sparql` and follows the SPARQL Protocol, for SPARQL 1.1 and 1.2 queries and updates.

```bash
# update
curl -X POST http://localhost:8080/sparql -H 'Content-Type: application/sparql-update' \
  --data 'INSERT DATA { GRAPH <urn:g> { <urn:alice> <urn:knows> <urn:bob> } }'

# query (JSON results)
curl http://localhost:8080/sparql -H 'Accept: application/sparql-results+json' \
  --data-urlencode 'query=SELECT * WHERE { GRAPH ?g { ?s ?p ?o } }'
```

Supported forms:
- **Queries:** `GET ?query=`, `POST` with a form field `query=`, or `POST` with an `application/sparql-query` body.
- **Updates:** `POST` with a form field `update=`, or `POST` with an `application/sparql-update` body.
- **Dataset parameters:** `default-graph-uri`, `named-graph-uri`, `using-graph-uri` and `using-named-graph-uri`.

Result formats are chosen from `Accept`:
- **SELECT:** SPARQL JSON (default), SPARQL XML, CSV, TSV.
- **ASK:** JSON (default), XML.
- **CONSTRUCT/DESCRIBE:** Turtle (default), N-Triples, N-Quads, JSON-LD, RDF/XML, TriG.

Every format carries triple terms and text directions, with these exceptions:
- **CSV** has no types, as in SPARQL 1.1: a triple term is written as text, without the brackets around its IRIs.
- **JSON-LD** has no triple terms: each is written as an IRI starting with `urn:rdf4j:tripleTerm:`. Text directions are kept.

An unsupported `Accept` gets `406`. A malformed query or update gets `400`.

Tripatha extensions:
- `auths=A,B` narrows the read authorizations for one request; asking for more than is configured gets `403`.
- `visibility=A&B` labels the statements an update inserts, if `allow-request-visibility` is on.
- `timeout=600` sets this request's time limit, in seconds. More than `tripatha.sparql.max-query-timeout` gets `403`; anything but a positive whole number gets `400`.
- `trace=<id>` streams what the request does in the store to `/sparql/trace/<id>` (see [Tracing a query](#tracing-a-query)).

The endpoint has no HTTP authentication. Run it behind a proxy that authenticates users, or add Spring Security.

## Tracing a query

A trace shows how a query or an update maps onto the store, live:
- **the plan:** RDF4J's query tree as parsed, then after Tripatha's rewrites, with the join order, the cardinality estimates and which joins are batched (`RyaBatchedJoin`, or `RyaEntityPrefetch` for a chain of attribute lookups);
- **every scan:** the table, the operation (Accumulo's `Scanner`, `BatchScanner`, `BatchDeleter`; HBase's `Scan`, `Get`, `BatchScan`), the ranges (with the separator bytes shown as `⟨00⟩`, `⟨01⟩`, `⟨ff⟩`), the column families and server-side filters, the authorizations, how many entries came back and the time spent in the store;
- **why each scan happened:** for example `query planning › statistics: PREDICATE count of …`, `lookup <urn:alice> <urn:knows> ? on SPO`, or `batched join: 5 right-hand lookups fetched together`;
- **every flush of writes:** the mutations, cells, deletes and bytes sent to each table;
- **progress of long scans:** about once a second, each scan that has run for a second reports the entries it has read so far, the last row it reached, and how long it has been waiting for the servers;
- **a summary** per table.

**On the query page:** tick **Trace** in the header.
- **The panel:** a panel on the right of the editor and results fills in while each query runs. Its header shows how long the query has been running, and a running scan's card shows where it has got to.
- **Query tabs:** each query tab has its own trace, and the panel shows the selected tab's. A query keeps being traced while you look at another tab.
- **Resizing:** drag its left edge (or focus it and use the arrow keys; double-click resets the width).
- **Folding:** fold it into a tab on the right edge with **⟩**; the tab shows a dot while the selected tab's traced query runs.
- **Memory:** the browser remembers the width and whether the panel is folded. On narrow screens the panel sits under the results.

**Over HTTP:** pick a random id of 16 to 64 letters, digits, `-` or `_`. Read the stream, and send the request with the same id:

```bash
ID=$(uuidgen)
curl -N http://localhost:8080/sparql/trace/$ID &        # Server-Sent Events: note, plan, scan, progress, scan-end, flush, summary, end
curl http://localhost:8080/sparql --data-urlencode 'query=SELECT * WHERE { ?s ?p ?o } LIMIT 5' --data-urlencode "trace=$ID"
```

- **Order:** either side may come first; events wait (up to 2 minutes) for the stream.
- **One use per id:** each id serves one request; reusing it gets `409`.
- **Closing:** the stream always ends with an `end` event whose status is `ok`, `error` or `timeout`.
- **Size:** a trace reports up to 1,000 scans and flushes one by one; the summary counts them all.

**Without the endpoint:** the same trace is available from Java. Every backend's store reports to the thread's trace, so tracing the thread that runs a query is enough:

```java
try (StoreTrace.Scope scope = StoreTrace.start(event -> System.out.println(event));
        TupleQueryResult result = conn.prepareTupleQuery(query).evaluate()) {
    result.forEach(row -> { });
}
```

Tracing costs nothing for requests that don't ask for it. `tripatha.sparql.allow-trace=false` turns it off on the endpoint.

## Approximate counts

Counting the statements of each graph with `COUNT(*)` reads every statement, which takes minutes on a large store. The statistics answer the same question approximately, with a handful of reads:

```sparql
PREFIX stats: <urn:tripatha:stats#>
SELECT ?graph ?statements WHERE { ?graph stats:approxSize ?statements }
ORDER BY DESC(?statements)
```

- **What it returns:** one row per named graph with data the request may see, with its number of statements as an `xsd:long`. The default graph has no IRI, so it isn't listed. `stats:approxSize` combines with the rest of a query like any pattern.
- **Classes and predicates** come from the same counters, with a handful of reads too:

  ```sparql
  PREFIX stats: <urn:tripatha:stats#>
  SELECT ?class ?members WHERE { ?class stats:approxInstances ?members } ORDER BY DESC(?members)
  SELECT ?statements WHERE { <http://example.org/Person> stats:approxInstances ?statements }
  SELECT ?predicate ?statements WHERE { ?predicate stats:approxTriples ?statements }
  ```

  `stats:approxInstances` counts a class's `rdf:type` statements as stored, without inference. `stats:approxTriples` counts a predicate's statements. With the class or predicate given, a single counter is read, so the answer costs the same at any size.
- **Requirements:** statistics, which are on by default (`tripatha.stats`).
- **Approximate:**
  - a statement inserted twice counts twice;
  - a predicate used for the first time in the last 5 minutes may not be counted yet.

## RDF 1.2

Tripatha stores and queries RDF 1.2 data, on every backend:

```sparql
# An annotation says something about a statement. ~ names it; without ~ it is a blank node.
INSERT DATA {
  <urn:alice> <urn:knows> <urn:bob> ~ <urn:claim1> {| <urn:since> 2019 ; <urn:source> <urn:survey> |} .
  <urn:bob> rdfs:label "بوب"@ar--rtl .          # a literal with a text direction
}

# The statements with their annotations
SELECT ?who ?since WHERE { <urn:alice> <urn:knows> ?who {| <urn:since> ?since |} }

# The same, written with a reified triple, and with the reifier
SELECT ?who ?claim WHERE { << <urn:alice> <urn:knows> ?who ~ ?claim >> <urn:since> ?since }

# Triple terms are values: <urn:claim1> rdf:reifies <<( <urn:alice> <urn:knows> <urn:bob> )>>
SELECT ?claim ?term (OBJECT(?term) AS ?who) WHERE { ?claim rdf:reifies ?term FILTER(isTRIPLE(?term)) }
```

- **Triple terms** (`<<( s p o )>>`) are stored as object values, nested to any depth, and compared exactly: deleting one deletes that term only. `TRIPLE`, `SUBJECT`, `PREDICATE`, `OBJECT` and `isTRIPLE` work on them.
- **Annotations and reified triples** are shorthand for `rdf:reifies` statements, in data and in patterns. Annotating a statement doesn't assert it twice, and deleting an annotation leaves the statement.
- **Text direction:** `"..."@ar--rtl` and `"..."@ar` are different values. `LANGDIR`, `hasLANG`, `hasLANGDIR` and `STRLANGDIR` work on them.
- **Speed:** a pattern with a triple term reads only what it needs. With the term's subject known (`?claim rdf:reifies <<( <urn:alice> ?p ?o )>>`), it reads just the range of terms about that subject; a [trace](#tracing-a-query) shows it.
- **Existing stores** need no migration: RDF 1.1 data is stored exactly as before.
- Visibility labels, named graphs, statistics and the search indexes treat these statements like any others.

Limits:
- A triple term can only be an object, as RDF 1.2 defines.
- A triple term is part of its statement's row key, so each level of nesting makes the row longer. On HBase a row can't exceed 32,767 bytes.
- An older Tripatha can't read a store that holds triple terms: its scans fail with "Bytes not deserializable" when they reach one.
- Inference doesn't look inside triple terms.

Some behaviour comes from RDF4J 6.1's SPARQL parser:
- In a `WHERE` pattern, the reifier after `~` must be an IRI or a blank node; to get it as a variable, use the reified form (`<< s p o ~ ?r >>`). A `~ <reifier>` with no `{| |}` block after it is ignored there.
- In a `WHERE` pattern, an annotation after a list of objects (`?s ?p ?a, ?b {| ... |}`) is refused, and only the first `{| |}` block of a statement is used.
- `STR()` of a triple term returns a string instead of raising an error.
- Turtle and TriG results write annotations as plain `rdf:reifies` statements, never as `{| |}`.

## Search

### Text

`text:matches(?literal, "query")` is true when a string literal (`xsd:string` or language-tagged) satisfies a text query:

```sparql
PREFIX text: <urn:tripatha:text#>
SELECT ?doc ?label WHERE {
  ?doc rdfs:label ?label .
  FILTER(text:matches(?label, "alice AND (bob OR caro*) AND NOT \"old draft\""))
}
```

- **Operators:** `AND` (or just a space), `OR`, `NOT`, parentheses, `"exact phrases"` and `prefix*`. They are recognised only in upper case, so `cats and dogs` searches for three words.
- **Matching:** case and accents are ignored, so `café` matches `Cafe`.
- **Words:** a word is a run of letters and digits, so `e-mail` is the phrase "e mail". Each Chinese or Japanese ideograph or hiragana character is a word of its own.
- **Not supported:** stemming, stop words and ranking. `running` doesn't match `run`.
- **Errors:** a malformed query gets a `400`.

### Geo

The GeoSPARQL functions (`geof:sfWithin`, `geof:distance`, `geof:relate`, the `eh*` and `rcc8*` relations, …) work on `geo:wktLiteral` values:

```sparql
PREFIX geo:  <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
PREFIX uom:  <http://www.opengis.net/def/uom/OGC/1.0/>
SELECT ?place WHERE {
  ?place geo:hasGeometry ?g . ?g geo:asWKT ?wkt .
  FILTER(geof:distance(?wkt, "POINT(12.49 41.89)"^^geo:wktLiteral, uom:metre) < 5000)
}
```

- Coordinates are longitude/latitude (CRS84).
- GML and WKT with a CRS prefix are not supported.
- WKT that doesn't parse is stored but never matches.

### The indexes

Without an index, these filters still work: they check every candidate, so they are slow on large data.

`tripatha.text-index` and `tripatha.geo-index` add an index table each (`<prefix>text`, `<prefix>geo`). The text index also keeps each word's number of postings (`<prefix>textcount`, a few MB), so a search for a common word and a rare one is driven by the rare one. On Accumulo, give the endpoint's and the writers' accounts that table like the others. A store whose text index is older than the counts plans as before until the index is rebuilt.

**What the index can speed up:**
- **Text:** queries with at least one word that every match must contain. `NOT x`, or prefixes under 3 characters, fall back to scanning.
- **Geo:** relations that imply intersection (`sfWithin`, `sfIntersects`, `sfContains`, `sfOverlaps`, `sfTouches`, `sfCrosses`, `sfEquals` and their `eh*`/`rcc8*` equivalents, or `geof:relate` with such a pattern). Also `geof:distance(?g, point, unit) < d` (or `<=`, or reversed).

**How results stay correct:**
- The index only narrows the candidates down.
- Every hit is read back from the statement tables with the reader's authorizations, and the filter always makes the exact check.
- So results are exactly the same as without the index, and nothing under a label the reader lacks is returned.

**When the index is not used:**
- The query sets Tripatha's `query.limit` option. SPARQL `LIMIT` is fine.
- An embedded query sets the binding `query.useindex` to `false`.
- The plan starts elsewhere and looks up each subject directly. That lookup is cheaper anyway.

**Lifecycle:**
- **Turning an index on for a store that already has data:** queries don't use the index until it is built.
  - **For a large store,** run the loader's `rebuild` with the endpoint's index options (`tripatha.loader/run.sh rebuild --table-prefix P --entity-index …`). It reads `spo` on parallel threads and processes, imports the index as sorted files, and resumes after a failure. The endpoint may keep writing meanwhile with the index on: deletes are recorded and replayed, graph drops are refused until the job is done, and queries start using the index on their own within a minute of it.
  - **For a small one,** start once with `tripatha.rebuild-indexes=true`.
  - **Either way** the build reads every statement with the account's labels, so it must hold every visibility label in the store.
- **Turning an index off:** delete its table (for the text index, `<prefix>textcount` too), or the store refuses to start. An index that stopped receiving writes would silently miss data.
- **Changing the indexed predicates:** this also needs a rebuild.

## Loading synthetic data

`tripatha.loader` generates a synthetic dataset and writes it into a store in parallel, through the normal write path, for stress tests up to billions of triples.

**The data:**
- about 7.5 triples per entity, over 11 classes: people, organizations, companies, cities, landmarks, events, products, reviews, articles, sensors and observations;
- labels and descriptions in 11 languages, numbers, dates, and power-law links between entities;
- points and polygons, named graphs, and optional visibility labels;
- optionally RDF 1.2 data: with `--rdf12-fraction F`, that share of the links between entities is annotated (a reifier of the link's triple term, with a certainty and sometimes a date), and that share of the language-tagged labels gets a base direction. Everything else is the dataset the same options give without it. The default is 0, an RDF 1.1 dataset.

Each entity depends only on the seed and its number, so any process can generate any part of the dataset, and writing a part again produces the same keys.

```bash
tripatha.loader/configure.sh   # once: asks for the store (Accumulo, HBase or RocksDB) and the connection, writes a file in ~/.tripatha
R=tripatha.loader/run.sh       # builds the jar on first use; --build rebuilds it
C="--accumulo-properties $HOME/.tripatha/accumulo-client.properties"   # HBase: C="--hbase-properties $HOME/.tripatha/hbase.properties"
                                                                     # RocksDB: C="--rocksdb-path $HOME/.tripatha/rocksdb"

$R check $C                                                # connection, servers, the account's rights
$R dry-run --triples 1B --text-index --geo-index           # no cluster: speed, bytes per triple, network ceiling, stored counters
$R prepare $C --table-prefix big_ --triples 1B --text-index --geo-index
$R load    $C <the dataset options prepare printed> --threads 8
$R load    $C <same options> --resume                       # after Ctrl-C or a failure
$R build   $C <the dataset options prepare printed> --threads 8 --work-dir /data/build   # or: files imported
$R verify  $C <same options> --sample 1000
$R drop    $C --table-prefix big_ --confirm big_
```

- **Connection:** `tripatha.loader/configure.sh` asks which store the loader writes to, writes a connection file readable only by you, and can test it right away.
  - Its first question is the store (1 Accumulo, 2 HBase), which matters when answers are piped in. `--output FILE` writes elsewhere, and `--defaults` answers from the environment without asking (`TRIPATHA_BACKEND=hbase` picks HBase).
  - **Accumulo:** an `accumulo-client.properties` file, for `--accumulo-properties`. The password can be stored in the file, or left out and read from `TRIPATHA_ACCUMULO_PASSWORD` when the loader starts. Without a file, set `TRIPATHA_ACCUMULO_ZOOKEEPERS`, `TRIPATHA_ACCUMULO_INSTANCE`, `TRIPATHA_ACCUMULO_USER` and `TRIPATHA_ACCUMULO_PASSWORD`. `--zookeepers`, `--instance` and `--user` also work, but the password is never accepted as an argument.
  - **HBase:** a file of HBase settings, for `--hbase-properties` (which selects HBase): the cluster's `hbase-site.xml` or a ZooKeeper quorum, the account, and the counter mode. Without a file, give `--backend hbase` with `--hbase-site /etc/hbase/conf/hbase-site.xml` (or `--hbase-conf 'hbase.zookeeper.quorum=zk1,zk2'`) and `--hbase-user`. Options win over the file, and the file over `TRIPATHA_HBASE_*`.
    - Every loader and endpoint of a store must use the same counter mode. If the coprocessor isn't on the region servers' classpath, give `prepare` its hdfs path with `--hbase-counters-jar` (the wizard asks for it).
    - Register the dataset's labels first, as an HBase superuser: `labels --labels A,B --grant-to ACCOUNT`, with `--hbase-user=SUPERUSER` (or `--hbase-user=` for the process user). The loading account itself must not be a superuser: the loader refuses one.
- **`prepare` first, once per dataset:**
  - creates the tables with the indexes ready and splits them at split points sampled from the data:
    - each table into tablets or regions of about `--tablet-size` (10G) on disk, from its estimated size;
    - into at least 4 per server;
    - `--splits N` asks for N per table instead.

    The estimate is the keys a sample of entities writes, over a compression of `--compression` (7, what the statement tables measured). At 1T that is about 2,000 splits each for `spo`, `po` and `osp`. On HBase the points are split in rounds, all regions at once, so thousands take a few minutes, not hours;
  - makes `--tablet-size` the size at which Accumulo or HBase splits the tables on their own (Accumulo's default is 1 GB, which at 1T would make about 160,000 tablets);
  - writes the ontology, 200 countries and a description of the dataset;
  - prints the `load` commands and the endpoint flags that match.
  `load` refuses a store prepared for another dataset or with other index settings.
- **Parallelism:** `--threads` writers per process. `--part K --parts N` divides the entities between processes, on one or more machines.
- **Resuming:** progress is saved after each flush (every `--commit-interval`), and `--resume` continues from there. After a crash with `--durability` below `sync`, run `verify`.
- **`build` instead of `load`:** the same cells, written as sorted files in the store's own format and imported, bypassing the write path.
  - **Phases:** it sorts the dataset into runs in `--work-dir`, merges them into one file per table and tablet, then imports them. `--phase sort|merge|import` runs one, and `--resume` continues after a failure.
  - **Counters:** it leaves out every counter total of 1 except `p`'s (with `--stats-ones skip`), so the built `eval` table is much smaller. The datatype markers stay.
  - **Speed:** at 10M triples with every index:
    - RocksDB: 64 s against `load`'s 96 s. The store comes out compacted: 2.7 GB against 8.0 GB before `load`'s compaction.
    - The Accumulo test cluster: 70 s against 906 s. Its counter table is 26 MB instead of 251 MB.
    - The HBase test cluster, at 3M triples: 31 s against 45 s. Its write path runs in-process, so it gains least there.
  - **Accumulo:** files are RFiles imported with a load plan, one per tablet.
    - The account needs the bulk-import permission on the tables.
    - The tablet servers must read the work directory. It is a local path today (the build's progress files are local), so use one they can read too: the same machine, or a mounted shared file system. HDFS work directories come with the first cluster run.
    - With `--part K --parts N`, the parts share the sort and the merge; one process then runs `--phase import`.
  - **HBase:** HFiles whose visibility tags hold the labels' ordinals, bulk-loaded as the loading account.
    - **Ordinals:** HBase shows them to superusers only, so the `labels` command records each one in its label's canary. Labels registered before that need `labels` run once more before a build.
    - **Labels held:** the loading account must hold every label of the data.
    - **Counter mode:** the build needs the coprocessor mode, the default. In increment mode it refuses, because an imported counter would replace the store's instead of adding to it: use `load` there.
  - **Space:** the work directory needs about 160 bytes per triple, compressed, until the import. `--spill-memory-mb` (1024, all threads together) is the memory for cells waiting to be sorted.
  - The progress file holds a watermark and the few blocks done above it, so it stays a few bytes at any size; 1T in one process is about 134 million blocks.
  - A file written by an earlier loader is read and rewritten in this form, which earlier loaders refuse rather than starting over (that would add the done blocks' counters twice).
- **On a Spark cluster:** `tripatha.spark` runs the bulk build as a Spark job, for Accumulo. Executors write the cells into RFiles on Accumulo's own volume (S3 or HDFS), and the driver imports them. It reads this dataset, or N-Triples and N-Quads files. Spark 4.2 on Java 25; see [tripatha.spark/README.md](tripatha.spark/README.md). At 10M triples on one machine it takes 63.5 s against `build`'s 50.8 s, and loads WatDiv's 10.95M triples from N-Triples in 52.3 s; on a cluster every executor sorts and writes.
- **Statistics:** a load doesn't write a subject's counters whose total is 1. The planner reads a missing counter as 1, so join orders are unchanged. `dry-run` reports the counter cells a load leaves once they are summed, per family, over the first `--counter-sample` entities (20K).
  - This leaves out about half of the counter cells: 8.2 cells per triple are sent instead of 11.6.
  - It is exact because a block holds whole entities, and no subject appears in two entities.
  - `--stats-ones keep` writes every counter. It is a dataset option, so repeat it on every command, as `prepare` prints.
- **Throughput:** with statistics and both indexes on, each triple sends about 870 bytes to the tablet servers (`dry-run` shows the breakdown per table), so a 1 Gbit/s link carries about 135k triples/s. `--stats off` or fewer indexes send less. `--durability flush` trades crash safety for speed, and `--max-rate` caps a process.
- **Too many writers:** more writers than the cluster can absorb can starve ZooKeeper, and a tablet server that loses its ZooKeeper lock exits. Raise `--threads` gradually and watch the cluster.
- **Disk:** after compaction, a test load took about 160 bytes per triple with statistics and both indexes: about 50 for statistics, 42 for text, 21 for each of spo, po and osp, and 1 for geo. That load wrote every counter. Leaving out the subject counters of 1 should save about two thirds of the statistics bytes, an estimate not yet measured. Multiply by the replication factor of the storage.
- **Exit codes:** 0 done, 1 failed or interrupted (resume it), 2 bad arguments or a store that doesn't match.

## Embedding the Sail

```java
try (AccumuloClient client = Accumulo.newClient().from("accumulo-client.properties").build()) {
    StoreRdfConfiguration conf = new StoreRdfConfiguration();
    conf.setTablePrefix("tripatha_");
    conf.setInfer(true);                       // optional
    conf.setTextIndex(true);                   // optional: text:matches index
    conf.setGeoIndex(true);                    // optional: GeoSPARQL index
    SailRepository repo = RyaSailFactory.createRepository(AccumuloStore.wrap(client), conf);
    repo.init();
    try (RepositoryConnection conn = repo.getConnection()) {
        conn.prepareUpdate("INSERT DATA { <urn:a> <urn:p> <urn:b> }").execute();
    }
    repo.shutDown();
}
```

On RocksDB, open the directory: `RocksStore.open(Path.of("/var/lib/tripatha"))` (close the store when done). On HBase, wrap a connection the same way: `HBaseStore.wrap(connection, user)` (the caller closes the connection), or open a store of either backend from its settings with `Stores.open("hbase", settings)` (see `HBaseStoreProvider` for the settings).

## Notes

- Writes are not transactional. Each update is applied immediately, and rollback does not undo it.
- `xsd:dateTime` values are stored as UTC milliseconds, so the original time-zone offset is not kept.
- Literals with the datatype `urn:tripatha/2012/05#tripleTerm` are refused: Tripatha uses that IRI inside its row keys for triple terms.
- Literals containing the control characters U+0000 or U+0001 are rejected, because those bytes separate the parts of a row key. Graph IRIs and statement qualifiers containing U+0000 are rejected too: on HBase that byte separates the column names.
- `size()` and the list of named graphs scan the data. They cost time in proportion to the store or graph size.
- Statistics are approximate, since re-inserting an existing statement counts it twice. They only affect join order.
