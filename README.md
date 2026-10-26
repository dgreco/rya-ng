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

# rya-ng

[![CI](https://github.com/dgreco/rya-ng/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/dgreco/rya-ng/actions/workflows/ci.yml)
[![coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fdgreco.github.io%2Frya-ng%2Fbadge.json)](https://dgreco.github.io/rya-ng/coverage/)
[![tests](https://img.shields.io/endpoint?url=https%3A%2F%2Fdgreco.github.io%2Frya-ng%2Ftests.json)](#build-and-test)
[![license](https://img.shields.io/github/license/dgreco/rya-ng?color=blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25%2B-orange)](#requirements)

rya-ng is an RDF triple store on [Apache Accumulo](https://accumulo.apache.org/). It has two parts:
- an [RDF4J](https://rdf4j.org/) Sail you can embed;
- a standalone [SPARQL 1.1 Protocol](https://www.w3.org/TR/sparql11-protocol/) endpoint for queries and updates.

It supports:
- named graphs;
- Accumulo cell-level visibility labels and scan authorizations;
- optional query-time OWL/RDFS inference;
- join ordering driven by statistics that are kept up to date on every write;
- batched join lookups: the matches for many left-hand rows are fetched in one Accumulo batch scan;
- full-text search and GeoSPARQL filters, sped up by optional text and geo indexes that honour visibility labels.

rya-ng is derived from [Apache Rya](https://github.com/apache/rya). It keeps Rya's storage model and query-time inference, rebuilt for Accumulo 2.1, RDF4J 6 and Java 25, with everything else removed. It is an independent project, not part of the Apache Software Foundation. See `NOTICE`.

## Requirements

- **Java 25.** RDF4J 6 requires it.
- **Maven 3.9 or newer.**
- **Accumulo 2.1.x** to run against. Tests and the dev server start their own in-process MiniAccumuloCluster.

## Build and test

```bash
mvn clean verify                 # build, unit tests (*Test) and MiniAccumuloCluster tests (*IT)
mvn clean install -DskipTests    # build only
mvn test                         # unit tests only (fast, no Accumulo)

# one test class: install the modules once, then run a single module
mvn install -DskipTests
mvn -pl rya.api test -Dtest=TypeEncodingDelimiterTest
mvn -pl rya.accumulo verify -Dit.test=AccumuloRyaDAOIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false

# coverage of the unit and integration tests together, in rya.coverage/target/site/jacoco-aggregate
mvn clean verify -Pcoverage
```

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn clean verify -Pcoverage` on every push and pull request:
- Each run's summary lists the tests and the coverage per module, and the HTML coverage report is attached to the run.
- For pushes to `main`, the report is published at <https://dgreco.github.io/rya-ng/coverage/>. The coverage and tests badges above show its line coverage and test count.

The endpoint is packaged as `rya.sparql/target/rya.sparql-<version>.jar`.

## Modules

| Module | Purpose |
|---|---|
| `rya.api` | Storage-independent model (`RyaStatement`, `RyaIRI`, `RyaType`), DAO interfaces, and row and value encoding |
| `rya.accumulo` | `AccumuloRyaDAO`: stores each statement in the SPO, PO and OSP tables, and maintains statistics and the named-graph list |
| `rya.sail` | RDF4J Sail (`RdfCloudTripleStore`), query planning, inference, and `RyaAccumuloSailFactory` |
| `rya.sparql` | Spring Boot SPARQL endpoint |
| `rya.loader` | Synthetic data generator and parallel loader, for stress tests |
| `rya.test.accumulo` | Test-only harness: one shared MiniAccumuloCluster per JVM |

## Running the SPARQL endpoint

Build the endpoint, let the wizard write a launch script, then start it:

```bash
mvn -pl rya.sparql -am package -DskipTests   # rya.sparql/target/rya.sparql-<version>.jar
rya.sparql/configure.sh                        # asks for the settings, writes ./rya-sparql.sh
./rya-sparql.sh                                # asks for the Accumulo password, then starts
```

**What the wizard asks for:**
- **Java and the jar:** the paths, and the heap size.
- **Connection:** either the ZooKeeper servers, instance name and user, or an `accumulo-client.properties` file.
- **Password source:** where the launch script gets the password when it starts: the terminal, the `RYA_ACCUMULO_PASSWORD` variable, or a file only you can read. The password is never stored in the script.
- **Store:** table prefix, text and geo indexes, inference and statistics.
- **Security:** the visibility labels requests may read, and the label written on inserted data.
- **Server:** port, listen address and query timeout.

**Options and overrides:**
- `--output FILE` writes the script elsewhere.
- `--defaults` accepts every default without asking.
- Arguments given to the launch script are passed to the endpoint, for example `./rya-sparql.sh --server.port=9090`.

Without the wizard, pass the settings as arguments. Pass the password in the environment, not as an argument: other users can see arguments with `ps`.

```bash
read -rs -p 'Accumulo password: ' RYA_ACCUMULO_PASSWORD; export RYA_ACCUMULO_PASSWORD
java -jar rya.sparql/target/rya.sparql-1.0.0-SNAPSHOT.jar \
  --rya.accumulo.instance=accumulo --rya.accumulo.zookeepers=zk1:2181 --rya.accumulo.user=rya
```

Instead of the connection arguments, `--rya.accumulo.client-properties` can point at an `accumulo-client.properties` file, such as the one `rya.loader/configure.sh` writes. If the file has no password, it comes from `RYA_ACCUMULO_PASSWORD`. Settings can also come from environment variables, for example `RYA_ACCUMULO_ZOOKEEPERS`. Defaults are in `rya.sparql/src/main/resources/application.yaml`.

| Setting | Default | Meaning |
|---|---|---|
| `rya.table-prefix` | `rya_` | Prefix of the Rya tables, which are created if missing |
| `rya.inference` | `false` | Query-time OWL/RDFS inference |
| `rya.inference-refresh` | `5m` | How often the ontology is reloaded. Ontology changes take effect after the next reload. |
| `rya.stats` | `true` | Keep cardinality statistics on write and use them for join ordering |
| `rya.join-batch-size` | `1000` | Left-hand join rows whose lookups are fetched together in one batch scan; `1` turns batching off |
| `rya.text-index` | `false` | Keep the full-text index (see [Search](#search)) |
| `rya.text-index-predicates` | `[]` | Predicates whose literals are text-indexed; empty means all |
| `rya.geo-index` | `false` | Keep the geospatial index |
| `rya.rebuild-indexes` | `false` | At startup, build indexes that don't cover the stored data yet |
| `rya.sparql.authorizations` | `[]` | The most a request may read with. The Accumulo user must hold all of them. |
| `rya.sparql.default-visibility` | `""` | Visibility label written on inserted statements |
| `rya.sparql.allow-request-visibility` | `false` | Let an update set its own label with `visibility=` |
| `rya.sparql.query-timeout` | `120s` | Maximum query or update time |

To try the endpoint without an Accumulo cluster:

```bash
mvn -pl rya.sparql -am install -DskipTests
mvn -pl rya.sparql spring-boot:test-run -Dspring-boot.run.main-class=io.github.dgreco.rya.sparql.DevServer
```

### Query page

Open the server's root URL (for example `http://localhost:8080/`) for a query page built on [YASGUI](https://github.com/zazuko/Yasgui).

**Writing queries:**
- Syntax highlighting, autocompletion and several query tabs, which the browser remembers.
- An **Examples** menu with queries to load sample data (Rome landmarks), browse it, and run text and geo searches.
- Every new tab starts with the `rdf:`, `rdfs:`, `xsd:`, `text:`, `geo:`, `geof:` and `uom:` prefixes.

**Result tabs:**
- **Table** and **Response**, as in YASGUI.
- **Map** draws every `geo:wktLiteral` in the results on an OpenStreetMap background. The background tiles need internet access; the rest of the page doesn't.
- **Chart** shows bar, line and pie charts of numeric results.

**Authorizations:** the field in the header is sent as `auths=`, so a query reads with fewer labels than the server allows.

The page's JavaScript and CSS are packaged into the jar at build time. Their licenses are listed at `/vendor/THIRD-PARTY-NOTICES.txt`.

### Using it

The endpoint is `/sparql` and follows the SPARQL 1.1 Protocol.

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

An unsupported `Accept` gets `406`. A malformed query or update gets `400`.

Rya extensions:
- `auths=A,B` narrows the read authorizations for one request; asking for more than is configured gets `403`.
- `visibility=A&B` labels the statements an update inserts, if `allow-request-visibility` is on.

The endpoint has no HTTP authentication. Run it behind a proxy that authenticates users, or add Spring Security.

## Search

### Text

`text:matches(?literal, "query")` is true when a string literal (`xsd:string` or language-tagged) satisfies a text query:

```sparql
PREFIX text: <urn:rya-ng:text#>
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

`rya.text-index` and `rya.geo-index` add an index table each (`<prefix>text`, `<prefix>geo`).

**What the index can speed up:**
- **Text:** queries with at least one word that every match must contain. `NOT x`, or prefixes under 3 characters, fall back to scanning.
- **Geo:** relations that imply intersection (`sfWithin`, `sfIntersects`, `sfContains`, `sfOverlaps`, `sfTouches`, `sfCrosses`, `sfEquals` and their `eh*`/`rcc8*` equivalents, or `geof:relate` with such a pattern). Also `geof:distance(?g, point, unit) < d` (or `<=`, or reversed).

**How results stay correct:**
- The index only narrows the candidates down.
- Every hit is read back from the statement tables with the reader's authorizations, and the filter always makes the exact check.
- So results are exactly the same as without the index, and nothing under a label the reader lacks is returned.

**When the index is not used:**
- The query sets Rya's `query.limit` option. SPARQL `LIMIT` is fine.
- An embedded query sets the binding `query.useindex` to `false`.
- The plan starts elsewhere and looks up each subject directly. That lookup is cheaper anyway.

**Lifecycle:**
- **Turning an index on for a store that already has data:** queries don't use the index until it is built. Build it by starting once with `rya.rebuild-indexes=true`. The build runs during startup and reads every statement, so the Accumulo user must hold every visibility label in the store.
- **Turning an index off:** delete its table, or the store refuses to start. An index that stopped receiving writes would silently miss data.
- **Changing the indexed predicates:** this also needs a rebuild.

## Loading synthetic data

`rya.loader` generates a synthetic dataset and writes it into a store in parallel, through the normal write path, for stress tests up to billions of triples.

**The data:**
- about 7.5 triples per entity, over 11 classes: people, organizations, companies, cities, landmarks, events, products, reviews, articles, sensors and observations;
- labels and descriptions in 11 languages, numbers, dates, and power-law links between entities;
- points and polygons, named graphs, and optional visibility labels.

Each entity depends only on the seed and its number, so any process can generate any part of the dataset, and writing a part again produces the same keys.

```bash
rya.loader/configure.sh   # once: asks for the connection, writes ~/.rya-ng/accumulo-client.properties
R=rya.loader/run.sh       # builds the jar on first use; --build rebuilds it
C="--client-properties $HOME/.rya-ng/accumulo-client.properties"

$R check $C                                                # connection, tablet servers, the user's rights
$R dry-run --triples 1B --text-index --geo-index           # no cluster: speed, bytes per triple, network ceiling
$R prepare $C --table-prefix big_ --triples 1B --text-index --geo-index
$R load    $C <the dataset options prepare printed> --threads 8
$R load    $C <same options> --resume                       # after Ctrl-C or a failure
$R verify  $C <same options> --sample 1000
$R drop    $C --table-prefix big_ --confirm big_
```

- **Connection:** `rya.loader/configure.sh` writes an `accumulo-client.properties` file, readable only by you, and can test it right away.
  - The password can be stored in the file, or left out and read from `RYA_ACCUMULO_PASSWORD` when the loader starts.
  - `--output FILE` writes elsewhere, and `--defaults` takes the `RYA_ACCUMULO_*` variables without asking.
  - Without a file, set `RYA_ACCUMULO_ZOOKEEPERS`, `RYA_ACCUMULO_INSTANCE`, `RYA_ACCUMULO_USER` and `RYA_ACCUMULO_PASSWORD`. `--zookeepers`, `--instance` and `--user` also work, but the password is never accepted as an argument.
- **`prepare` first, once per dataset:**
  - creates the tables with the indexes ready and splits each table into 4 tablets per tablet server, at split points sampled from the data;
  - writes the ontology, 200 countries and a description of the dataset;
  - prints the `load` commands and the endpoint flags that match.
  `load` refuses a store prepared for another dataset or with other index settings.
- **Parallelism:** `--threads` writers per process. `--part K --parts N` divides the entities between processes, on one or more machines.
- **Resuming:** progress is saved after each flush (every `--commit-interval`), and `--resume` continues from there. After a crash with `--durability` below `sync`, run `verify`.
- **Throughput:** with statistics and both indexes on, each triple sends about 1 KB to the tablet servers (`dry-run` shows the breakdown per table), so a 1 Gbit/s link carries about 120k triples/s. `--stats off` or fewer indexes send less. `--durability flush` trades crash safety for speed, and `--max-rate` caps a process.
- **Too many writers:** more writers than the cluster can absorb can starve ZooKeeper, and a tablet server that loses its ZooKeeper lock exits. Raise `--threads` gradually and watch the cluster.
- **Disk:** after compaction, a test load took about 160 bytes per triple with statistics and both indexes: about 50 for statistics, 42 for text, 21 for each of spo, po and osp, and 1 for geo. Multiply by the replication factor of the storage.
- **Exit codes:** 0 done, 1 failed or interrupted (resume it), 2 bad arguments or a store that doesn't match.

## Embedding the Sail

```java
try (AccumuloClient client = Accumulo.newClient().from("accumulo-client.properties").build()) {
    AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
    conf.setTablePrefix("rya_");
    conf.setInfer(true);                       // optional
    conf.setTextIndex(true);                   // optional: text:matches index
    conf.setGeoIndex(true);                    // optional: GeoSPARQL index
    SailRepository repo = RyaAccumuloSailFactory.createRepository(client, conf);
    repo.init();
    try (RepositoryConnection conn = repo.getConnection()) {
        conn.prepareUpdate("INSERT DATA { <urn:a> <urn:p> <urn:b> }").execute();
    }
    repo.shutDown();
}
```

## Notes

- Writes are not transactional. Each update is applied immediately, and rollback does not undo it.
- `xsd:dateTime` values are stored as UTC milliseconds, so the original time-zone offset is not kept.
- Literals containing the control characters U+0000 or U+0001 are rejected, because those bytes separate the parts of a row key.
- `size()` and the list of named graphs scan the data. They cost time in proportion to the store or graph size.
- Statistics are approximate, since re-inserting an existing statement counts it twice. They only affect join order.
