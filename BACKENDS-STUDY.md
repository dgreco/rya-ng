<!--
Copyright 2026 David Greco

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# HBase as a second storage backend: study and plan

Status: written 2026-10-31 against `main` at `860fa66`; implemented on the `hbase-backend` branch on 2026-11-01. The study below is kept as written, with the phase 0 results added to section 8. The next section says what was built and where it differs from the plan.

## 0. Implementation status

**Built, phases 0 to 4:**
- **The SPI and the Accumulo provider** (phase 1): `rya.store`, `rya.accumulo`, `rya.test`. The 409 tests of `main` pass unchanged.
- **The HBase provider** (phase 2): `rya.hbase`, with the cell mapping of 3.2, the label rules of 4.2 and region-grouped batched reads.
- **Counters** (phase 3): `rya.hbase.coprocessor`, in both counter modes.
- **The endpoint and the loader** (phase 4): `rya.backend` and `rya.hbase.*`, `--backend` and `--hbase-*`, the loader's `labels` command, both wizards (the loader's writes a file for `--hbase-properties`), and `DevServer`.
- **Tests:** the store, Sail, loader and endpoint ITs run on either backend (`-Drya.test.backend=hbase`). New tests: `QualifierOrderTest`, `RangeParityIT`, `LabelParityIT`, `CounterIT`, `HBaseLabelsIT`, `StoreProviderIT`, and a parity test of the neutral visibility parser against Accumulo's.

**Different from the plan:**
- **Tracing.** The Accumulo trace proxies were kept, so traces on Accumulo are unchanged. HBase reports through `TracedScans` and its writer, with the same events. A `Store` decorator would have changed the events on Accumulo.
- **`AccumuloRdfConfiguration`** stays, as a subclass of `StoreRdfConfiguration` with the Accumulo-only settings.
- **Counters in increment mode** are set back to zero on a drop, not deleted. An increment is stamped `max(now, previous + 1)`, so a busy counter's timestamp runs ahead of the clock, and a delete marker there would hide the next increments until the clock caught up. `CounterIT` found this.
- **The coprocessor's timestamps.** Each delta's timestamp is taken atomically, with a monotonic per-region clock read inside the update. Puts take shared row locks, so two batches can stamp the same cell at once, which section 3.8 didn't foresee. `CounterIT` found this too.
- **Label checks.**
  - **Startup probe:** a store refuses to open when, asking for no labels, it sees a label canary. That means its account is a superuser, or the cluster doesn't enforce labels. Phase 0 showed both cases.
  - **Endpoint canaries:** on HBase the endpoint refuses to start when a label of `rya.sparql.authorizations` has no canary visible to it.
  - **Label administration** opens the store with `hbase.check-labels=false`, because it needs a superuser.
- **Read-only HBase accounts.** There is no test that one can open a store, because the test cluster runs without `AccessController`. Accumulo has one.
- **Accumulo rejects some empty reads.** It rejects a cell range outside the fetched columns instead of returning nothing, so the Accumulo provider drops such ranges before reading. `RangeParityIT` found this; rya-ng itself never asks for one.
- **Finding an HBase 3 cluster.** The HBase 3 client looks for the masters (`hbase.client.bootstrap.servers`, by default `localhost:16000`) rather than ZooKeeper. Given only a ZooKeeper quorum and no site file, the provider switches to ZooKeeper discovery itself.
- **Long lookups on HBase.** A lookup of a literal too long to have been stored (a row over 32,767 bytes) reads nothing, as on Accumulo, instead of failing.
- **Test cluster fixes:**
  - The HBase test cluster binds to the loopback address. With two interfaces on one network, the host name resolved to the wrong address and the cluster vanished mid-run.
  - Its JSP engine (`tomcat-jasper`, javax) is excluded, because it broke Spring Boot's Tomcat in the endpoint tests.
- **Purge in increment mode** leaves counters at zero rather than deleting them, for the same reason as drops.
- **The wizards** ask for site files (`core-site.xml` for Kerberos) rather than a principal, a keytab and TLS stores.

**Checked outside the tests:** the endpoint's executable jar ran against an HBase cluster in another JVM, with Hadoop's client API only (no `hadoop-common`). It was given just a ZooKeeper quorum and the normal account, and its startup check of labels A,B passed. Over HTTP:
- labelled and unlabelled inserts worked;
- a read with A,B saw both, and one with B didn't see the A-labelled value;
- an unconfigured label got 403;
- the text index answered.

The loader's executable jar ran the same way, from its own process against an HBase mini cluster, with the file its wizard wrote (a ZooKeeper quorum and the normal account):
- `labels` registered A,B as the process user (a superuser, with an empty `--hbase-user=`); every other command refused that account;
- `prepare`, `load` and `verify` (both labels, and A alone) found no difference, with coprocessor and with increment counters;
- `drop` removed the tables.

**Still to do:**
- **Benchmarks B0, B1, B2** (section 7) at the planned scale, on a real cluster. One machine shared with other load can't measure within 3 %; a small A/B on the mini clusters is all that was run.
- **Kerberos and TLS** against a real HBase cluster. The client settings pass through (`hbase.site`, `hbase.conf.*`), but they are untested.
- **The loader's `dry-run`** still sizes cells as Accumulo sends them.
- **Data copy between backends** (section 5.4) is not built.
- **Gaps in the superuser check.** The startup probe needs label canaries, which exist only after a label was registered through rya-ng (the loader's `labels` command). Until then a store opens with a warning. A superuser with labels registered outside rya-ng would then read labelled cells while asking for none.
- **The counter mode isn't recorded in `ns`,** as 3.8 planned. Writers read it from the table descriptor, and an account that may not read descriptors uses its configured mode, so every writer of a store must be configured alike.
- **Unheld labels in embedded use.** Through the embedded Sail or DAO, labels the account doesn't hold make HBase return fewer rows instead of failing. Only the endpoint checks its labels at startup.

## 1. Summary

**Yes, the design works: rya-ng can run on HBase with the same functionality, with one size limit to decide on.** Every Accumulo feature rya-ng uses has an HBase counterpart, natively or with a small amount of rya-ng code:
- **Mechanical:** most features map one to one (section 3.1).
- **Labels work differently in HBase, so they need four rules:**
  - The label goes in the qualifier as well as in the tag. In HBase a label isn't part of the cell's key: two copies of a statement under different labels would collapse into one, and a client can't read a cell's label back.
  - Reads never send an empty authorization set. HBase reads an empty set as "every label the user holds".
  - Accounts that read with labels (the endpoint, the loader's `verify`, the tests) must not be HBase superusers, who see every cell.
  - Labels must be registered in HBase before they are used.
- **Batched lookups** need a client-side replacement for Accumulo's `BatchScanner`: ranges grouped by region and scanned in parallel.
- **Statistics counters** need server-side code to match Accumulo's write-only combiner. That code is a small coprocessor jar installed on the region servers. HBase's native increments work without it, but each one locks its row, reads the old value and syncs the write-ahead log on its own. That is far slower than the batched writes Accumulo does.
- **One gap:** HBase caps a row key at 32,767 bytes, against Accumulo's 1 MB per key. rya-ng stores whole literals in its rows, so literals over about 32 KB fit on Accumulo and not on HBase.

**The blocking unknown is the runtime, not the design.**
- No HBase release officially supports JDK 25, which rya-ng requires (HBASE-29728 is open).
- HBase builds against Hadoop 3.4.3, and nobody has tested it with the Hadoop 3.5.0 client this build forces.
- The client only has to run on JDK 25, since the HBase servers can stay on JDK 17 or 21. The in-process mini cluster used by the tests would run HBase and HDFS servers on JDK 25, which is riskier.
- Phase 0 (section 8) is a spike that settles this before any other work starts, with a fallback for the tests: an HBase cluster in a container, outside the test JVM.

**Pluggable backends** come from cutting at the cell level:
- **One shared module (`rya.store`)** holds a small storage SPI and all the logic that is the same everywhere: the DAO, the query engine, statistics, indexes and tracing, moved out of `rya.accumulo`.
- **Thin providers**, one per backend, are found with `ServiceLoader` and chosen by one `rya.backend` setting.
- **Capability flags** make a backend that lacks a feature (for example Bigtable, without labels) refuse the settings it can't honour, instead of degrading silently.

**"No speed penalty" is two claims:**
- Putting Accumulo behind the SPI must cost nothing measurable. That is a merge gate, proven by benchmarks before and after.
- HBase matching Accumulo is a design target. The mapping adds no read-before-write and no client-side filtering, and no extra round trips per operation, with one exception: a batched lookup costs one scan per region touched instead of one request per server, all in flight at once. That exception and the other named risks are measured on the same hardware (section 7.1). Parity can't honestly be promised before measuring.

**Decisions for you:**

| # | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Literals whose rows would exceed HBase's row limit | (a) reject them on HBase with a clear error, and add a check that finds them in an Accumulo store; (b) a new out-of-row encoding for long values | (a). (b) changes the stored encoding and ARCH.md invariants 1–2 for both backends |
| D2 | Counter mode on HBase | (a) the rya-ng coprocessor (parity, but a jar on the region servers); (b) native `Increment` (nothing to install, slower writes) | (a) by default, (b) where coprocessors aren't allowed. The mode is fixed per store |
| D3 | HBase labels must be registered before use | (a) operators register them, with a rya-ng admin command that also grants rya-ng's reserved label and writes the label canaries (4.2), and writes with unknown labels fail with a clear error; (b) privileged writers (loader `prepare`, rebuild) register what they write | (a) for the endpoint, (b) for the loader |
| D4 | HBase release line | 3.0.x (GA since 2026-08; JDK 17+, Hadoop 3 only, async client underneath) or 2.6.x (its `-hadoop3` artifacts; the plain ones pull in Hadoop 2.10) | 3.0.0, with the plain `hbase-client` in main code and `hbase-testing-util` in tests, both on Hadoop 3.5.0: the pair phase 0 ran on JDK 25. The shaded clients would duplicate `org.apache.hadoop.hbase.client` classes next to the test jars, and the plain client avoids the shaded-client DNS bug (HBASE-30211). Decided by phase 0 (section 8) |
| D5 | Bigtable or other HBase-API stores later | design the capability flags now, or not | design them now (cheap), implement later. Bigtable has no labels and no coprocessors, and caps rows at 4 KB, so it would be a reduced mode |
| D6 | Packaging | one endpoint and loader jar with both providers, or one per backend | one jar; check its size in phase 0 |

**Side findings, unrelated to HBase:**
- **Counter drift after a full drop on Accumulo.** `EvalStatsMutations.combinerSetting()` doesn't set `Combiner.setReduceOnFullCompactionOnly`. Accumulo's javadoc warns that combining during a partial compaction can bring back data that a delete in another file should have removed. Only the drop-everything and purge paths delete `eval` cells, and counters are approximate by design, so the impact is limited to join-order drift after such a drop. Turning the option on would cost more cells per file. It's worth knowing, not necessarily worth changing.
- **`VisibilitySimplifier`** is used only by its own test.

**Where to read what:**

| Section | Content |
|---|---|
| 2 | what rya-ng uses from Accumulo |
| 3 | the mapping, feature by feature |
| 4 | security |
| 5 | the pluggable design |
| 6 | tests |
| 7 | performance and benchmarks |
| 8 | the phased plan |
| 9 | sources |

---

## 2. What rya-ng asks of Accumulo today

The Accumulo API is used in 22 files of `rya.accumulo`, 9 of `rya.loader`, 1 of `rya.sail` (`RyaAccumuloSailFactory`) and 2 of `rya.sparql`. `rya.api` has none: its `TripleRow` (row, family, qualifier, visibility, timestamp, value) is already a backend-neutral cell. `rya.sail` reaches Accumulo-specific *rya* types in 10 files (`AccumuloRyaDAO`, `AccumuloRdfConfiguration`, `AccumuloTrace`, the index classes), but no Accumulo API.

Every Accumulo feature rya-ng depends on, and where:

| # | Accumulo feature | Used for | Where |
|---|---|---|---|
| A1 | Sorted map, 5-part key (row, cf, cq, cv, ts) | every table | everywhere |
| A2 | Column family as a free-form, per-cell value | graph IRI in `spo`/`po`/`osp`/`text`/`geo`; counter kind in `eval`; record kind in `ns` | `RyaTableMutationsFactory`, `IndexKeys`, `EvalStatsMutations` |
| A3 | Visibility as part of the key | the same statement under two labels is two cells; every derived cell carries the label | all writers |
| A4 | Reading a cell's visibility back | `RyaStatement.columnVisibility`; exact deletes; `BatchLookup` deduplication; index rebuild/verify | `RyaStatementKeyValueIterator`, `deleteMatches`, `getContextIDs` |
| A5 | Scan authorizations; a scan with unheld auths is rejected; empty auths = public data only | per-request security | `AccumuloRdfConfiguration.getAuthorizations()` |
| A6 | `Scanner`: one ordered range, `setBatchSize` | single lookups, probes, skip scan | query engine, `GraphSizes`, `IndexSearcher`, DAO |
| A7 | `BatchScanner`: many ranges, parallel, unordered | batched joins, index searches, graph sizes, full scans | `batchQuery`, `IndexSearcher`, `GraphSizes`, `size()`, index rebuild |
| A8 | `fetchColumnFamily` / `fetchColumn` (server-side, seek-based) | graph restriction, exact counter cell | query engine, `IndexSearcher.restrictGraphs`, `getCardinality` |
| A9 | `RegExFilter` scan iterator | object-type check on rows (`ri`), qualifier regex (`riq`) | `AccumuloRyaQueryEngine.fillScanner` |
| A10 | `TimestampFilter` scan iterator | `query.ttl` | `fillScanner` |
| A11 | Arbitrary scan iterators from the conf | `setAdditionalIterators` (only one test uses it) | `fillScanner` |
| A12 | `SummingCombiner` at scan, minc, majc | `eval` counters: blind `+n` writes, summed on the server | `EvalStatsMutations`, `ensureStatsCombiner` |
| A13 | `MultiTableBatchWriter`: one buffer, one parallel flush for all tables | every write; `flush()` = commit | DAO, `IndexManager` |
| A14 | `putDelete(cf, cq, cv, ts)`: delete an exact cell, older timestamps included | `deleteMatches`, stale graph markers, index deletes | DAO, `IndexKeys` |
| A15 | `BatchDeleter` (scan + delete of whatever is visible) | `dropGraph`, `purge` | DAO |
| A16 | Versioning iterator (newest timestamp wins) | re-inserting a statement stores it once | implicit |
| A17 | Table admin: exists, create, delete, `addSplits`, `listSplits` | `init()`, loader `prepare`/`drop` | DAO, `SplitPlanner` |
| A18 | Table settings readable only with `ALTER_TABLE`; read-only users must open the store | `ensureStatsCombiner` skips the check | DAO |
| A19 | Security ops: `whoami`, user auths, grant auths, system permissions | index rebuild/verify (full user auths), loader `check`/`prepare`, tests | `IndexManager`, `IndexVerifier`, loader, `AccumuloITBase` |
| A20 | `ColumnVisibility` parser | validating `visibility=` on the endpoint; `VisibilitySimplifier` (unused in main code) | `SparqlController` |
| A21 | `Durability` per writer | loader `--durability` | `LoaderOptions` |
| A22 | Client properties file, password from `RYA_ACCUMULO_PASSWORD` | endpoint, loader, `configure.sh` | `AccumuloClients` |
| A23 | Instance info: tablet server count | loader `check`, split planning | loader |
| A24 | `MiniAccumuloCluster` | all 30 `*IT` classes, `DevServer` | `rya.test.accumulo` |
| A25 | Dynamic proxies over the client API | tracing | `trace/TracingProxies` |

Two properties of the current code constrain any mapping, beyond the features themselves:
- **Row bytes are load-bearing.** Ranges, split points, `BatchLookup`'s exact matching and the loader's `SplitPlanner` all work on the row bytes built in `rya.api`. A port that keeps rows byte-identical keeps all of that code unchanged.
- **Order inside a row is load-bearing too.** `IndexSearcher.withoutAdjacentRepeats` relies on cells that differ only in their label being adjacent, which holds because Accumulo sorts by (cf, cq, cv). Whatever replaces cf/cq/cv must sort the same way.

---

## 3. The mapping, feature by feature

### 3.1 Summary table

| # | Accumulo | HBase | Kind |
|---|---|---|---|
| A1 | 5-part sorted key | row + one family + an encoded qualifier + timestamp (3.2) | encoding |
| A2 | free-form column family | inside the qualifier: families must be declared in the table schema | encoding |
| A3 | label in the key | inside the qualifier too, so copies under two labels stay distinct cells (3.2) | encoding |
| A4 | read the label back | decode it from the qualifier; HBase strips tags from results | encoding |
| A5 | scan authorizations | `Scan/Get.setAuthorizations`, never empty, from a non-superuser account, plus a start-up check that the account holds its configured labels (4.2) | native, with rules |
| A6 | `Scanner` | `Scan` with explicit start/stop inclusiveness; `Get` for exact rows (3.4) | native |
| A7 | `BatchScanner` | ranges grouped by region, one `MultiRowRangeFilter` scan per region, all in flight at once (3.5) | client-side |
| A8 | `fetchColumnFamily` / `fetchColumn` | `ColumnPrefixFilter` / `MultipleColumnPrefixFilter` on the encoded qualifier (seek-based) | native |
| A9 | `RegExFilter` | `RowFilter` / `QualifierFilter` with `RegexStringComparator`, anchored, UTF-8, no flags (3.6) | native |
| A10 | `TimestampFilter` | `Scan.setTimeRange` (also skips whole files by their time range) | native, better |
| A11 | arbitrary scan iterators | none: Accumulo-only capability | gap, unused in main code |
| A12 | `SummingCombiner` | counter deltas written as plain puts and summed by a rya-ng `RegionObserver`, or native `Increment` (3.8) | server jar, or much slower writes |
| A13 | `MultiTableBatchWriter` | one buffered mutator per table, all flushed concurrently (3.7) | client-side |
| A14 | exact `putDelete` | `Delete.addColumns(family, qualifier, ts)` carrying the cell's own label, which HBase requires (3.9) | native |
| A15 | `BatchDeleter` | parallel scan plus exact deletes, which is what `BatchDeleter` does inside (3.9) | client-side, same work |
| A16 | versioning (newest wins) | `VERSIONS => 1` | native |
| A17 | table admin, splits | `Admin`, `createTable(descriptor, splitKeys)`, `RegionLocator` (3.10) | native |
| A18 | settings readable only by admins | same need: init degrades for users who may not read the descriptor (3.10) | same logic |
| A19 | security operations | `VisibilityClient` (`getAuths`, `setAuths`, `addLabels`), `AccessControlClient`, `User` (4) | native |
| A20 | `ColumnVisibility` parser | a backend-neutral parser of Accumulo's syntax (4.4) | library |
| A21 | `Durability` | `Mutation.setDurability`: `SKIP_WAL`, `ASYNC_WAL`, `SYNC_WAL`, `FSYNC_WAL` | native |
| A22 | client properties + password | `hbase-site.xml`, SIMPLE or Kerberos, optionally TLS. HBase has no password authentication (3.11) | different auth model |
| A23 | tablet server count | `Admin.getRegionServers()` | native |
| A24 | `MiniAccumuloCluster` | the HBase mini cluster from the testing utilities (6) | test |
| A25 | tracing proxies | a decorator over the SPI, for both backends (5.5) | redesign, simpler |

The two hard items are the counters (A12) and the batch scanner (A7). Labels (A3 to A5) need care but map cleanly. The rest is mechanical.

### 3.2 The cell mapping

HBase differs from Accumulo in three ways that matter here:
- **Families are declared** in the table schema, and HBase advises only a few per table. rya-ng uses the column family for the graph IRI, an open set.
- **A label is a tag, not part of the key.** Two cells that differ only by label have the same coordinates (row, family, qualifier, timestamp):
  - With equal timestamps they are the same cell. HBase's own code says so ("2 cells with same ts and diff vis tags only one of them is returned", in `VisibilityScanDeleteTracker`), and a compaction keeps only one. The loader writes every cell of a dataset with one fixed timestamp, so this would hit every statement stored under two labels.
  - With different timestamps they are versions of one cell. A newer version the reader may not see still counts against the version limit, so it hides an older version the reader may see.
- **A client can't read a label back.**
  - The default RPC codec strips tags.
  - The tag-carrying codec returns label ordinals, and only users holding HBase's `system` label may map those to names.
  - HBase's guide says so too: "there is currently no way to determine which labels have been applied to a cell".
- **A delete must carry the label it deletes.** HBase only removes cells whose label expression matches the delete's, after normalisation. A delete without a label removes only unlabelled cells.

So every table uses one family, `c`, and an encoded qualifier:

```
HBase row        = the rya-ng row, byte for byte
HBase family     = c
HBase qualifier  = cf 00 cq 00 cv
HBase timestamp  = the cell's timestamp
HBase value      = the cell's value
HBase tag        = CellVisibility(cv), when cv is not empty
```

For `ex:alice ex:age 42` in graph `ex:g1` under label `A&B`, the `spo` cell is:

```
row        http://ex.org/alice 00 http://ex.org/age 00 800000012a 01 05     (unchanged)
family     c
qualifier  http://ex.org/g1 00 00 A&B
tag        visibility A&B
```

**Why it is correct:**
- **Rows are identical.** So ranges, split points, `BatchLookup`'s exact matching, the loader's `SplitPlanner` and every row-level invariant keep working unchanged.
- **The label is in the qualifier:**
  - Copies of a statement under two labels stay two cells, as on Accumulo.
  - All the versions of a cell have the same label, so a hidden version can never hide a visible one.
  - The reader decodes the label back, and deletes know which label to carry.
- **The tag enforces the label on the server.** A reader without the labels never receives the cell, and so never sees the qualifier that names its label. That matches Accumulo, which returns the label with every cell the reader may see.

**Why the order is the same.** Accumulo orders the cells of a row by (cf, cq, cv), then by timestamp descending. HBase orders them by qualifier, then by timestamp descending. With `00` as a separator smaller than every byte that may appear in cf and cq, comparing the encoded qualifiers byte by byte gives exactly the (cf, cq, cv) order:
- If one cf is a prefix of the other, the shorter one hits `00` where the longer one has a larger byte, so it sorts first. That is also Accumulo's rule.
- The same holds for cq.
- cv is last and is compared as is.

So `withoutAdjacentRepeats` and any other order-dependent code see the same sequence on both backends. `QualifierOrderTest` (section 6) checks this property on random data against Accumulo's `Key.compareTo`.

**Guard rather than escaping.** The encoding needs cf and cq to contain no `00`, so the DAO rejects such a graph IRI or qualifier on write, the way `checkNoReservedBytes` already rejects `00`/`01` in values.
- **Cost:** a graph IRI can't contain U+0000. SPARQL, Turtle and N-Triples already forbid it in IRIs, so in practice nothing changes.
- **Gain:** qualifiers stay readable in the HBase shell, and the prefix and regex filters below stay simple.

Byte-stuffing (`00` written as `00 FF`, terminator `00 01`) would lift the restriction, at the cost of an escape step on every cell and less readable filters. The guard applies on both backends, so data stays portable between them.

**Family settings per table kind:**

| Kind | Tables | Settings |
|---|---|---|
| `STATEMENTS`, `INDEX` | `spo`, `po`, `osp`, `text`, `geo`, `graphs` | `VERSIONS => 1`, `BLOOMFILTER => NONE` (reads are prefix scans, which row blooms don't help), `DATA_BLOCK_ENCODING => FAST_DIFF` (or `ROW_INDEX_V1`, chosen in phase 5), `COMPRESSION => ZSTD`, tag compression on |
| `COUNTERS` | `eval` | as above but `BLOOMFILTER => ROW` (exact-row reads), plus the counter settings of 3.8 |
| `METADATA` | `ns` | `VERSIONS => 1`, `BLOOMFILTER => ROW`, no labels |

HBase advises at most two or three families per table, which rules out one family per graph. The family name is a single byte, because HBase repeats it in every cell's key.

### 3.3 Table by table

| Table | Accumulo cf / cq / cv | HBase qualifier | Notes |
|---|---|---|---|
| `spo`, `po`, `osp` | graph / qualifier (normally empty) / label | `graph 00 qualifier 00 label` | |
| `text`, `geo` | graph / qualifier / label | same | `IndexKeys` builds the same rows |
| `graphs` | `g` / empty / label | `g 00 00 label` | |
| `ns` | `namespace`, `version` or `index` / empty or index id / none | `namespace 00 00`, `version 00 00`, `index 00 text 00` | unlabelled, so any reader sees it, as today |
| `labels` (new, HBase only) | — | `canary 00 00 label`, row = the label | one canary cell per registered label, under that label (4.2). `purge` and `dropGraph` leave it alone; `dropAndDestroy` deletes it |
| `eval` | `s`, `p`, `o`, `sp`, `po`, `so` / empty or graph / label | `p 00 http://ex.org/g1 00 label` | values are 8-byte longs instead of decimal text, read only through the counter API |

### 3.4 Single-range reads (`Scanner`)

| Accumulo | HBase |
|---|---|
| `createScanner(table, auths)` | `Scan` on `AsyncTable` or `Table`, with `setAuthorizations(...)` always set, and rya-ng's reserved label when there are none (4.2) |
| `setRange(new Range(start, end))`, both ends inclusive | `withStartRow(start, true).withStopRow(end, true)` |
| `Range.prefix(p)` | `withStartRow(p).withStopRow(next(p), false)`, or `setStartStopRowForPrefixScan(p)` |
| `Range.exact(row)` | `Get(row)` (one call, row bloom filter) |
| `Range.exact(row, cf, cq)` (index probes) | `Get(row)` with `ColumnPrefixFilter(cf 00 cq 00)` |
| `setBatchSize(n)` | `setCaching(n)`. Small lookups also get `setReadType(PREAD)` and, when there is a cap, `setLimit(n)` (`setOneRowLimit()` for one row). The scan then returns its data and closes in the call that opens it. `Scan.setSmall` is gone in 3.0 |
| `fetchColumnFamily(g)` | `ColumnPrefixFilter(g 00)`. Several graphs: `MultipleColumnPrefixFilter`. Both seek inside the row, like Accumulo's column-family skipping |
| `fetchColumn(cf, cq)` | `ColumnPrefixFilter(cf 00 cq 00)` |

The cost of a graph-restricted scan is the same on both. Without a locality group, Accumulo reads every cell of the range and skips other families. HBase reads every cell and skips other qualifier prefixes.

**The skip scan of `GraphSizes`** is already client-driven: one seek per predicate, done as a new range each time. On HBase each step is a `Scan` from `predicate 01` with `setLimit(1)`, `setCaching(1)` and `PREAD`: one call per predicate, as today.

**A paused reader.** Results are streamed, so a slow HTTP client can pause a scan for a long time. HBase handles that in both lines:
- **2.x:** the synchronous scanner reopens itself from the last row returned when its 60-second lease has expired.
- **3.0:** the scanner renews its lease while it waits. It then holds a server-side scanner until it is closed, so every rya-ng iterator must close its scanner in a `finally`, which the code already does.

Neither keeps one snapshot across the pause, and neither does Accumulo's scanner without isolation, so results behave the same. `SlowConsumerIT` guards the behaviour.

### 3.5 Multi-range reads (`BatchScanner`)

`BatchLookup` (batched joins), `IndexSearcher`, `GraphSizes`, `size()` and the index rebuild pass many ranges at once. Accumulo's `BatchScanner` groups them by tablet server, sends each server one request for all of its ranges, and runs a few servers in parallel. HBase has no such call.

The HBase provider does the grouping itself:
1. Find the regions with the connection's cached region locations (`AsyncTableRegionLocator`). Split each range at region boundaries.
2. For each region, run one `Scan`:
   - from the region's smallest start to its largest end, covering just its ranges;
   - filtered by `MultiRowRangeFilter` over those ranges, which seeks from one range to the next on the server;
   - plus the column filter and regex.

   The explicit start and stop matter: the client does not derive them from the filter, so a `MultiRowRangeFilter` scan without them visits every region, one after another (HBASE-28623, closed as Won't Fix with that advice). Phoenix does it the same way.
3. Start the region scans together on the async client, with a cap on how many are in flight per region server (derived from `query.numthreads`). Return cells as they arrive, unordered, as `BatchScanner` does.
4. **Exact rows only** (the counters in `GraphSizes`, exact index entries): use `AsyncTable.getAll(List<Get>)` instead. The client groups the `Get`s per region server into one multi-request each, which is what `BatchScanner` does with exact ranges.

A region scan that fails because its region moved is retried by the HBase client. A batched lookup is never left partly filled, because `BatchLookup` registers a batch whole or not at all, and that logic doesn't change.

**Cost compared with Accumulo** (risk P2):
- A batch touching R regions on S servers costs R scans on HBase, against S requests on Accumulo.
- All R scans run at once, and each is a single call for small results.
- So latency is bounded by the slowest region, not by the sum over regions.

`RangeParityIT` and the trace's per-query scan counts check this.

### 3.6 Server-side filters

| Accumulo | Where | HBase |
|---|---|---|
| `RegExFilter` on the row: Java regex, `matches()`, UTF-8, no flags (checked in the 2.1.6 sources) | object-type check `ri`; `conf.getRegexSubject/Predicate/Object` | `RowFilter(EQUAL, new RegexStringComparator("\\A(?:" + re + ")\\z", 0))`. `RegexStringComparator` matches substrings (`find()`) and compiles with `DOTALL` by default, so the anchors and the flags argument are both needed for identical matches. Its default charset is already UTF-8; set it explicitly anyway |
| `RegExFilter` on the qualifier (`riq`) | pattern with a qualifier and no graph | `QualifierFilter` with an anchored regex over the encoded qualifier: `\A[^\x00]*\x00(?:re)\x00` |
| `TimestampFilter` | `query.ttl` | `Scan.setTimeRange(start, end + 1)`. HBase's upper bound is exclusive; Accumulo's `setEnd(…, true)` is inclusive |
| `IteratorSetting`s from the conf | none in main code | not offered on HBase: the store refuses to open with them |

`setTimeRange` is a small gain over Accumulo: HBase skips whole store files whose time range doesn't overlap.

### 3.7 Writes

**Writer.** The SPI writer holds one buffered mutator per table, on the async connection. `flush()` flushes all of them at once and waits for all of them.
- Flushing them one after another would multiply the latency of every Sail commit by the number of tables touched (up to 7), because the Sail flushes after every `add` call. That is risk P3.
- Concurrent flushes cost one round trip, like `MultiTableBatchWriter.flush()`.
- Errors surface on `flush()` or when the buffer fills, as `MutationsRejectedException` does today, and become `RyaDAOException`.

**Writer settings:**

| Accumulo `BatchWriterConfig` | HBase |
|---|---|
| `setMaxMemory` | the mutator's `writeBufferSize` |
| `setMaxLatency` | `setWriteBufferPeriodicFlushTimeoutMs` |
| `setMaxWriteThreads` | the client's per-server and total in-flight task limits |
| `setTimeout` | operation timeout |
| `setDurability(DEFAULT, NONE, LOG, FLUSH, SYNC)` | `USE_DEFAULT`, `SKIP_WAL`, `ASYNC_WAL`, `SYNC_WAL`, `FSYNC_WAL`, per mutation. Whether `FSYNC_WAL` syncs to disk rather than only flushing is not confirmed; phase 0 checks it, and until then it is documented as equivalent to `SYNC_WAL` |

**Versions and re-inserts** behave the same way:
- Puts carry the statement's timestamp, and `VERSIONS => 1` keeps the newest, as Accumulo's versioning does.
- A delete at timestamp T masks later puts with a timestamp at or below T until a major compaction, on both systems. So a loader rerun with its fixed timestamp after a delete stays hidden on both.
- HBase's `NEW_VERSION_BEHAVIOR` would change this, so it stays off for parity.

### 3.8 Statistics counters

This is the one place where HBase lacks a built-in equivalent.

**What Accumulo does.** Counter updates are blind writes (`+n` as decimal text). The `SummingCombiner` adds them up at scan time, at flush (minor compaction) and at major compaction. A write never reads, and writers never contend on a hot row, such as the `p` counter of `rdf:type`, which nearly every typed statement updates.

**Option A: native `Increment`.**
- **How it works:** the region server takes the row's exclusive lock, reads the current value under that lock, and writes the sum.
- **Advantage:** no server-side code; one cell per counter and label; exact reads; retries protected by nonces.
- **Cost:** in a batched `multi` call, a region server groups consecutive puts and deletes into one write and one WAL sync per mini-batch (up to 20,000 mutations). But it runs **each `Increment` on its own, with its own WAL sync** (`RSRpcServices.doNonAtomicRegionMutation`).
  - `eval` gets 6 to 12 counter updates per statement, the largest share of all written cells. So this option turns most of a load's writes into individual synced writes.
  - There are no recent benchmarks. Older ones agree on the size of the gap:
    - about 20k against 4k increments per second (HBASE-15213);
    - for 10k operations, 1.8 s with puts against 16.8 s with increments (CDAP's measurements).
  - Hot rows, such as the `p` counter of `rdf:type`, also serialise on the exclusive lock.

  The loader sums counters per block, which reduces their number but not the per-increment cost. **This option should be kept only as a fallback.**

**Option B: write-only counters, summed by a rya-ng coprocessor** (recommended). This is the counterpart of Accumulo's combiner:
1. **Writes are plain puts.** The client writes each delta as a `Put` carrying the counter's label, so deltas are batched like every other write, take only the shared row lock, and are never read first.
2. **Unique timestamps.** On `eval`, a `RegionObserver` from the `rya.hbase.coprocessor` jar gives each delta a timestamp in `prePut`, in milliseconds. Uniqueness is only needed per counter cell (row and qualifier), so the timestamp is `max(now, last timestamp given to this cell + 1, floor)`:
   - **The per-cell memory:** "last" comes from a small in-memory map per region. An entry is dropped once the clock has passed it, so the map only holds cells written in the last millisecond or so.
   - **The floor:** it is the newest timestamp in the region's store files, read once at region open. It covers cells moved from another server.
   - **What this gives:** timestamps stay at the clock except for a cell written more than once per millisecond, and that cell catches up as soon as its rate drops. So concurrent deltas never collide, and the client's label is left untouched.
   - **Settings it rules out:** the family keeps all versions, and nothing time-based may be enabled on `eval`: no TTL, no date-tiered compaction.
3. **Summed reads.**
   - `preGetOp` and `preScannerOpen` make every read of `eval` ask for all versions, so a client can never get a partial sum.
   - `postGetOp`, and a scanner wrapper installed by `postScannerOpen`, replace the versions of each (row, qualifier) with one cell holding their sum and the newest timestamp.
   - They work on what the visibility filter has already let through, and labels are never mixed, because the label is part of the qualifier.
   - **Partial batches:** the scanner wrapper keeps a running sum across `next()` batches. A hot counter can have more un-summed versions in the memstore than one response may carry (2 MB by default), so a qualifier's versions can arrive split over two batches. Emitting a sum per batch would return two partial counts.
4. **Summed flushes and full compactions.** `preFlush` and `preCompact` fold the versions the same way:
   - The wrapped scanner implements `Shipper`, so blocks are released.
   - Partial compactions are not folded, because a delete marker in a file left out could then be bypassed. That is the hazard the Accumulo `Combiner` javadoc warns about, and rya-ng's current combiner setting does not avoid it (see "Side findings" in section 1).
   - In-memory compaction is off on `eval`, for the same reason.
5. **Deletes work as on Accumulo.** The deleter reads the summed cell, then deletes that column at its timestamp and older, carrying its label. That removes every delta.

HBase's example module has a `WriteHeavyIncrementObserver` on a similar idea (HBASE-19033), but it is not reusable as it is:
- it drops the label of every counter;
- its `preGetOp` re-scans past the visibility filter and sums across labels;
- it doesn't sum scans;
- retries double-count.

rya-ng's coprocessor is small (an estimated 300 to 500 lines) but has to be written and tested for this purpose.

**What a retry does.** A retried delta write can be counted twice. Accumulo's blind writes have the same property, and counters are approximate by design.

**The counter mode is fixed per store and recorded in `ns`** (row `rya`, family `counters`):
- Option B needs the coprocessor for correctness, not only for speed. Without it, deltas written in the same millisecond have the same key, and all but one are lost.
- The two modes also store different things: one cell per counter for A, many versions for B.

So every writer must agree on the mode, and `init()` checks that the table matches the record: the coprocessor attached and all versions kept for B. Reading a table descriptor needs ADMIN or CREATE on HBase. So, like `ensureStatsCombiner`, `init()` trusts the record when the user may not read the descriptor, and read-only users can still open the store.

**Decoding.** Values are 8-byte big-endian longs on HBase (as `Increment` requires, and used for both options). `getCardinality` and `GraphSizes` read them through the SPI's counter API, which returns (row, cf, cq, cv, sum) and keeps both backends' value formats out of the shared code.

**Proof:** `CounterIT` (concurrency, compactions, deletes, read-only reader), plus `AccumuloStatsAndGraphsIT` and `LoaderIT`'s counter check, which compares logical sums on both backends.

### 3.9 Deletes, graph drops, purge

- **Exact delete** (`deleteMatches`, stale graph markers, index deletes): `Delete(row).addColumns(c, qualifier, ts)`, which deletes that timestamp and older like Accumulo's `putDelete(cf, cq, cv, ts)`.
  - It carries the cell's own label with `setCellVisibility`. HBase requires this: a labelled cell is removed only by a delete whose expression is the same after normalisation, so `a&b` matches `b&a` but `a` doesn't match `a|b`. A delete without a label removes only unlabelled cells.
  - The label comes from the qualifier (3.2). This is the second reason it has to be stored there.
- **`deleteLatest`** (namespace removal): `Delete(row).addColumns(c, qualifier)` at the current time. `ns` cells have no label, so the delete carries none.
- **`deleteVisible`** (`dropGraph`, `purge`):
  - The parallel region scans of 3.5 run with the caller's labels and a `KeyOnlyFilter`. Each returned cell becomes an exact delete through the writer.
  - That is what Accumulo's `BatchDeleter` does: it also scans to the client and writes deletes back.
  - HBase's example `BulkDeleteEndpoint` could do it on the server instead. That is a later optimisation, not needed for parity.
- **`getContextIDs`, `size()` and `purge`:** unchanged logic on top of the SPI.

### 3.10 Tables, init and permissions

- **`init()`.** The logic stays the same:
  - It creates missing tables (`Admin.tableExists`, then `createTable`); a `TableExistsException` from a racing client is ignored.
  - It checks the counter mode.
  - It writes the storage version only if it is missing.
  - It must not write to an existing store.
  - **Admin calls and read-only users.** With HBase's `AccessController`:
    - `tableExists` needs no grant;
    - `getDescriptor` needs ADMIN or CREATE;
    - `listTableDescriptors` silently returns nothing for a read-only user.

    So the counter-mode check reads the descriptor only when it is allowed, and catches `AccessDeniedException`, as `ensureStatsCombiner` does today.
- **Splits.** `prepare` should compute its split points first and create each table pre-split: `createTable(descriptor, splitKeys)` on HBase (synchronous), `NewTableConfiguration.withSplits` on Accumulo.
  - On HBase, splitting an existing table is asynchronous, one procedure per split point, each waiting for its region to reopen.
  - `SplitPlanner` only needs the serializers, not the tables, so the reordering is easy, and it helps Accumulo too.
- **`listSplits`:** the region start keys from `RegionLocator`.
- **Server count:** `Admin.getRegionServers()`.
- **`dropAndDestroy`:** disable, then delete each table.
- **Coprocessor deployment.** The `eval` table descriptor names the coprocessor class and the jar's location on HDFS (table-level loading, no region server restart), or the class alone when the jar is on the region servers' classpath.

### 3.11 Connection and configuration

- **Connecting.** `ConnectionFactory.createAsyncConnection(conf)`, with `conf` built from one or more `hbase-site.xml` files (`rya.hbase.site`) plus overrides. The cluster is found through ZooKeeper or through the RPC-based connection registry (bootstrap servers).
- **Authentication.** HBase clients authenticate with SIMPLE (an asserted user name, so not a security boundary) or Kerberos (principal and keytab). Delegation tokens are issued only to clients that already authenticated.
  - **No password.** HBase has no password authentication. The pluggable SASL example that adds one still needs Kerberos on the servers, and OAuth support was never released.
  - **TLS:** HBase 2.6 and 3.0 support TLS, including client certificates, but a certificate doesn't become the HBase user.
  - **Effect on rya-ng:** `RYA_ACCUMULO_PASSWORD` has no HBase counterpart. `configure.sh` asks for a principal and keytab, plus optional TLS trust and key stores, and still never writes a secret.
- **Artifacts.** Use the shaded client that leaves Hadoop to the caller (`hbase-shaded-client-byo-hadoop`), with the Hadoop client 3.5.0 the root POM already forces:
  - **Fewer conflicts:** there is no second Hadoop version on the classpath, and HBase's relocated dependencies (protobuf, Netty, Guava) stay away from Spring Boot and Accumulo.
  - **The DNS bug:** it is also the workaround for HBASE-30211. On Java 18 and later, the regular shaded client and shaded testing artifacts of all current releases break DNS resolution for the whole JVM; the fix ships only in 2.5.17, 2.6.8 and 3.0.1, none of them released yet.
  - **Don't use the plain `hbase-client:2.6.x` artifacts:** they pull in Hadoop 2.10.

  Whether `byo-hadoop` works with `hadoop-client-api`/`-runtime` 3.5.0 on JDK 25 is the first thing phase 0 checks.
- **Loader `check`:** reports the user, region servers, labels and permissions (`AccessControlClient.getUserPermissions`).

### 3.12 Limits

- **Row size.**
  - **The two limits:**
    - Accumulo limits a key (row, family, qualifier and label together) to 1 MB. That is `DefaultKeySizeConstraint`, which every table rya-ng creates gets by default (checked in the 2.1.6 sources).
    - HBase limits a row key to 32,767 bytes. A `Put` with a longer row throws `IllegalArgumentException` in its constructor, before anything is sent.
  - **Where rya-ng puts long values:** whole literals go in rows. The encoded object goes in `spo`/`po`/`osp`, and the lexical value in `eval`'s `o`, `po` and `so` rows.
  - **The gap:** a literal over about 32 KB, such as a long text or a detailed WKT polygon, can be stored on Accumulo but not on HBase. This is the one functional gap, and a decision for you (D1 in section 1).
  - **What the provider must do at least:** build every cell of a statement before buffering any of them, so that an over-long row rejects the whole statement with a clear error. Otherwise the statement tables and the counters would be left half-written.
- **Family names** are limited to 127 bytes. That isn't a concern, since there is one family, `c`. Qualifiers have no practical limit. A cell may be up to 10 MB by default.

---

## 4. Labels and authorizations on HBase

rya-ng's security model (ARCH.md §16) must hold unchanged: every derived cell carries the statement's label, and every read uses the connection's authorizations.

### 4.1 Enabling labels

HBase enforces labels with the `VisibilityController` coprocessor, listed in both `hbase.coprocessor.master.classes` and `hbase.coprocessor.region.classes` (after `AccessController`, if that is used too), with HFile format 3, the default. Labels and user grants live in HBase's `hbase:labels` table.

**`hbase.security.authorization=true` is required too.** Phase 0 showed that without it the controller loads, labels can be registered, and every reader still sees every cell, under any authorizations.

So the provider doesn't trust the configuration: when a store opens with labels, it runs a **negative probe**. It reads the `labels` table (3.3) with only the reserved label `rya.none`. Any labelled canary it can see means labels aren't enforced for this account, either because of the setting above or because the account is a superuser, and the store refuses to open.

### 4.2 Reading

HBase's default rules for a read's labels differ from Accumulo's in every case that matters:

| Case | Accumulo | HBase (default scan-label generators) |
|---|---|---|
| no labels, or an empty set | only unlabelled cells | **every label the user holds** |
| a label the user doesn't hold | the scan fails | the label is dropped, with a WARN in the region server log; results just shrink |
| the user is a superuser, or holds HBase's `system` label | no special case: root needs grants too | **sees every cell**, whatever it asks for |
| reading one's own labels | allowed | not available to normal users: phase 0 saw `getAuths` return an empty list, not an error, so it can't even be used as a check |

The provider restores Accumulo's behaviour with four rules:
1. **Never send an empty set.** For "unlabelled cells only", send rya-ng's reserved label `rya.none`. It is registered once and granted to every account that reads, and is never put on a cell.
   - **Why it works:** the requested set is then non-empty and held, so HBase keeps it, and only unlabelled cells match. Confirmed in phase 0.
   - **Why not an unheld label:** HBase would drop it to the same effect, but it logs a WARN on the region server for every such read.
   - **Why not a server-side scan-label generator:** it would be cleaner, but it is a cluster-wide setting that would change behaviour for every other HBase application on the cluster.
2. **Use non-superuser accounts.** Every account that reads with labels must be a non-superuser without `system`: the endpoint, the loader's `verify`, and the tests. HBase's process user is always a superuser, so this holds for the mini cluster too.
3. **Check held labels at start-up, since HBase won't reject unheld ones.**
   - **The check:** when a label is registered through rya-ng (D3), rya-ng also writes one *canary* cell under that label, in a small `labels` table of its own (3.3). `purge` doesn't touch that table, so the canaries survive it. At start-up, the endpoint reads the canaries of `rya.sparql.authorizations`, and refuses to start if one is missing: either the account doesn't hold that label, or the label wasn't registered.
   - **Why it's enough:** per request, the endpoint already rejects `auths=` outside the configured set with 403. So the chain user labels ⊇ configured ⊇ requested holds as on Accumulo, and a deployment error fails at start-up instead of silently returning fewer rows.
4. **Full-label reads send no labels.** Index rebuilds and `IndexVerifier` read with "all of the user's labels", which on HBase is exactly an empty set. They must not compute the set with `getAuths`, which returns nothing for a normal user.

Labels are read back from the qualifier (3.2), never from tags.

### 4.3 Writing and deleting

- **Unknown labels.** Accumulo accepts any label. HBase only accepts labels already registered with `VisibilityClient.addLabels`.
  - A write that uses an unknown label fails with `FailedSanityCheckException`, which is not retried. Through a buffered writer it arrives wrapped in `RetriesExhaustedWithDetailsException`.
  - A malformed expression fails the whole batch.
  - Registering labels needs a superuser or the `system` label (and WRITE on `hbase:labels` under `AccessController`).
  - So the policy is a decision for you (D3), and the provider maps both failures to a clear error naming the label.
- **Labels the writer doesn't hold.** On both systems a writer may write labels it doesn't hold: HBase's `hbase.security.visibility.mutations.checkauths` is false by default.
- **Deletes** must carry the label of the cell they delete (3.9). They may only use registered labels, which is always the case for a stored cell.

### 4.4 Expression syntax

**What the two have in common.** Both use `&`, `|`, parentheses and quoted labels (`"…"`, with `\"` and `\\` escapes). Unquoted labels may use the same characters, `[A-Za-z0-9_\-:./]`.

**Where they differ:**
- **NOT:** HBase also has `!`, which Accumulo lacks.
- **Mixing `&` and `|` without parentheses:** Accumulo rejects `a&b|c`. HBase accepts it and evaluates left to right, with no precedence.
- **Empty expressions:** HBase rejects an empty expression, so unlabelled cells get no `CellVisibility` at all.

rya-ng must accept exactly the same expressions on both backends, so:
- validation (`SparqlController`'s `visibility=` check and the DAO) parses Accumulo's grammar on both;
- the HBase provider writes the parsed expression back fully parenthesised, quoting labels with `CellVisibility.quote` where needed;
- the qualifier stores the expression exactly as the statement gives it, as Accumulo stores it.

**A parser that doesn't need `accumulo-core`.** `accumulo-core` is a heavy dependency for the HBase build. Apache's standalone `accumulo-access` library parses and evaluates the same syntax with no dependencies, but has only beta releases so far: `org.apache.accumulo:accumulo-access-core:1.0.0-beta3`, Java 11.
- **Recommendation:** a small parser of the Accumulo grammar in `rya.store`, about 200 lines.
- **Its test:** it is checked against `ColumnVisibility` on random expressions in the Accumulo provider's tests.
- **Later:** switch to `accumulo-access` once it is released as GA.

Normalising expressions (as `ColumnVisibility.flatten` does) isn't needed: HBase normalises them itself when it matches deletes.

### 4.5 Administration

| Operation | Accumulo | HBase |
|---|---|---|
| who am I | `client.whoami()` | `User.getCurrent()` |
| a user's labels | `getUserAuthorizations` (allowed for one's own) | `VisibilityClient.getAuths` (superuser or `system` only, when `AccessController` is on) |
| grant labels (tests, loader `prepare`) | `changeUserAuthorizations` | `VisibilityClient.setAuths`, after `addLabels` (superuser) |
| permissions | system and table permissions | `AccessController` grants: READ, WRITE, EXEC, CREATE, ADMIN |

Account roles:
- **A read-only endpoint account** needs READ on the rya-ng tables. It must not be a superuser (4.2).
- **Writers** need WRITE.
- **The account that runs `prepare`** needs CREATE, plus superuser rights to register labels and to grant them.
- **The loader's `check`** reports which of these the account has. The checks are partial: a normal user can't read its own labels.

**In production, labels are only a boundary with Kerberos.** Under SIMPLE authentication the user name is just asserted by the client, so labels and ACLs protect nothing against a hostile client. Accumulo always authenticates its clients, by password or Kerberos. Parity on HBase therefore means Kerberos.

---

## 5. The pluggable mechanism

### 5.1 Where to cut

| Cut point | What each backend implements | Verdict |
|---|---|---|
| Above the DAO | `RyaDAO`, `RdfEvalStatsDAO`, `RyaQueryEngine`, `IndexSearcher`, `GraphSizes`… | **No.** About 2,500 lines of backend-independent logic would exist twice: `deleteMatches`, counter batching, stale-marker clean-up, the index lifecycle, `IndexSearcher`'s text planning. So would the invariants of ARCH.md §20. |
| **At the cell level** | a small storage SPI shaped like what rya-ng does: labelled cells, ranges, multi-range scans, a multi-table writer, counters, admin, security | **Yes.** Everything above it is written once. Each SPI call maps to exactly one native operation, so the layer adds no round trips. |
| Plain byte key/value | `get`/`put`/`scan` on bytes | **No.** Labels, columns, server-side filters and counters would all be emulated on the client, which costs speed and, for labels, security. |

### 5.2 The SPI

A sketch, to fix the shape. Names are provisional.

```java
// module rya.store, package io.github.dgreco.rya.store.spi
public interface StoreProvider {                      // found with java.util.ServiceLoader
    String id();                                      // "accumulo", "hbase"
    Store open(StoreSettings settings);               // or wrap a client the caller owns
}

public interface Store extends AutoCloseable {
    Capabilities capabilities();
    Tables tables();        // exists, create(name, TableKind, splitPoints), delete, splits, addSplits, serverCount
    Access access();        // whoami, registerLabels, grantLabels, checkExpression(cv), mayReadSettings(table)
    CellWriter writer(WriterOptions options);                                  // all tables, one flush
    CellScanner scan(String table, KeyRange range, ScanSpec spec);             // ordered (Scanner)
    CellScanner scan(String table, Collection<KeyRange> ranges, ScanSpec spec);// parallel, unordered (BatchScanner)
    void deleteVisible(String table, Collection<KeyRange> ranges, ScanSpec spec); // BatchDeleter
    CounterScanner counters(String table, Collection<KeyRange> rows, ScanSpec spec);
}

public record Cell(byte[] row, byte[] family, byte[] qualifier, byte[] visibility, long timestamp, byte[] value) {}
public record KeyRange(byte[] start, boolean startInclusive, byte[] end, boolean endInclusive) {} // null = unbounded
public record ScanSpec(Labels auths, Columns columns, Pattern rowRegex, Pattern qualifierRegex,
                       TimeRange time, int batchHint, int threads) {}

public interface CellWriter extends AutoCloseable {
    void put(String table, Cell cell);
    void delete(String table, Cell key);              // exact row, cf, cq, cv; this timestamp and older
    void deleteLatest(String table, byte[] row, byte[] family, byte[] qualifier); // ns records
    void addToCounter(String table, CounterKey key, long delta);
    void flush();                                     // every table, concurrently
}
```

Design rules:
- **The logical model is rya-ng's, not a backend's.** A `Cell` has the five key parts rya-ng uses (row, family, qualifier, label, timestamp), and `TripleRow` converts to it one to one. Each provider maps it to its physical model (section 3.2 for HBase).
- **Ranges carry explicit inclusive flags.** Accumulo's `Range(Text, Text)` includes both end rows. rya-ng's ranges end at `start + FF`, inclusive. `GraphSizes` uses an inclusive start and no end. HBase stop rows are exclusive unless asked otherwise. An implicit convention here would lose or add boundary rows.
- **Counters are an operation, not a put.** `addToCounter` lets Accumulo write decimal text for its combiner, and lets HBase choose its own strategy (section 3.8).
- **`TableKind`** (`STATEMENTS`, `INDEX`, `COUNTERS`, `METADATA`) lets a provider choose physical settings per table: the combiner on counters, bloom filters, block encoding.
- **Capabilities** declare what a provider does natively:

  | Capability | Accumulo | HBase | Bigtable via the HBase API (possible later) |
  |---|---|---|---|
  | `LABELS` (cell visibility enforced on the server) | yes | yes, with `VisibilityController` | no: cell visibility and tags aren't supported |
  | `SERVER_COMBINE` (write-only counters) | yes | yes, with the rya-ng coprocessor | no: no coprocessors; increments only, not batched |
  | `SERVER_REGEX` (row and qualifier regex filters) | yes (Java regex) | yes (Java regex) | RE2 only, so patterns behave differently |
  | `SCAN_ITERATORS` (arbitrary `IteratorSetting`s) | yes | no | no |
  | `MAX_ROW_BYTES` | 1 MB per key | 32,767 | 4 KB |

  A store refuses to open with settings a provider cannot honour. Examples: labels on a provider without `LABELS`, or `setAdditionalIterators` without `SCAN_ITERATORS`. Labels are never emulated on the client: that would let a bug leak data.

### 5.3 Modules after the change

| Module | Today | After |
|---|---|---|
| `rya.api` | model and encodings | unchanged |
| `rya.store` (new) | — | the SPI, plus the shared logic moved out of `rya.accumulo`: the DAO (`AccumuloRyaDAO` becomes `RyaStoreDAO`), query engine, statistics DAO, `GraphSizes`, the index package, the trace package (`AccumuloTrace` becomes `StoreTrace`), and a neutral `StoreRdfConfiguration` |
| `rya.accumulo` | everything | the Accumulo provider only: cell mapping, writer, scanners, deleter, combiner setup, security, `AccumuloClients` |
| `rya.hbase` (new) | — | the HBase provider |
| `rya.hbase.coprocessor` (new) | — | the jar installed on the region servers: the counter combiner. Depends only on `hbase-server`, provided |
| `rya.sail` | depends on `rya.accumulo` | depends on `rya.store`. `RyaSailFactory.createSail(Store, conf)`; `RyaAccumuloSailFactory` stays as a convenience |
| `rya.sparql`, `rya.loader` | Accumulo client | a `backend` setting; both providers on the classpath |
| `rya.test` (from `rya.test.accumulo`) | `MiniAccumuloCluster` singleton | a neutral `StoreITBase` (unique table prefix, grant labels, clean-up), one cluster singleton per backend |

The data flow stays `rya.api` → `rya.store` → `rya.sail` → `rya.sparql`, with the providers plugged in under `rya.store`.

### 5.4 Configuration

- **One setting picks the backend:** `rya.backend = accumulo | hbase`. It is a conf key, an endpoint property, a loader option and a `configure.sh` question.
- **Neutral configuration:** `AccumuloRdfConfiguration` becomes `StoreRdfConfiguration`. The authorizations are already a `String[]` in `RdfCloudTripleStoreConfiguration`. `getAuthorizations()`, which returns the Accumulo type, leaves the neutral class.
- **Backend-only settings** are namespaced: `accumulo.client-properties`, `hbase.site` (one or more `hbase-site.xml` files), `hbase.counters`. `setAdditionalIterators` becomes an Accumulo-only option (`SCAN_ITERATORS`); only `AccumuloRyaDAOIT` uses it.
- **Table names:** the prefix rule (`<prefix><suffix>`) works on both. HBase also allows `namespace:table`, so `hbase.namespace` can be an option.
- **Switching backends does not move data.** A copy tool can be added later: scan `spo` with full authorizations, write through the target's DAO, and let the counters be rebuilt.

### 5.5 What the shared code must stop doing

| Today | After |
|---|---|
| `RdfCloudTripleStoreConnection` and `IndexTripleSource` test for or cast to `AccumuloRyaDAO` and `AccumuloRdfConfiguration` | they use `RyaStoreDAO` and `StoreRdfConfiguration` |
| `SparqlController` validates `visibility=` with `new ColumnVisibility(...)` | the neutral parser of section 4.4, through `store.access().checkExpression(cv)` |
| `AccumuloTrace.wrap(client)` builds reflection proxies of the Accumulo API | a decorator around `Store`. The SPI is ours, so no reflection is needed, and every provider is traced for free. The thread-local `reason(...)` calls stay where they are. |
| the index rebuild and `IndexVerifier` read `securityOperations().getUserAuthorizations(whoami())` | a `ScanSpec` asking for "every label the user holds" (`Labels.ALL_HELD`). Accumulo resolves it by reading the user's labels; HBase by sending none (4.2) |

### 5.6 Why the layer itself costs nothing, and how that is proven

- **No extra copies.** `RyaStatementKeyValueIterator` already copies every Accumulo `Key` into byte arrays to build a `TripleRow`. Building a `Cell` instead costs the same copies.
- **No extra dispatch cost.** One JVM loads one provider, so every SPI call site is monomorphic and the JIT inlines it.
- **No extra round trips.** Each SPI operation is one native operation: `scan(range)` is one `Scanner`, `scan(ranges)` one `BatchScanner`, `flush()` one `MultiTableBatchWriter.flush()`.
- **Proof:** the before/after Accumulo benchmarks of section 7 gate the merge of phase 1.

---

## 6. Tests

**One suite, two backends.** The ITs run against the backend named by `-Drya.test.backend` (`accumulo` by default). The existing flags keep working on both: `-Drya.test.joinBatchSize=2`, `-Drya.join-batch-size=2` and `-Drya.test.indexes=true`.

**What already runs on either backend.** 22 of the 30 `*IT` classes use no Accumulo API. They reach the store only through `RyaSailITBase`/`IndexITBase`, the DAO, the Sail or the endpoint. They do name rya's Accumulo-named types (`AccumuloRdfConfiguration`, `AccumuloRyaDAO`), which phase 1 renames mechanically, so they run on HBase once `StoreITBase` replaces `AccumuloITBase`. The other 8 read or write Accumulo directly and need porting:
- `AccumuloITBaseIT`
- `AccumuloRyaDAOIT`
- `AccumuloStatsAndGraphsIT`
- `AccumuloIndexMaintenanceIT`
- `IndexSearcherIT`
- `AccumuloTraceIT`
- `SparqlEndpointIT`
- `LoaderIT`

Their direct reads become SPI scans. The assertions that only make sense on Accumulo (iterator settings, `FirstEntryInRowIterator`) move to Accumulo-only tests.

**New tests:**

| Test | Checks |
|---|---|
| `QualifierOrderTest` (unit, property-based) | for random (cf, cq, cv) triples, the encoded HBase qualifiers sort exactly like Accumulo `Key`s, and decode back |
| `RangeParityIT` | the same cells and the same `KeyRange` set give the same rows on both providers. Covers inclusive and exclusive ends, `FF` ends, empty and unbounded starts, and ranges across region and tablet boundaries |
| `LabelParityIT` | one statement under two labels, with the same timestamp; readers with A, B, A+B and no labels (the reserved `rya.none` label); deleting one label's copy; graph markers; expressions mixing AND and OR; start-up refusal when the canary of a configured label is missing |
| `CounterIT` | N concurrent writers adding +1 and −1; a hot counter whose versions exceed one response; forced flushes and major compactions; drop-everything deletes; a read-only reader; the counter mode recorded in `ns` |
| `SlowConsumerIT` | a result stream paused longer than the scanner lease still returns every row once |
| `LoaderIT` on HBase | compares counters as logical sums, not physical cells |

**The HBase test cluster:**
- **What it runs:** the mini cluster runs with `VisibilityController` on, as in production.
- **Who the tests run as:** the process user is always an HBase superuser, and superusers see every cell. So `StoreITBase` gives each test two connections:
  - an admin connection for set-up (tables, labels, grants);
  - a connection as a normal test user (`User.createUserForTesting`), which every rya-ng object uses.

  `grantAuthorizations(...)` registers the labels and grants them to that user. Without this split, visibility tests would pass for the wrong reason.
- **The JVM:** the in-process mini cluster also starts an in-process HDFS by default, both on JDK 25, which no HBase release supports. Phase 0 tries it, also with a local-filesystem root instead of HDFS. If it fails, the ITs use an HBase cluster in a container (Testcontainers) on JDK 17 or 21, with only the client in the test JVM.
- **Cost:** a MAC test costs about 5 s, mostly creating the six tables. Creating tables on HBase is slower, so phase 0 measures it. If it's too slow, each test class reuses its tables and deletes their rows between tests, instead of creating a prefix per test.

**CI.** One job per backend. The HBase job doubles the ITs' time (about 15 min today), so it could run on pushes to `main` and nightly, with the Accumulo job on every pull request.

---

## 7. Performance: what "no speed penalty" can mean, and how it is measured

"No speed penalty" covers two different claims, and they deserve different promises.

**Claim 1: putting Accumulo behind the SPI costs nothing.** This can be promised and proven. Section 5.6 explains why the layer is free. Phase 1 merges only if the Accumulo benchmarks below are within noise of `main`, which means within 3 % on throughput and p99.

**Claim 2: HBase is as fast as Accumulo.** This is a design target, not a promise. The mapping avoids every structural penalty: it keeps the same number of round trips, adds no read-before-write and no client-side filtering, and keeps the same server-side seeks. But the two engines differ, and some operations will be faster on one than on the other. What remains is a list of named risks, measured on the same hardware with the same data.

### 7.1 Risk register for HBase

| # | Risk | Why | Mitigation | Measured by |
|---|---|---|---|---|
| P1 | Counter writes | `eval` gets 6 to 12 cells per statement, more than the other tables combined. A native `Increment` locks its row, reads, and syncs the WAL on its own instead of joining a batch | deltas as plain puts, summed by a coprocessor (section 3.8) | loader triples/s; single-statement INSERT p99 at 16 concurrent writers; counter-heavy reads (`getCardinality` during planning) |
| P2 | Round trips per batched lookup | Accumulo's `BatchScanner` sends one request per tablet server; HBase needs one scan per region touched. Within one multi-get, a region server handles the actions one after another, on one handler | group ranges by region, one bounded, filtered scan per region, all in flight at once on the async client (section 3.5) | batched-join query latency; scans per query from the trace |
| P3 | Flushing several tables | the Sail flushes after every `add`. One `BufferedMutator` per table, flushed one after another, would add up 7 round trips | flush every table concurrently and wait for all (section 3.7) | INSERT DATA p50 and p99 |
| P4 | Cache density | RFile stores a family, qualifier or label once per run of equal values (relative-key encoding). HBase keeps the full key in every cell, and the qualifier here holds the graph and the label | `DATA_BLOCK_ENCODING => FAST_DIFF` or `ROW_INDEX_V1`, ZSTD blocks, possibly cached compressed | bytes per triple (`dry-run` and on disk); block-cache hit ratio |
| P5 | Labels stored twice | once in the qualifier (to read it back and keep cells distinct), once as a tag (for enforcement) | labels are short; measured with P4 | bytes per triple |
| P6 | Label checks | `VisibilityController` adds a filter to every read and checks every cell's tags; Accumulo does an equivalent check. No published benchmark exists for HBase's cost | none needed if the numbers match | full-scan rate with and without labels |
| P7 | Small scans | every non-batched lookup is one scan | exact rows become `Get`s; small scans use `PREAD` and a row limit so they finish in one call | point-lookup latency |

### 7.2 Benchmarks

Run on one fixed cluster, the same machines for both backends:
- **Load:** `rya.loader` with a fixed dataset of 100 M triples and the same split plan. Report triples/s, with and without indexes.
- **Writes:** a SPARQL `INSERT DATA` of one statement and of 1,000 statements, at concurrency 1 and 16. Report p50 and p99.
- **Queries:** a fixed suite over the loader's dataset, cold and warm cache. It covers:
  - point lookups;
  - a star join and a chain join (batched joins);
  - text and geo searches;
  - `stats:approxSize`;
  - an inference query;
  - a full count.

  Report p50 and p99 latency, and the scans and entries per query taken from the trace.
- **Maintenance:** `DROP GRAPH` of a 1 M-statement graph, and an index rebuild.
- **Micro:** JMH on the cell mapping (qualifier encoding and decoding) and on `Cell` construction, to keep the per-cell cost visible.

Runs:
- **B0:** `main` on Accumulo.
- **B1:** after phase 1, on Accumulo. This gates claim 1.
- **B2:** HBase. Compared with B0 for claim 2.

---

## 8. Plan

Each phase ends green on `mvn clean verify`, and is mergeable alone.

### Phase 0: spike (this gates everything else)

In a throwaway branch:
1. **The client on JDK 25.** Run `hbase-shaded-client-byo-hadoop` 3.0.x, with `hadoop-client-api`/`-runtime` 3.5.0, against a real HBase cluster on JDK 17 or 21: connect, write, scan, Kerberos login. Then try the same with 2.6.x-hadoop3. **If no client works on JDK 25, stop here.** The remaining options would be patching HBase or waiting for HBASE-29728.
2. **The mini cluster on JDK 25.** Start it in this build with visibility labels on. Watch:
   - the async WAL, which reaches into HDFS internals by reflection (`hbase.wal.provider=filesystem` is the fallback);
   - `Subject`-related failures;
   - start-up time.

   Try a local-filesystem root instead of an in-process HDFS. If the mini cluster doesn't work, set up the containerised cluster for the ITs (section 6).
3. **The endpoint classpath.** Check that the shaded client coexists with Spring Boot 4 and Logback in `rya.sparql`, and with `accumulo-core` in the same JVM, and that DNS resolution still works (HBASE-30211).
4. **Label semantics**, with small tests:
   - the reserved `rya.none` label for "unlabelled only";
   - the canary check;
   - deletes carrying labels;
   - two labels on one key.
5. **The remaining unconfirmed facts:** what `FSYNC_WAL` does, and the coprocessor hook order.
6. **Counters.** Prototype the coprocessor and run a 16-writer counter test with forced flushes and major compactions. Compare puts-plus-coprocessor with native increments, on throughput.
7. **Batched scans.** Prototype the region-grouped batched scan and compare its latency with sequential scans.
8. **Table set-up cost.** Measure creating the six tables, against reusing them and deleting their rows.

**Exit:** a go/no-go note, the chosen HBase line and artifacts, the test-cluster setup (in-process or container), and a measured IT cost.

**Results (2026-11-01): go.** Run on GraalVM JDK 25 with HBase 3.0.0 and Hadoop 3.5.0, using the plain `hbase-testing-util`.
- **The runtime.** The client and an in-process mini cluster run on JDK 25. Reflection warnings, such as `HFileSystem`'s block-ordering hook, are logged and harmless.
- **The test cluster.** It runs in the same JVM as `MiniAccumuloCluster`, with this recipe:
  - `startMiniZKCluster()` then `startMiniHBaseCluster(...)`, not `startMiniCluster()`. The latter's in-process HDFS fails with `NoClassDefFoundError: jakarta/servlet/Filter` once Accumulo's Jersey 3 is on the classpath.
  - A local-filesystem root and WAL directory (`file:` URIs), with `hbase.wal.provider=filesystem` and `hbase.unsafe.stream.capability.enforce=false`.
  - Info servers off (port -1), `hfile.format.version=3`, `hbase.security.authorization=true`, and `VisibilityController` on the master and the regions.
  - `hbase.superuser` set to the process user: the mini cluster's region server runs as `<user>.hfs.0`, so the test process isn't a superuser otherwise.
  - Readers are created with `User.createUserForTesting` and `runAs`.
- **The cost.** Start-up takes about 6 s with labels on. Creating the six tables takes about 4 s, and deleting them about 2.6 s, which teardown doesn't need to wait for.
- **Labels, as predicted in section 4, with two corrections.**
  - **Confirmed:** superusers see every cell; empty or null authorizations mean every held label; `rya.none` returns only unlabelled cells; unheld labels are dropped silently; two labels on one key collapse into one cell; a delete must carry the cell's label, and reordered expressions match; unregistered labels are rejected on write; `checkauths` is off by default.
  - **Correction 1:** labels aren't enforced at all without `hbase.security.authorization=true` (4.1).
  - **Correction 2:** `addLabels` and `setAuths` report failures, such as `AccessDeniedException`, inside the response rather than by throwing, so every per-label result must be checked. `getAuths` called by a normal user returned an empty list rather than failing (4.2).
- **The client artifact.** `hbase-client` in main code, `hbase-testing-util` in tests (D4).

### Phase 1: the SPI, on Accumulo only (no behaviour change)

1. Create `rya.store` with the SPI. Move the DAO, query engine, statistics, `GraphSizes`, indexes and trace into it.
2. Write the Accumulo provider. Turn `AccumuloRdfConfiguration` into `StoreRdfConfiguration`.
3. Replace the trace proxies with the `Store` decorator.
4. Replace `rya.test.accumulo` with `rya.test` plus the MAC singleton.

**Exit:**
- all 30 ITs green;
- `QueryTraceIT` unchanged;
- benchmark B1 within noise of B0.

### Phase 2: the HBase provider core

1. Write the connection and admin.
2. Write the cell mapping (section 3.2), with its two write-time guards:
   - no `00` in graph IRIs and qualifiers, on both backends;
   - rows over the HBase limit rejected per statement (D1).
3. Write the scanners, the batched scan, the multi-table writer, exact deletes and the visible-cell deleter.
4. Write label and authorization handling (the reserved label, expression conversion).
5. Write counters in native `Increment` mode, the simple one, so statistics work from this phase on.
6. Write `QualifierOrderTest`, `RangeParityIT` and `LabelParityIT`.

**Exit:** the 22 neutral ITs green on HBase, with every test flag.

### Phase 3: counters and statistics

1. Write the coprocessor jar (section 3.8), and record the counter mode in `ns`, checked by `init()`.
2. Port `GraphSizes` and `getCardinality` to the provider's counter reads.
3. Write `CounterIT`, run in both modes.

**Exit:** `AccumuloStatsAndGraphsIT` and `LoaderIT` (logical counters) green on HBase.

### Phase 4: the rest of the product

1. Text and geo indexes, and tracing (backend-neutral events and wording, "tablet server" becoming "server").
2. The loader:
   - `check` reports region servers, labels and permissions;
   - `prepare` creates pre-split tables;
   - writer options map onto the HBase client.
3. Label administration (D3): a command that registers labels in HBase and writes their canaries. The endpoint's start-up canary check (4.2).
4. The endpoint: `rya.backend` and `rya.hbase.*` settings.
5. `configure.sh`: a backend question, and Kerberos and TLS settings in place of the password.
6. A `DevServer` variant on an HBase mini cluster.
7. Port the remaining 8 ITs.

**Exit:** all 30 ITs green on both backends.

### Phase 5: performance parity and documentation

1. Run benchmark B2, then tune: block encoding, compression, the scanner and writer settings of section 3.
2. Fix whatever the risk register turns up.
3. Update `ARCH.md`, `README.md`, `AGENTS.md`, and `NOTICE` if new third-party code is bundled.
4. Add the CI job.

**Exit:** a B2 report against B0, with every gap explained or fixed.

---

## 9. Sources

**rya-ng.** `ARCH.md` and the code at `860fa66`, in particular:
- `AccumuloRyaDAO`, `AccumuloRyaQueryEngine`, `RyaStatementKeyValueIterator`;
- `EvalStatsMutations`, `AccumuloRdfEvalStatsDAO`, `GraphSizes`;
- `IndexKeys`, `IndexSearcher`, `IndexManager`;
- `TracingProxies`, `AccumuloClients`;
- `AbstractTriplePatternStrategy`, the loader's `Prepare` and `SplitPlanner`.

**Accumulo 2.1.6**, checked in the sources jar:
- `RegExFilter`: `matches()` unless `matchSubstring`, UTF-8, no flags;
- `DefaultKeySizeConstraint`: 1 MB;
- the `Combiner` javadoc on deletes and partial compactions.

**HBase.** The source citations point at branch-2.6 (`a238a8c`), `rel/2.6.7` and `rel/3.0.0`, under https://github.com/apache/hbase:

| Topic | Sources |
|---|---|
| Release lines and the JDK matrix | https://hbase.apache.org/downloads/ and the reference guide's prerequisites page (JDK 25 "not currently supported"); HBASE-29728, HBASE-29546; the 3.0.0 announcement (2026-08-06) |
| Shaded client DNS bug | HBASE-30211 |
| Hadoop | HADOOP-19212, HADOOP-19821, HADOOP-19486; https://hadoop.apache.org/docs/r3.5.0/ |
| Visibility labels | the reference guide's "Visibility Labels" section; `VisibilityController`, `VisibilityUtils`, `DefaultVisibilityLabelServiceImpl`, `VisibilityScanDeleteTracker`, `FeedUserAuthScanLabelGenerator`, `DefinedSetFilterScanLabelGenerator`, `ExpressionParser`, `CellVisibility`; HBASE-10885 (deletes), HBASE-12466 (scan-label generators), HBASE-12470 (labels can't be read back) |
| Two labels on one key | `UserScanQueryMatcher`, `ScanWildcardColumnTracker`, the TODO in `VisibilityScanDeleteTracker`; HBASE-17125 |
| Increments | `RSRpcServices.doNonAtomicRegionMutation`, `HRegion.reckonDeltas`, `AsyncProcess`, `ServerNonceManager`; HBASE-12751, HBASE-14460, HBASE-15213, HBASE-16652; https://github.com/cdapio/cdap-hbase-increments |
| Coprocessors | `RegionObserver` (`rel/2.6.7`); `hbase-examples` `WriteHeavyIncrementObserver` (HBASE-19033); HBASE-19001 |
| Scans | `MultiRowRangeFilter`, HBASE-28623; `Scan` (`setSmall` removed, HBASE-17045); `ClientScanner` (HBASE-16266); `AsyncScanSingleRegionRpcRetryingCaller` (lease renewal); `RegexStringComparator` |
| Limits | `HConstants.MAX_ROW_LENGTH`, `Mutation.checkRow`, `KeyValue.checkParameters`, `hbase-default.xml` |
| Security | `AccessController` (`preGetTableDescriptors`), HBASE-23347 (pluggable SASL), HBASE-26666 and HBASE-27280 (TLS), HBASE-26553 (OAuth, unreleased) |
| Bigtable | https://docs.cloud.google.com/bigtable/docs/hbase-differences |
| Accumulo Access | https://repo1.maven.org/maven2/org/apache/accumulo/accumulo-access/maven-metadata.xml |

**Unconfirmed**, tested in phase 0:
- that any HBase client or mini cluster works on JDK 25, or with the Hadoop 3.5.0 client;
- the reserved-label behaviour for "unlabelled only", derived from code reading;
- what `FSYNC_WAL` does;
- the cost of `VisibilityController` (no published benchmark);
- what retries do to increments across region moves.
