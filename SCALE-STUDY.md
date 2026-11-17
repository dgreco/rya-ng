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

# One trillion triples: study and optimization plan

Status: started 2026-11-16 against `main` at `2321ae3`. Nothing is built yet.

This document drives the work of taking Tripatha to 10^12 triples. Section 2 is the work list and its state.

## How to use this document

- **Three kinds of rows.** Optimizations (`O1`…), measurements (`M1`…) and decisions (`D1`…). Requirements (`R1`…, section 5) say what 1T needs and where Tripatha stands; each one points at the rows that close it.
- **Status values:**
  - `Not started`, `In progress`, `Done` or `Deferred` (with the date and why) for O and M rows;
  - `Missing`, `Partial` or `Present` for R rows;
  - a date and the choice for D rows.
- **When a pull request lands an item:**
  - set its status, and add the pull request number to the row;
  - replace estimates with the measured figures. Estimates are marked *est.*, and measured figures say where they were measured.
- **New findings:** a newly found gap becomes an R row in section 5, with an O row if it needs work. A measurement that contradicts a figure here replaces the figure, with a note in the row that used it.

## 1. Summary

**Yes: a trillion triples is within reach on Accumulo, and later on HBase, for selective queries, without changing the storage format first.**

A lookup on a sorted key-value store costs about the same at 1T as at 1B. It is one seek, more often a cold read from disk, because a few percent of the data fits in cache. Batched joins, entity prefetch, statistics reads, graph sizes and the inference refresh all stay bounded by the size of the query or of the ontology, not of the store (section 5.8).

**What 1T does break:**
- ingest through the write path;
- statistics that are ten times bigger than they need to be;
- maintenance operations that read whole tables;
- queries whose intermediate results are themselves huge.

**Headline numbers** (section 4):

| | Per triple | At 1T |
|---|---|---|
| Bytes sent to the servers, statistics and both indexes on (`dry-run`) | 1,065 B, 11.6 cells | 1.07 PB |
| Same, statistics only | 794 B, 9.6 cells | 0.79 PB |
| On disk after compaction, one copy (README, a test load of unknown size) | about 160 B | about 160 TB, 480 TB with 3 replicas |
| Statistics cells stored after aggregation | 5.4 | 5.4 T, about 90 % of them holding the value 1 |
| Statistics cells stored if totals of 1 are not written | *est.* about 0.5 | about 0.5 T |
| Distinct terms (IRIs and literals) | 0.45 to 0.49 | up to about 450 billion |

**Order of the work, and why** (re-ordered 2026-11-16: internal optimizations first, load work deferred):
1. **Skip counters of 1 in the loader (O1).**
   - The planner already reads a missing counter as 1, so counters holding 1 carry no information. 90 % of the stored counters are such.
   - Measured: the cells sent per triple drop from 11.6 to 8.2, and the bytes from 1,065 to 871.
   - Green on Accumulo, HBase and RocksDB.
2. **A local join benchmark (M8).**
   - A fixed query suite over a generated store, with latencies, a profile, and scans and cells per query from the trace.
   - Every optimization below reports its effect on it, before and after.
3. **Fewer lookups (O15): done.** Batched joins start with small windows, so `LIMIT` queries stop early: `chain-limit` went from 1,003 ranges to 67.
   - O21 (one range per subject) was built and measured, and is **off by default**. It halved the ranges and read 4 times the cells: no gain on warm Accumulo, and 50 to 64 % slower on RocksDB.
4. **Filters as key ranges (O13 done; O14 after O22, D12 decided).**
   - O13: `STRSTARTS` reads only the matching prefix, so `prefix` reads 522 cells instead of 133,579.
   - O14: comparisons on numbers and dates, with the datatype markers of D12 and a storage-version check.
   - **Done meanwhile:**
     - the benchmark's two blind spots (M8): it now reports CPU apart from waiting, and compares settings within one run. It shows Accumulo joins are bound by the servers, not the client;
     - the type check without a regex (O20) and RocksDB key decoding (O19): on RocksDB, range scans are 23 to 31 % faster and joins 13 to 19 %.
   - **Then,** if the split profile supports them: more lookups in flight (O16), less client work per cell (O17), and parallel reads of one large range (O7).
5. **"All entities matching X" (D1): class stars in the benchmark (M10) and merge joins (O22) done; O14 next.**
   - O22 reads a star's constant patterns as subject-sorted `po` ranges and intersects them. On the Accumulo test cluster that is 3 to 10 times faster than one lookup per driver row: `class-large` 1,446 → 137 ms, `class-2` 267 → 47 ms. On RocksDB, where a lookup is cheap, it gains only for lists of similar lengths, so the ratio that decides is per backend (section 4.7).
   - Value conditions in stars are still one lookup per match: O14 is what they need.
   - Stars in the servers (O23) wait for D13. On these queries the client's merge reads 50k to 400k cells in 50 to 140 ms on Accumulo, so the transfer of the lists is not the limit at 10M; it would be with lists of billions.
6. **IRI prefix codes: measured (M9), and deferred.**
   - Short codes for common IRI prefixes save only 4 to 5 % on disk: RFile already stores shared prefixes once, and compresses the rest.
   - They save 25 to 35 % in the block cache and 44 to 57 % of the bytes a writer sends, which isn't enough to justify a storage-format change now (section 6.9).
7. **Whole-table operations and planning at scale (O4 to O6, O8, O9).**
8. **Deferred:** the load work (O2, O3, O10, M2, M6), and with it the 1T runs. Dictionary encoding (O11) and an analytics engine (O12) stay conditional.

**Dictionary encoding comes late on purpose (R1, section 5.1).**
- The statement tables send 446 B per triple (0.5 PB at 1T), but compress about 7× on disk.
- IDs plus a dictionary of up to 450 billion terms come out about even on disk for this data.
- It is a format break that touches value ordering, triple terms, counters, indexes and labels.

So it is a measured go/no-go (M5), and nothing waits for it.

**Decisions for you:**

| # | Decision | Options | Recommendation | Decided |
|---|---|---|---|---|
| D1 | Which queries must work at 1T | (a) selective: lookups, entity stars, batched joins, search, approximate counts; (b) also analytics over the whole store (large scans, exact aggregates) | (a) as the target. (b) only through a separate engine (O12): no single process can stream a large share of 1T rows interactively | 2026-11-16: **"all entities matching X"**: entities of large classes selected by several properties and values. That is (a) plus large stars. It puts O22 and O23 on the plan, and makes O14 (D12) matter more |
| D2 | How the data arrives | (a) one bulk build, then small online updates; (b) a continuous stream at high rate | (a). The online path stays for updates; (b) would need the online path at about 1M triples/s, which nobody has measured (section 5.4) | |
| D3 | Backend for the first 1T run | Accumulo, HBase | Accumulo. Labelled RFiles import directly, and bulk counters sum through the existing combiner. HBase needs label ordinals in its files and unique counter timestamps (section 6.2) | |
| D4 | Source data | (a) the synthetic loader; (b) a real dataset | Both: (a) for the benchmark ladder, (b) for acceptance. (b) must come as N-Triples or N-Quads, which split by line; Turtle can't be parsed in parallel | |
| D5 | What sorts the bulk build | (a) the loader itself: partitioned sorted runs, then one merge per tablet; (b) Spark | (a), unless M6 shows Spark runs Tripatha's JDK 25 code. That is unproven, and (a) reuses the loader's parts, progress and resume | |
| D6 | Counters with a total of 1 | (a) don't write them: the per-subject families in the loader (O1), every family but `p` in a bulk build (O3); (b) write every counter | (a): the same estimates for a load into an empty store (section 6.3) | |
| D7 | The hashed row layout (`tbl.hashprefix`) | keep, deprecate | deprecate: it doesn't do what it is kept for (section 5.3) | |
| D8 | Dropping a graph at 1T | (a) a graph-first table (`gspo`), optional like the indexes, so a drop costs the graph's size; (b) graph tombstones hidden by scan and compaction filters on every backend; (c) accept a full-table scan per drop | (a) if graphs are the unit you load and replace, (c) otherwise. (b) puts a filter on every read path of every backend | |
| D9 | Dictionary encoding | (a) decide after M5, on the criteria of section 6.5; (b) start now | (a) | |
| D10 | Disks | NVMe SSD, HDD | NVMe for interactive use: at 1T most lookups miss the cache, and each miss is one read | |
| D11 | IRI prefix codes in the stored keys | (a) decide after M9, on the criteria of section 6.9; (b) start now; (c) never | (a), and M9 says **not now**: −4 to −5 % on disk is far below the bar. The cache gain (−25 to −35 %) is worth revisiting if a cluster shows lookups bound by cache misses (M3) | |
| D12 | Datatype markers for O14 (section 6.7) | (a) write them on every add from now on, flag new stores, rebuild old stores with O5; (b) no markers, so no O14 | (a) if range filters on numbers and dates matter to the workload. They cost one more `eval` cell per single-statement insert, cover new stores only until a rebuild, and forbid writes from older versions into flagged stores | 2026-11-16: **(a), with a storage-version check.** The workload's value conditions need O14. The DAO gets a version check: the markers raise the storage version, and a DAO refuses to write to a store whose version is newer than its own (older stores stay writable). Today it writes the version when missing and never checks it. A rebuild command for older stores comes with O5. O14 comes after O22 |
| D13 | The subject-partitioned table of O23 | (a) an optional table like the text and geo indexes, with Tripatha server code on the tablet servers (an Accumulo iterator jar, an HBase coprocessor); (b) none, so stars stay with O22 on the client | decide after O22 is measured: (a) only if the client's intersection is the limit. It costs about another copy of `po` on disk and in writes, and an iterator jar to deploy | |

**Where to read what:**

| Section | Content |
|---|---|
| 2 | the tracker: optimizations, measurements, milestones |
| 3 | what "1T" is taken to mean |
| 4 | the measured sizing model |
| 5 | the requirements at 1T, and where Tripatha stands |
| 6 | designs: loader-side counters, bulk build, statistics, graph drops, dictionary, big scans, filters as key ranges, the join path, IRI prefix codes, merge joins and stars in the servers |
| 7 | the phased plan |
| 8 | published trillion-triple results |
| 9 | sources |

---

## 2. Tracker

### 2.1 Optimizations

| # | Optimization | Closes | Gain | Depends on | Phase | Status |
|---|---|---|---|---|---|---|
| O1 | **Loader: don't write per-subject counters of 1** (`s`, `sp`, `so`), summed per block. Includes reading a missing counter as 1 in the inference sum, in `verify` and in `LoaderIT` (section 6.1) | R5 | measured (`dry-run` at 1T, both indexes): cells sent per triple 11.6 → 8.2 (−29 %), bytes 1,065 → 871 (−18 %), `eval` 348 → 154 B. *est.* −20 % on disk (about 100 TB less at 1T with 3 replicas) | — | 0 | Built on branch `loader-skip-singleton-counters`; green on Accumulo, HBase and RocksDB; not yet merged |
| O2 | **Loader at 1T:**<br>• `--triples 1T`<br>• `prepare --tablet-size`, planning splits by size instead of 4 per server<br>• `dry-run` reports stored counter cells and their share of 1s | R8, R9 | tablets near the target size from the start, instead of about 50 GB tablets splitting online | — | later | Deferred (2026-11-16: internal optimizations first) |
| O3 | **Bulk build on Accumulo and RocksDB:**<br>• sorted files per tablet, then an import<br>• every counter of 1 left out except `p`<br>• the DAO's guards in the writer<br>• input from the generator or from N-Triples/N-Quads files<br>(section 6.2) | R4, R5, R11 | no write-ahead log, memory tables or compactions. *est.* counters about 5.4 → 0.5 stored cells per triple, 160 → about 115 B per triple on disk | O1, M6 | later | Deferred (2026-11-16: internal optimizations first) |
| O4 | **Graph drops in time proportional to the graph** (D8) | R6 | a drop reads the graph, not the 5 cells per triple of every table (about 5T cells at 1T) | D8 | 3 | Not started |
| O5 | **Index rebuilds through the bulk build; purge by recreating tables** when the caller sees everything | R6 | a rebuild becomes a batch job; such a purge becomes constant time | O3 | 3 | Not started |
| O6 | **Text planning from per-term posting counts** | R7 | an AND is driven by its rarest word | — | 3 | Not started |
| O7 | **Parallel reads of one large range,** split at tablet or region boundaries (section 6.6) | R2 | every server holding the range reads at once | — | 1 | Not started |
| O8 | **Approximate counts from the counters** (class sizes, like `stats:approxSize`) | R12 | a count in a few reads instead of a scan | — | 3 | Not started |
| O9 | **Deprecate `tbl.hashprefix`** | R3 | removes a layout that costs 33 B per row and spreads nothing | D7 | 3 | Not started |
| O10 | **HBase bulk build:** label ordinals in the files, counter timestamps per import | R4, R10 | as O3, on HBase | O3, M6 | later | Deferred (2026-11-16: internal optimizations first) |
| O11 | **Dictionary encoding**, only if M5 says go (section 6.5) | R1 | to be measured | M5, D9 | later | Not started |
| O12 | **An analytics engine and pushdown**, only if D1 (b) (section 6.6) | R2 | whole-store analytics | D1 | later | Not started |
| O13 | **`STRSTARTS` as a prefix range:** a filter `STRSTARTS(?o, "abc")` on a pattern's object narrows its scan to the objects whose data starts with `abc`, with no type check; the filter stays. Not `STRSTARTS(STR(?o), …)`: numbers, dates and booleans aren't stored as their text (section 6.7, ARCH.md 12.8) | R2 | measured (M8): `prefix` 92 → 0.7 ms on RocksDB and 72 → 2.2 ms on Accumulo, 133,579 → 522 cells. `query.prefixranges` turns it off | M8 | 1 | Built on branch `loader-skip-singleton-counters`; green on Accumulo, HBase and RocksDB (also with batch size 2 and indexes) |
| O14 | **Comparisons as value ranges:** `<`, `<=`, `>`, `>=` on a pattern's numeric or date object narrow its scan to the value range, one range per stored type; the filter stays. Needs a record of the datatypes each predicate's objects use (section 6.7) | R2 | the same, for numbers and dates | M8 | 1 | Not started |
| O15 | **Adaptive batch windows in batched joins:** the first window is 64 left rows, doubling up to `query.join.batchsize`, as entity prefetch does (section 6.8) | R2 | measured (M8, section 4.6): `chain-limit` 114 → 19 ms on Accumulo and 6.9 → 0.7 ms on RocksDB (1,003 → 67 ranges); `type-limit` 164 → 40 ms and 22.6 → 1.6 ms (2,004 → 260 ranges). No cost on large joins: `chain` −7 % with 16 batch scans instead of 13 | M8 | 1 | Built on branch `loader-skip-singleton-counters`; green on Accumulo, HBase and RocksDB (also with batch size 2 and indexes) |
| O16 | **More lookups in flight:** batched joins and entity prefetch keep the next window's fetch running while the current one is joined, and more than one when the store has several servers (section 6.8) | R2 | the tablet server is the limit on the test cluster (4.6), so overlapping with client work gains nothing. More server parallelism (more tablets, concurrent batch scans) is the open question, for a cluster (M3) | M8 | 1 | Not started |
| O17 | **Less work per looked-up row:** `BatchLookup` matches on the row's encoded object instead of encoding every result again, and allocates less per row (section 6.8) | R2 | dropped for joins: on Accumulo the query thread's CPU is 2 to 3 % of a join's wall time (4.6). It could matter for range scans, which are client-bound | M8 | 1 | Not started |
| O18 | **IRI prefix codes**, only if M9 says go (section 6.9) | R1 | measured (M9): −4 to −5 % on disk, −25 to −35 % in the block cache, −44 to −57 % of the key bytes a writer sends, no clear change in scan and decode time | M9, D11 | 2 | Deferred (2026-11-16: below the bar on disk; revisit after M3, see 6.9) |
| O19 | **RocksDB key decoding:** `RocksCodec` encodes and decodes keys by copying the runs between escapes in bulk, instead of a byte at a time through a synchronized `ByteArrayOutputStream` | — | measured with O20 on RocksDB (M8, against `rocksdb-20261116-204243`): `count-class` 71 → 25 ms, range scans −23 to −31 %, joins −13 to −19 % | — | 1 | Built on branch `loader-skip-singleton-counters`; green everywhere |
| O20 | **The object-type check as a row suffix:** `ScanSpec.rowSuffix`, set by the query engine when the type is the only row condition. RocksDB compares bytes. Accumulo and HBase filter with the equivalent linear `[\s\S]*` and the quoted suffix, instead of three open groups. It also fixes datatype IRIs being pasted into the expression unquoted, which let a `.` in a custom datatype IRI match any character | R2 | measured with O19, above. On Accumulo it means less tablet-server CPU per type-checked cell, not measured separately | — | 1 | Built on branch `loader-skip-singleton-counters`; green everywhere, `RangeParityIT` on all three backends |
| O21 | **One range per subject:** when a window looks up two or more links of the same subject in `spo`, it reads the subject's range once and lets `BatchLookup`'s exact matching keep the cells asked for. A batch whose subject ranges return more than 64 cells per subject plus 1,024 is re-read one range per link (section 6.8) | R2 | measured (M8): `star-in` 17,594 → 8,799 ranges but 25,445 → 103,265 cells; `star-optional` 7,601 → 2,537 ranges, 9,345 → 28,772 cells. Accumulo: no change beyond noise. RocksDB: +64 % and +49 %. So `query.join.subjectranges` is **off by default**. Untested hypothesis: on a cold cache a seek is a block read and the extra cells are in the same block (M3) | M8 | 1 | Built, off by default |
| O22 | **Merge joins of subject-sorted ranges:** for a star with constant objects (`?x a :Person ; :worksFor :Acme ; :nationality :IT`), each pattern is a `po` range sorted by subject. Stream them side by side and intersect, instead of one lookup per row. The subject space is sliced so several slices are intersected at once (section 6.10) | R2 | expected from 4.6: a sequentially read cell costs about 1/35 of a lookup on the Accumulo test cluster. To be measured on M10's class stars | M10 | 1 | Done: `SubjectMergeJoin`, on by default with a ratio per backend (35 Accumulo, 20 HBase, 8 RocksDB); measured in section 4.7. Slices in parallel and subject-only reads are O24 |
| O23 | **Stars evaluated in the servers:** an optional subject-partitioned table, maintained like an index. A shard holds, per predicate and object, the sorted subjects of its share of the store. A server-side iterator (Accumulo) or coprocessor (HBase) intersects a star's lists inside every shard in parallel, and returns only the matching subjects (section 6.10) | R2 | the distributed join for "all entities matching X" (D1). To be measured against O22 | O22, D13 | 2 | Not started |
| O24 | **Merge joins, part 2:** intersect a star's lists in slices of the subject space on several threads at once (cut at `spo`'s tablet splits or at sampled subjects), and read only each cell's subject instead of decoding the whole statement (section 6.10) | R2 | O22 reads one list per pattern on one thread, and decodes every cell | O22 | 1 | Not started |

### 2.2 Measurements

All on a real cluster with NVMe disks, unless the row says otherwise.

| # | Measurement | Feeds | Status |
|---|---|---|---|
| M1 | **Bytes on disk per table**, at 1B and 10B triples on Accumulo, and for a real dataset of at least 1B (D4). Replaces the README's test-load figure | section 4, cluster size, O3 | Not started |
| M2 | **Ingest on the current write path:** triples/s against the number of servers, and the compaction backlog. Also BACKENDS-STUDY.md's benchmark B0, which never ran | D2, section 4.5 | Deferred (2026-11-16: load work later) |
| M3 | **The query suite:** lookups, star and chain joins, entity prefetch, text, geo, `stats:approxSize`, inference. p50 and p99, cold and warm, with scans and cells per query from the trace. Also `query.join.subjectranges` on and off, on a cold cache (O21) | the 1T run's acceptance | Not started |
| M4 | **Single-client scan rates**, Scanner and BatchScanner | R2, O7, D1 | Not started |
| M5 | **The dictionary spike,** offline, on a 1B-triple sample (section 6.5) | D9, O11 | Not started |
| M6 | **Bulk spikes:**<br>• Accumulo RFiles with labels and counters, written from JDK 25 and imported into a live table (with a `LoadPlan`)<br>• RocksDB SST files with `merge` entries, ingested into a family that already has counters<br>• HBase HFiles with visibility tags through the expression resolver, and the same counter file imported twice in coprocessor mode<br>• Spark executors running `tripatha.api` code on JDK 25 | O3, O10, D5 | Deferred (2026-11-16: load work later) |
| M7 | **Maintenance at 10B:** `DROP GRAPH` of a small and of a large graph, graph sizes, an index rebuild | O4, O5 | Not started |
| M8 | **A local join benchmark,** no cluster needed: a fixed query suite over a generated store of about 10M triples, on RocksDB and the Accumulo test cluster. Star and chain joins, entity prefetch, OPTIONAL, `LIMIT`, value and prefix filters, text and geo. Reports p50 and p99, a profile of where the time goes, and scans and cells per query from the trace. Checked in, so each optimization reports before and after | O13 to O17 | Done: `tripatha.loader/bench.sh` (`JoinBench`); baseline in section 4.6. Follow-ups: report CPU samples apart from threads blocked in native code, and compare settings back to back in one run (`--conf` variants) |
| M9 | **IRI prefix codes, measured offline:** the generator's `spo`, `po` and `osp` rows written to RFiles as today and with prefix codes. Compares bytes sent, compressed size, and scan and decode time (section 6.9) | D11, O18 | Done 2026-11-16: results in section 6.9 |
| M10 | **Class-star queries in the join benchmark:** entities of a large class selected by two to four constant properties, with and without a value filter and with attributes fetched. For example, people of one nationality working for one company, and observations of one property above a value. They measure O14, O22 and O23 | O14, O22, O23 | Done: seven `class-*` queries; baseline in section 4.7 |

### 2.3 Milestones

| Milestone | Needs | Exit | Status |
|---|---|---|---|
| 10B bulk build on Accumulo | O3 | the build is measured, with a time model to 1T | Deferred (2026-11-16) |
| 1T on Accumulo | O1 to O9, M1 to M7 | a report against the extrapolations of M1 to M4, with every gap explained (phase 3) | Deferred (2026-11-16) |
| 1T on HBase | O10 | the same report on HBase | Deferred (2026-11-16) |

---

## 3. What "a trillion triples" is taken to mean

- **The data:** 10^12 statements, shaped like the loader's dataset by default:
  - 7.5 triples per entity, 11 classes, power-law links, labels in 11 languages, points and polygons;
  - 8 named graphs, with 20 % of the statements in the default graph;
  - both indexes on.

  At 1T that is 133.6 billion entities. Real data will differ in IRI length and in how often literals repeat, so M1 measures a real dataset too (D4).
- **The queries:** class (a) of D1. Their cost depends on how many rows they touch, not on how many the store holds.
- **The load:** one bulk build of the whole dataset, then online updates of ordinary size (D2).
- **Labels:** used as today, on some statements.

## 4. The sizing model

All the figures in this section were measured on the development machine (JDK 25, 2026-11-16) unless they say otherwise.

### 4.1 What one triple sends

`tripatha.loader/run.sh dry-run --triples 1000B --text-index --geo-index` (`--triples 1T` is refused: O2). Bytes are Accumulo `Mutation` sizes, as the servers receive them. The disk figures come from the README: "after compaction, a test load took about 160 bytes per triple". The size and backend of that load aren't recorded, so M1 measures them again.

| Table | Bytes sent | Cells sent | Bytes on disk, one copy (README) |
|---|---|---|---|
| `spo` | 148.6 | 1.00 | 21 |
| `po` | 148.6 | 1.00 | 21 |
| `osp` | 148.6 | 1.00 | 21 |
| `eval` | 348.4 | 6.58 | 50 |
| `text` | 261.4 | 1.97 | 42 |
| `geo` | 9.7 | 0.07 | 1 |
| **total** | **1,065.1** | **11.62** | **about 160** |

Without the indexes: 794 bytes and 9.6 cells sent per triple.

**What follows at 1T:**
- **Sent:** 1.07 PB in total, of which 446 TB for the three statement tables.
- **Stored:** about 160 TB for one copy, 480 TB with three replicas, before compaction headroom.

**Load speed on the development machine:**
- the generator: 1.42M triples/s on one thread;
- generating and serializing as a load does: 633k triples/s on 8 threads with both indexes, 1.15M without.

One 940 Mbit/s link carries at most about 110k triples/s of this data.

Entity IRIs here are short (`urn:tripatha-gen:e/a759ea27d4727622`, 35 bytes). Subjects average 35 bytes, predicates 33, and objects 37 bytes of data.

### 4.2 The statistics counters

**Four different numbers:**

| Stage | Cells per triple | Source |
|---|---|---|
| Increments a statement makes: six counters, plus six more in a named graph (80 % here) | 10.8 | counted |
| Cells the loader sends, summed per block of 1,000 entities | 6.48 to 6.58 | block aggregation, `dry-run` |
| Cells stored once every counter is fully summed | 5.36 at 3M triples (5.52 at 0.75M) | full aggregation |
| Cells stored if totals of 1 are not written, except `p` | *est.* about 0.5 to 0.6 | from the shares below |

**By counter family,** on a complete generated dataset of 400k entities (3.0M triples):

| Family | Cells per triple (all graphs + per graph) | Share whose total is 1 | Key bytes per triple |
|---|---|---|---|
| `s` | 0.152 + 0.122 | 12 % | 12.5 |
| `p` | ≈ 0 (45 predicates) | — | ≈ 0 |
| `o` | 0.363 + 0.410 | 84 to 87 % | 52.6 |
| `sp` | 0.925 + 0.740 | 94 % | 134.0 |
| `po` | 0.406 + 0.443 | 85 to 90 % | 85.1 |
| `so` | 0.999 + 0.800 | 99.9 % | 151.8 |
| **total** | **5.36** | **89.5 %** | **436** |

**How far these numbers carry to 1T:**
- **Structural:** `s`, `sp` and `so` are per subject, and a subject has a few statements, so their shares hold at any size. They are 70 % of the stored cells and 68 % of the key bytes.
- **Range only:** `o` and `po` fell from 1.78 to 1.62 cells per triple between 0.75M and 3M triples, as literals and link targets repeat. At 1T they will be lower again, so for those two families the table is an upper bound.

**Per-subject counters are final within a block.** The loader's blocks were summed as `load` sums them, over 200k entities (1.5M triples):
- **No splits:** none of the 5.6M `s`, `sp` and `so` keys appeared in more than one block. The generator emits all of a subject's statements inside its own entity.
- **What can be skipped:** dropping those keys' block totals of 1 removes 3.39 cells per triple (`so` 1.80, `sp` 1.56, `s` 0.03). That leaves 3.09 of the 6.48 counter cells sent per triple.

That is O1.

### 4.3 Distinct terms

The same datasets have:
- 0.49 (at 0.75M triples) and 0.45 (at 3M) distinct terms per triple, falling slowly;
- 0.15 distinct subjects per triple;
- 45 predicates;
- an average term of 85 to 88 bytes, so about 40 bytes of distinct term text per triple.

At 1T, a dictionary would hold up to about 450 billion terms, most of them literals that occur once.

### 4.4 The cluster this implies

| Quantity | Estimate | Basis |
|---|---|---|
| Stored data, one copy | 160 TB; *est.* about 127 TB with O1, about 115 TB with O3 | 4.1, 4.2 |
| With 3 replicas | 480 TB; *est.* about 380 TB, about 345 TB | HDFS default |
| Raw disk to provision | about 700 TB to 1 PB | room for major compactions, and keeping HDFS below about 70 % full |
| Tablets at a 10 GB target | about 2,100 each for `spo`, `po`, `osp`; 4,200 for `text`; 5,000 for `eval` (500 with O3) | per copy |
| Cache | a few percent of the data (for example 50 servers × 128 GB = 6.4 TB against 160 TB) | so random lookups mostly read from disk |

**Splitting and node count:**
- **Accumulo's default split threshold** (`table.split.threshold`, 1 GB) would give about 160,000 tablets, so the 1T tables need a larger one.
- **`prepare`** today plans 4 tablets per server unless given `--splits`. With 100 servers, that means tablets of about 50 GB at 1T, which then split online during the load. O2 plans by target size instead.
- **HBase's default region size** (10 GB) is about right.
- **Node count** follows from disk per node: for example 48 nodes × 15 TB or 24 × 30 TB.

### 4.5 Ingest time

**Through the write path, as today:**
- 1T at 1M triples/s takes 11.6 days. 1M triples/s means:
  - 11.6M cells/s into the servers (8.2M with O1);
  - at least ten loader machines at 1 Gbit/s;
  - every cell written to the write-ahead log, then flushed, then rewritten by major compactions, each time on 3 replicas.
- **Measured:** nothing at that rate (M2). The README's guidance is 120k triples/s per 1 Gbit/s link from the loader side.

**Through a bulk build** (O3):
- no write-ahead log, no memory tables, no compactions, and an import that is a metadata operation;
- the cost moves into one sort of about 11.6T cells, about 1 PB raw and a few hundred TB compressed;
- for scale, Spark's 2014 sort record was 100 TB in 23 minutes on 206 machines (quoted from memory, not checked here).

So a bulk build is a batch job of hours to a day or two on a cluster of this size, not a two-week stream. It is also restartable per partition.

### 4.6 The query baseline (M8)

Measured with `tripatha.loader/bench.sh` on 2026-11-16, before any of the query items: 10M triples, both indexes, warm, 3 warm-up and 10 timed runs per query. Both runs were on the development machine. The Accumulo run used the in-process test cluster: one tablet server, in a process of its own on the same machine. The reports are `tripatha.loader/target/bench/rocksdb-20261116-160139.md` and `accumulo-20261116-161815.md`.

| Query | Rows | RocksDB p50 | Accumulo p50 | Ranges | Cells | Waiting on storage, Accumulo |
|---|---|---|---|---|---|---|
| `lookup` | 8 | 3.3 ms | 2.7 ms | 2 | 9 | 31 % |
| `star-in` | 7,281 | 119 ms | 1,274 ms | 17,594 | 25,445 | 96 % |
| `star-optional` | 3,005 | 37 ms | 377 ms | 7,601 | 9,345 | 97 % |
| `chain` | 2,813 | 30 ms | 365 ms | 4,822 | 6,940 | 97 % |
| `chain-limit` | 10 | 6.9 ms | 114 ms | 1,003 | 2,871 | 97 % |
| `type-limit` | 100 | 23 ms | 164 ms | 2,004 | 13,126 | 94 % |
| `range-number` | 387 | 25 ms | 19 ms | 2 | 26,917 | 64 % |
| `range-date` | 5 | 262 ms | 150 ms | 3 | 240,443 | 18 % |
| `range-double` (was `range-decimal`) | 922 | 201 ms | 125 ms | 2 | 240,442 | 52 % |
| `prefix` | 521 | 96 ms | 75 ms | 2 | 133,579 | 68 % |
| `text` | 78 | 1.1 ms | 11 ms | 80 | 186 | 86 % |
| `geo` | 13,432 | 183 ms | 1,588 ms | 14,814 | 62,192 | 96 % |
| `count-class` | 1 | 74 ms | 96 ms | 2 | 53,432 | 90 % |

**What it shows:**
- **On Accumulo, joins are limited by the tablet server.** This was measured with the CPU split (`accumulo-20261116-204048`).
  - **The client's share:** the query thread's CPU is 2 to 3 % of a join's wall time, and the whole JVM's 6 %. `star-in` takes 1,038 ms of wall time, 25 ms of query-thread CPU and 59 ms of JVM CPU. The rest is the tablet server, which runs in its own process.
  - **The two costs, from the same run:**
    - a lookup (one range in a batch scan) costs about 59 µs: `star-in`, 17,594 ranges;
    - a cell read sequentially within one range costs about 1.7 µs, all included: `count-class`, 53,432 cells in 90 ms.
    - On RocksDB the same two figures are about 4.7 µs and 1.3 µs.
  - **What follows:**
    - per-cell client work doesn't limit joins on Accumulo (O17 dropped for joins);
    - reading a subject's whole range for two links costs about as much as it saves (O21);
    - streaming sorted lists and intersecting them is about 35 times cheaper per row than one lookup per row (O22).
  - **Range scans are different.** `range-date` spends 120 of its 130 ms on the query thread's CPU, parsing and decoding 240,443 cells. That is client-bound, and fewer cells (O14) is what helps.
  - **Run-to-run noise:** variants measured in the same run agree within about 5 %. Separate runs differed by up to 40 %.
- **`LIMIT` queries fetch a whole batch.** `chain-limit` asks for 1,003 ranges to return 10 rows, and `type-limit` 2,004 for 100. This is O15.
- **Filters read whole ranges.**
  - `range-date` reads 240,443 cells for 5 rows, `prefix` 133,579 for 521, and `range-number` 26,917 for 387 (O13, O14).
  - On top of that, RDF4J's parsing of every date for the `FILTER` is 27 to 32 % of `range-date`'s CPU.
  - `range-double` can be narrowed too: `o:hasResult` is an `xsd:double`, whose encoding sorts like the values. It was named `range-decimal`, and described here as not narrowable, by mistake: the generator's `decimal2` writes `xsd:double`.
- **A star asks for each link separately.** `star-in` makes 2.4 ranges per driver row, one per link, though a subject's links are adjacent rows of `spo`. One range per subject would halve the seeks (O21).
- **On RocksDB, decoding dominates.**
  - `RocksCodec.cell` rebuilds every key a byte at a time through `ByteArrayOutputStream.write`, a synchronized method: 40 to 63 % of the CPU in `prefix`, `range-date`, `count-class` and `type-limit` (O19).
  - `RocksScan.accepts` matches the object-type check as a regular expression with three `.*` groups, against a new `String` per cell: 44 % of `count-class` (O20). Accumulo runs the same expression in the tablet server, where this profile can't see it.
- **Loading on the test cluster** ran at 13k triples/s (13 minutes for 10M), against about 80k/s on RocksDB.

**Noise.** The first RocksDB baseline ran straight after the load and its compaction. A later run on the settled store was 20 to 30 % faster on queries whose work hadn't changed: the same ranges and cells, and the single `lookup` 3.3 → 0.7 ms. Two consequences:
- **Reference:** RocksDB comparisons use that later run (`rocksdb-20261116-172927`).
- **Evidence:** each change is judged first on ranges and cells, which are exact, and on latency only where the change is well beyond that noise.

On Accumulo the unchanged queries moved by −4 to −16 % between runs.

| After O15 | RocksDB p50 | Accumulo p50 | Ranges | Cells |
|---|---|---|---|---|
| `chain-limit` | 0.7 ms (was 6.9) | 19 ms (was 114) | 67 (was 1,003) | 209 (was 2,871) |
| `type-limit` | 1.6 ms (was 22.6) | 40 ms (was 164) | 260 (was 2,004) | 561 (was 13,126) |
| `chain` | 24 ms | 339 ms (was 365) | 4,822 | 6,940 |

### 4.7 Class stars, before merge joins (M10)

Seven queries for "all entities matching X" (D1), added to the benchmark on 2026-11-16. Each is a star of constant patterns, sometimes with a value condition and attributes. Measured on the same machine as 4.6, after O19 and O20: `rocksdb-20261116-235129` and `accumulo-20261117-000803`. The list lengths are the patterns' `po` counts, counted exactly from the generator (they match the rows the queries return).

| Query | Lists (subjects) | Rows | RocksDB p50 | Accumulo p50 | Ranges | Cells |
|---|---|---|---|---|---|---|
| `class-2` | employer 2,532; nationality 47,218 | 312 | 7.6 ms | 260 ms | 2,535 | 2,846 |
| `class-3` | the same, and Person 347,303 | 312 | 12.8 ms | 270 ms | 5,068 | 5,379 |
| `class-attrs` | `class-3`, then a label and an optional birth date | 361 | 22.8 ms | 284 ms | 10,134 | 10,690 |
| `class-large` | category 20,640; in stock 93,492; Product 133,578 | 14,379 | 139 ms | 1,438 ms | 41,284 | 55,662 |
| `class-count` | `class-large`, counted | 1 | 139 ms | 1,435 ms | 41,284 | 55,662 |
| `class-value` | property 56,173; Observation 240,441; then `hasResult > 60` (922 values) | 218 | 378 ms | 3,948 ms | 112,350 | 168,522 |
| `class-value-int` | industry 9,693; Company 53,431; then `numberOfEmployees >= 100` (2,645 values) | 201 | 71 ms | 1,775 ms | 19,390 | 27,235 |

**What it shows:**
- **Every star is driven by its shortest list, then looked up per row.** The other patterns are one lookup each per driver row, so `class-large` asks for 41,284 ranges to return 14,379 rows, and `class-value` 112,350 for 218.
- **On Accumulo the lookups are the cost.** They take 96 to 99 % of the time in storage calls, and the query thread's CPU is 2 to 5 % of the wall time. In large batches a range costs about 35 µs (`class-large`, `class-value`). Small batches cost more per range: `class-2` spends 260 ms on 2,535 ranges, and `class-3`, whose two lookups per row go out in the same batches, only 10 ms more for twice the ranges.
- **On RocksDB a lookup is cheap**, a few microseconds, and close to the cost of a cell read in sequence.
- **What O22 should do, by the cost model** (a cell read in sequence about 1 µs on Accumulo, 1.3 µs on RocksDB):
  - `class-large`: merging all three lists reads 247,710 cells in sequence, about 0.3 to 0.4 s against 1.44 s on Accumulo. On RocksDB it would be slower than today's 139 ms;
  - `class-2` and `class-3`: merging employer with nationality reads 49,750 cells, about 50 to 85 ms against 260 ms on Accumulo, with Person left as a lookup per match. On RocksDB it loses;
  - `class-value` and `class-value-int`: merging the type into the constant pattern saves one of the two lookups per row. The value condition stays one lookup per row, or becomes the driver with O14, which is what these two need most.

**After O22** (merge joins), as variants of one run per backend: `query.join.mergeratio` 0 (off), the backend's default (35 on Accumulo, 8 on RocksDB), and 10^6 (merge everything). Reports `accumulo-20261117-003258`, `rocksdb-20261117-004032` (off and default) and `rocksdb-20261117-001428` (0, 5, 35 and everything).

| Query | Accumulo, off | Accumulo, 35 | merge all | RocksDB, off | RocksDB, 8 | merge all |
|---|---|---|---|---|---|---|
| `class-2` | 267 ms, 2,535 ranges | **47 ms**, 4 ranges, 49,738 cells | 46 ms | 9.4 ms | 8.4 ms (not merged) | 18.3 ms |
| `class-3` | 272 ms | **83 ms**: Person looked up for 312 rows | 326 ms (+20 %): Person read whole | 12.6 ms | 12.6 ms (not merged) | 157 ms |
| `class-attrs` | 286 ms | **84 ms** | 362 ms | 23.9 ms | 22.2 ms (not merged) | 161 ms |
| `class-large` | 1,446 ms, 41,284 ranges | **137 ms**, 6 ranges, 247,677 cells | 137 ms | 141 ms | **101 ms** | 99 ms |
| `class-count` | 1,439 ms | **137 ms** | 138 ms | 140 ms | **101 ms** | 100 ms |
| `class-value` | 3,938 ms, 112,350 ranges | **2,141 ms**, 56,178 ranges | 2,141 ms | 382 ms | 358 ms | 368 ms |
| `class-value-int` | 1,774 ms | **931 ms** | 931 ms | 71 ms | 67 ms | 70 ms |

On the HBase test cluster (`hbase-20261117-014817`), one run with ratios 0, 8, 35 and 10^6. Its default, 20, merges what 35 merges here:

| Query | HBase, off | HBase, 35 | merge all |
|---|---|---|---|
| `class-2` | 57.6 ms | 52.2 ms | 52.8 ms |
| `class-3` | 63.0 ms | 52.0 ms | 347 ms |
| `class-attrs` | 71.7 ms | 50.5 ms | 355 ms |
| `class-large` | 506 ms | 149 ms | 147 ms |
| `class-count` | 557 ms | 161 ms | 161 ms |
| `class-value` | 1,845 ms | 1,031 ms | 1,052 ms |
| `class-value-int` | 440 ms | 252 ms | 250 ms |

- **Accumulo:** merging is 3 to 10 times faster wherever the lists are within about 35 times each other: −83 % on `class-2`, −91 % on `class-large`. Merging a list 137 times the shortest (the whole Person class) is slower than looking it up: the ratio must stay well below that.
- **RocksDB:** a sequential cell costs about 0.4 µs inside a merge, and a lookup about 3 µs, so the break-even is near 8. Below it (`class-large`, three lists within 6.5× of each other) the merge is 25 to 30 % faster; above it (`class-2`, 18.6×) it is 2.4 times slower.
- **HBase** (`hbase-20261117-014817`, the in-process test cluster): a lookup costs 12 to 21 µs and a cell read in a merge 0.6 to 0.9 µs, so the break-even is near 20. `class-2` (18.6×) gains 9 % merged, `class-attrs` 30 %, `class-large` 71 % (506 → 149 ms) and the value stars 43 %; merging the whole Person class (137×) is 5.5 times slower.
- **Hence a ratio per backend**, from `Capabilities.lookupCost()`: 35 for Accumulo, 20 for HBase, 8 for RocksDB.
- **Value conditions:** `class-value` and `class-value-int` halve on Accumulo, since the type list is merged. The other half is the value lookup per match, which is O14's work.
- **Row counts** were the same in every variant (the benchmark compares counts only; `SubjectMergeIT` compares the rows themselves).
- **The other 13 queries don't change.** Run with merges off and with every star merged (`rocksdb-20261117-013844`), they read exactly the same scans, ranges and cells: none of them has two constant patterns on one subject. Plans don't depend on the backend, so that holds on Accumulo and HBase too.
- The RocksDB columns come from two runs: "off" and "8" from one, "merge all" from the other. Compare within a run only.

---

## 5. Requirements at 1T, and where Tripatha stands

| # | Requirement | Status | Closed by |
|---|---|---|---|
| R1 | Terms stored compactly (a dictionary of fixed-size IDs, or an equivalent) | Missing, and not needed first | M9, O18 (prefix codes); M5, O11 (dictionary) |
| R2 | Joins and scans that don't funnel through one process, and read no more than they need | Partial | O13 to O17, O7, O12 |
| R3 | No hot keys | Present (pre-splitting); the hashed layout doesn't help | O9 |
| R4 | Bulk ingest | Partial | O3, O10 |
| R5 | Statistics for join ordering, at a size that scales | Present, oversized | O1, O3 |
| R6 | Maintenance in time proportional to what it changes | Missing | O4, O5 |
| R7 | Search planning that tells common terms from rare ones | Partial | O6 |
| R8 | Tablets and regions sized for 1T | Partial | O2 |
| R9 | A loader that runs at 1T | Partial | O2 |
| R10 | Labels in HBase bulk files | Missing | O10 |
| R11 | Bulk writes checked like online writes | Missing | O3 |
| R12 | Counts without reading the data | Partial (graph sizes only) | O8 |

### 5.1 R1: terms stored compactly

**Today:**
- Rows are UTF-8 terms joined by `00`, three times (ARCH.md 7.2).
- The three statement tables send 446 B per triple, about 0.5 PB at 1T.

**But on disk they compress about 7×,** to 21 B per triple each. RFile's relative-key encoding and block compression exploit sorted neighbours that share long prefixes.

**What IDs and a dictionary would cost on disk.** This is an estimate; M5 measures it:
- **IDs in rows:** three 8-byte IDs per row. Within a row's neighbourhood, two of the three are effectively random, so expect 12 to 16 B per row compressed, 36 to 48 B for three tables.
- **The dictionary:** term → ID and ID → term, for about 0.45 terms per triple of about 88 bytes each, compressed about 3 to 4×. That is about 25 B per triple.
- **Total:** 60 to 75 B against 63 B today. About even, because most terms are literals that occur once, and a literal stored once in each direction of a dictionary weighs about as much as storing it compressed in three rows.

**What IDs would gain:** fewer bytes sent (the three tables would send about 150 B per triple instead of 446), more statements per cached block, and cheaper key comparisons.

**What they would cost:**
- **Reads:** every result needs a decode for each distinct term it returns: one more batched lookup per join batch, against a 450-billion-entry table that is mostly cold.
- **Writes:** every new term needs an ID. That means a read-before-write, or a coordinated allocator, on a path that is blind writes today.
- **Labels:** the dictionary would reveal terms. A literal used only in statements labelled `A` must not be readable by a reader without `A`, so dictionary cells need labels too (section 6.5).
- **Order:** values are stored in value order today (mango's lexicoders), which range filters, `ORDER BY` on one predicate's values, and `TripleTermRangeSource`'s prefix ranges over triple terms rely on. Order-preserving IDs can't be assigned incrementally. So numbers, dates and triple terms would have to stay inline, which is the hybrid most stores end up with.
- **The rest of the format:** invariant 2 (encodings never change), `EncodingCompatibilityTest`, the counters (keyed on lexical values), and the index keys (`IndexKeys` puts the predicate and subject in the row).
- **Choosing IDs:**
  - **Sequential IDs:** new subjects get increasing IDs, so writes pile up at the end of `spo`: a hot spot on one tablet.
  - **Hash IDs:** these avoid coordination. But 64 bits give thousands of collisions among 450 billion terms, and 128-bit hashes (16 bytes, incompressible) make rows bigger on disk than today's compressed IRIs.

**Conclusion:** a measured decision (D9, M5), with nothing waiting for it.

### 5.2 R2: joins that don't funnel through one process

**Two parts of the query path already cut the round trips:**
- **`BatchedJoinStep`:** batches of `query.join.batchsize` lookups (default 1000).
- **`EntityPrefetchStep`** (ARCH.md 13.5): the lookups of a chain of joins and OPTIONALs over a window of driver rows, in one prefetch.

**What limits a query** is the number of rows it moves to the endpoint's JVM, not the store's size:
- A query whose intermediate results stay small runs as fast at 1T as at 1B, apart from cache misses.
- A query that streams, say, 1 % of the store (10 billion rows) takes hours at any plausible rate for one client.

M4 measures that rate: cells per second for one Scanner, and for one BatchScanner reader.

**Mitigations that exist:**
- `tripatha.sparql.query-timeout` and the per-request `timeout=`;
- `query.limit`;
- `stats:approxSize` for graph sizes.

**To add:**
- **Approximate counts** for more shapes (O8);
- **parallel reads of one large range** (O7);
- **a separate engine** for real analytics (O12, D1 (b)).

**Pushdown can do less than it sounds.** In a range-partitioned store, a join across subjects can't run inside one server. What can move to the servers:
- filters on object values;
- star filters on one subject's `spo` range;
- partial aggregates.

These cut bytes for filtered scans. They don't remove the funnel, and RocksDB has no server to push to. They belong with O12.

### 5.3 R3: no hot keys

**What `tbl.hashprefix` actually does** (`WholeRowHashedTripleResolver`):
- it prefixes `spo` rows with the hex MD5 of the subject and `po` rows with the hex MD5 of the predicate, 32 characters plus `00`;
- `osp` isn't changed.

**So it doesn't spread a hot predicate:**
- every row of `rdf:type` gets the same 33-byte prefix and stays one contiguous range;
- what it spreads is different predicates and different subjects, which sorted IRIs and quantile splits already spread.

**It costs:**
- 33 bytes more per `spo` and `po` row;
- graph sizes: `GraphSizes` throws `UnsupportedOperationException` on that layout.

Apart from the configuration class that defines it, `RyaTripleContext` and `GraphSizes` are the only code that tests the flag. Hence O9.

**Where the hot spots really are:**
- **Large ranges** such as `rdf:type` (about 13 % of `po` in this dataset) are split into many tablets by `prepare`'s quantile split points, so their storage and their writes spread. Reading one through a single Scanner is still sequential, which is R2 (O7).
- **The `p` counter rows** receive a cell from every block: about 45 predicates × 9 graph columns.
  - At 1T with 1,000-entity blocks, the hottest row (`rdf:type`) receives about 1.2 billion small cells over the whole load.
  - At 1M triples/s that is about 1,200 cells per second, to a row whose combiner sums them without locks. That isn't a hot spot. A bulk build sums them before writing.
- **Input in key order on the online path** is the real risk, for example IRIs numbered `…/item/000001` upwards, loaded in that order. Every write then goes to the last tablet of `spo`. The bulk path is immune. For online loads, the fix is to shuffle the input order, or to salt only such ranges. Sequential dictionary IDs would cause exactly this hot spot (5.1).

### 5.4 R4: bulk ingest

**Today:**
- the loader plans split points at quantiles, and runs parallel parts with resume;
- every write still goes through the DAO and the batch writers.

**The numbers:** 1T at 1M triples/s takes 11.6 days, and 1M triples/s on the online path is unmeasured (section 4.5, M2).

**The bulk build (O3) doesn't need dictionary encoding.** Writing today's cells through the same serializers:
- reuses all of today's code;
- is verifiable cell by cell against the DAO path, as `LoaderIT` already compares `spo` and `eval` against regenerated data;
- leaves the dictionary decision free.

Design in section 6.2.

### 5.5 R5: statistics for join ordering

The `eval` counters work: summed on the server (Accumulo's combiner, the HBase coprocessor, RocksDB's merge operator), and read by RDF4J's join optimizer. But at 1T it is the biggest table, and mostly redundant:
- 50 of 160 B per triple on disk and 348 of 1,065 B sent (section 4.1);
- 5.4 stored cells per triple, 89.5 % of them holding the total 1 (section 4.2).

**Why those cells carry nothing.** `RdfCloudTripleStoreEvaluationStatistics` estimates a pattern whose counter is missing at 1, in the subject, predicate and object branches alike. So a counter whose total is 1 gives the same estimate as no counter at all.

There is one exception, used only with inference: a `DoNotExpandSP` after a `FixedStatementPattern` adds up the raw counters of the ontology terms. There a missing counter counts as −1, as it already does today (section 6.3).

**What follows:**
- **`so` and `sp`:** 99.9 % and 94 % ones, so almost entirely such cells.
- **In the loader**, a block holds the final totals of the per-subject families, so it can skip them (O1).
- **In a bulk build**, every family's totals are known after the merge (O3).
- **The online path** of the endpoint, one statement per call, can't skip any: it doesn't know a total when it writes.

### 5.6 R6 to R12

| # | Gap | Evidence | Effect at 1T | Fix |
|---|---|---|---|---|
| R6 | **Maintenance reads whole tables** | `RyaStoreDAO.dropGraph` calls `deleteVisible(table, KeyRange.all(), …)` with the graph as a column filter, on `spo`, `po`, `osp` and the index tables. `purge` does the same on every table. `size()` and index rebuilds scan everything | dropping any graph, even a small one, reads about 5T cells. At an assumed 50M cells/s across the cluster, that is about a day | O4 (D8), O5 |
| R7 | **Text search can't rank common words** | `IndexSearcher.PROBE_LIMIT = 1000`: an operand's size is estimated by reading up to 1,000 entries | every word with more than 1,000 postings looks the same, so an AND can be driven by a word with billions of postings instead of the rare one | O6: per-term posting counts, maintained like the other counters, and read by the planner |
| R8 | **Tablet sizing** | `Prepare` plans `4 * servers - 1` splits per table unless `--splits` is given; Accumulo splits at 1 GB by default | tablets of about 50 GB at plan time, then about 160,000 tablets after online splits | O2: plan by target tablet size, and give the 1T tables a larger split threshold. The 50,000-entity sample still gives about 178 rows per split point at about 2,100 splits |
| R9 | **Loader limits** | `LoaderOptions.size` knows K, M and B only. `Progress` keeps one bit per block in a `BitSet` and rewrites the whole file at every commit | `--triples 1T` is refused. One part covering all 133.6 billion entities has 133.6 million blocks, so a 22 MB file is rewritten at every commit interval. That works, but is wasteful | O2 adds `T`; with `--parts`, the files shrink anyway |
| R10 | **HBase bulk files and labels** | an HBase visibility tag stores label ordinals from `hbase:labels`, not names | a bulk writer must map labels to ordinals, as HBase's `ImportTsv` does with its visibility expression resolver. Bulk counters also need unique timestamps (section 6.2) | O10 |
| R11 | **Bulk import skips server-side checks** | Accumulo doesn't apply table constraints to imported files; the same holds for HBase and RocksDB ingestion | the key-size limit and the DAO's guards (no `00` in graphs, label syntax) aren't checked by the server | O3: the bulk writer runs the same guards as the DAO |
| R12 | **Exact counts are out of reach** | `COUNT(*)`, `size()` | an exact count of a large class reads the class | O8: approximate counts from the counters |

### 5.7 Notes

- **Statistics drift by one** wherever a counter of 1 was left out and later the online path adds that statement again, or a later load adds to the same key (section 6.3). That is within "approximate by design" (ARCH.md 11.3).
- **`verify` and `LoaderIT`** compare counters to recomputed values, so they must treat a missing counter as 1 once O1 is in.
- **Compaction headroom:** a full major compaction of a 20 TB table needs free space for its new files before the old ones go. Compact table by table, and keep HDFS below about 70 % full.

### 5.8 What doesn't change at 1T

These were checked so they don't need work:
- **Lookups:** one seek. The only difference at 1T is a lower cache hit rate (D10).
- **Batched joins and entity prefetch:** their batches are bounded by `query.join.batchsize` and `query.join.prefetchlimit`, not by the store.
- **Cardinality estimates:** one exact `eval` read per pattern.
- **Graph sizes:** one seek per predicate in `po`, then one exact row per predicate.
- **Named graphs:** `getContextIDs()` checks each graph with one lookup.
- **The inference refresh** reads only ontology-sized ranges:
  - the `po` ranges of `rdfs:subPropertyOf`, `owl:inverseOf`, `rdfs:domain`, `owl:onProperty` and the like;
  - the instances of `owl:SymmetricProperty`, `owl:TransitiveProperty` and the reflexive-property class (`fetchInstances`, whose javadoc limits it to such types).

  Data that used those predicates massively at instance level would change that.
- **Geo search:** it reads its cells with one batch scanner and resolves candidates against `spo` 1,000 at a time.
- **Hot counter rows:** summed without locks by the combiner, or by the coprocessor on HBase (section 5.3).

---

## 6. Designs

### 6.1 Per-subject counters of 1, skipped by the loader (O1)

**Why it is exact.** The generator emits every statement of a subject inside that subject's entity, and an entity never spans two blocks. Measured over 1.5M triples, none of the 5.6M per-subject counter keys appeared in two blocks (section 4.2). So a block's sums for `s`, `sp` and `so` are the final totals.

**The rule:** the worker sums its block's counters, drops `s`, `sp` and `so` totals of 1, and writes the rest. `o`, `po` and `p` keys span blocks, so they are written as today.

**The block must be summed whole.**
- **Why:** with the default policy, `RyaStoreDAO.add` writes its pending counters early once it holds 50,000 keys (`MAX_PENDING_COUNTERS`). A 1,000-entity block averages about 48,500 counter keys, so an early write would split some blocks, and with them subjects. Two halves of one subject's counter would then each look like a 1.
- **How:** `RyaStoreDAO.add(statements, CounterPolicy.SKIP_SUBJECT_ONES)` sums the counters of the whole call before writing any, then leaves out the `s`, `sp` and `so` cells whose sum is 1, label by label (`CounterBatch.drain(policy)`). The loader passes one block per call, as before.
- **Resume:** a block is still recorded only after its flush, so `--resume` behaves as today: blocks in flight during a failure are written again.

**Who may use it:** only callers that pass whole subjects per call, which the generator guarantees. Real data gets the same effect, for every family, from the bulk build (O3).

**The loader option:** `--stats-ones skip|keep`, a dataset option.
- `skip` is the default.
- `prepare` prints `keep` when it is chosen.
- The option is part of the progress file's fingerprint.

**What else follows the policy:**
- `prepare` writes the reference data in one call, with the same policy. Reference subjects appear in no entity.
- `SplitPlanner` counts as the load writes, so `eval`'s split points follow its rows.
- `dry-run` measures what the load sends.
- `Verify` expects no cell where a label's count is 1, and −1 when a subject has no cell left.
- `LoaderIT` compares `eval` with the policy applied.
- The `DoNotExpandSP` branch of `RdfCloudTripleStoreEvaluationStatistics` reads a missing counter as 1, like the other branches (section 6.3). That is a fix in its own right.

**Tests:**
- `EntityGeneratorTest`: no subject appears in two entities, or in an entity and the reference data, with labels and with RDF 1.2 reifiers. A generator change that breaks the property fails the build.
- `EvalStatsMutationsTest`: the policy leaves out exactly the subject cells of 1, label by label, and keeps every other counter.
- `StatsAndGraphsIT`: a subject whose statements straddle the 50,000-key point keeps its total; other counters of 1 are written; a reader with two labels sees only the label whose count is above 1.
- `RdfCloudTripleStoreEvaluationStatisticsTest`: a missing counter estimates 1, alone and in the inference sum.
- `LoaderOptionsTest`, and `LoaderIT`'s cell-by-cell `eval` check after parallel parts, a stop and resume, and a write failure.

**Gain:** measured with `dry-run` at 1T with both indexes:
- 8.2 cells sent per triple instead of 11.6;
- 871 bytes instead of 1,065;
- `eval` alone: 154 B instead of 348.

One 940 Mbit/s link carries about 135k triples/s instead of 110k. *est.* −20 % on disk: 160 → about 127 B per triple, about 100 TB less at 1T with 3 replicas (M1 measures it).

### 6.2 The bulk build (O3, O10)

**Shape** (D5 (a)):
1. **`prepare`**, as today. It creates the tables, plans split points by target size (O2) and writes the reference data, then records the plan.
2. **Partition.** Each part generates (or parses) its entities and serializes them with the DAO's own code:
   - `RyaTableMutationsFactory` for `spo`, `po` and `osp`;
   - `IndexManager`'s puts for the indexes;
   - `EvalStatsMutations.counterKeys` for the counters;
   - graph markers once per graph and label.

   Each cell goes to the tablet its row falls in, by the split points. The part writes sorted runs per tablet, with counters summed in memory first.
3. **Merge**, one task per tablet:
   - **Statement cells:** merge the tablet's runs into one sorted file, removing exact duplicates.
   - **Counters:** add the deltas up per key, then drop totals of 1 except in family `p` (D6, section 6.3).
4. **Import:**
   - **Accumulo:** files written with the public `RFile` writer, `importDirectory(dir).to(table).load()`.
   - **RocksDB:** `SstFileWriter`, then `ingestExternalFile`.
   - **HBase:** HFiles, then `BulkLoadHFiles`.

Parts, progress and resume work as in the loader today, per step.

**Properties:**
- **The same cells.** The serializers are the DAO's, so `spo`, `po`, `osp`, the indexes and the markers are byte for byte what `load` writes, with the dataset's single fixed timestamp. So a rerun is idempotent for everything but the counters, and a test can compare two stores cell by cell.
- **Counters into a store that already has data:**

  | Backend | What a bulk file of counters does | Hazard |
  |---|---|---|
  | Accumulo | the combiner adds imported cells to existing ones | none |
  | RocksDB | the file holds `merge` entries, added on read and compaction, like online deltas | to confirm in M6 |
  | HBase, coprocessor mode | the coprocessor sums versions | two files carrying the same counter cell with the same timestamp collapse into one cell, so each import stamps its counters with a timestamp of its own |
  | HBase, increment mode | an imported value replaces, it doesn't add | refuse bulk counters into a non-empty table |

  Importing the same counter files twice counts them twice on every backend. The progress file records imports per table.
- **Labels:**
  - **Accumulo and RocksDB:** cells carry their expression as today.
  - **HBase:** tags hold label ordinals, so the writer resolves them from `hbase:labels` (R10).
- **Guards:** the writer runs the DAO's checks itself (R11).
- **Real data:** parts read byte ranges of N-Triples or N-Quads files, aligned to line ends, and parse them with RDF4J's N-Triples and N-Quads parsers. There are no entity numbers, so resume works per input split.

**In the SPI:** a `BULK_IMPORT` capability, with:
- a sorted-file writer per table that takes `Cell`s and counter deltas in key order;
- an `importFiles(table, dir)` call.

The partitioning and merge sit above the SPI, in `tripatha.loader`, so they are written once.

**Tests:**
- a `BulkBuildIT` that loads one dataset through `load` and through `build` into two prefixes, and compares every table cell by cell (`eval` as logical sums, a missing counter read as 1);
- the existing `LoaderIT` checks on the built store;
- on Accumulo and RocksDB first, then on HBase in both counter modes (O10).

### 6.3 Leaving counters of 1 out: the rule and what it changes

**The rule:** don't write a counter whose total is 1, except in family `p`:
- in the loader, for the per-subject families only (O1);
- in a bulk build, for every family (O3).

**Why the estimates are identical, for a load into an empty store.**
- **The main branches.** For every family, the subject, predicate and object branches of `RdfCloudTripleStoreEvaluationStatistics` map a missing counter to 1, so join orders don't change.
- **The inference branch.** The `DoNotExpandSP` branch, used only with inference, sums raw `getCardinality` values over the terms of a fixed ontology pattern, and a missing counter returns −1 there. So today a missing counter already subtracts 1, and a left-out counter of 1 would read −1 instead of 1.
  - O1 makes that branch read a missing counter as 1, like the others.
  - It only reads the `s`, `p` and `o` counters of ontology terms.
- **Why `p` is always written.** Graph sizes sum `p` counters per graph. A graph whose predicates each occur once would otherwise vanish from `stats:approxSize`, which leaves out graphs whose total is 0. `p` is tiny anyway.

The other readers of the counters are `RyaStoreEvalStatsDAO.getCardinality`, `GraphSizes` and the loader's `Verify`.

**The effect of the full rule (O3):** about 5.4 → 0.5 cells per triple stored, and *est.* about 50 → 5 B per triple on disk, so about 135 TB less at 1T with 3 replicas.

**What changes:**
- **Several labels.** Counters are per label, so a key whose count is 1 under one label is left out under that label, even when other labels count more. A reader holding several labels reads it one low per such label.
- **Online writes after the load:**
  - re-adding such a statement gives 1 instead of 2;
  - deleting it leaves −1, which `getCardinality` reads as 0: right.
- **A second load, or a load into a store the online path already wrote,** decides "total of 1" from its own data only. A key with a total of 1 in the new data but already present in the store is left out, so it reads one too low: the same drift as above.
- **`verify` and `LoaderIT`** read a missing counter as 1.

**Optional, for the online path too:** make `so`, and the per-graph set, configurable. `so` is only read for patterns with a bound subject and object and an unbound predicate.

### 6.4 Graph drops and other whole-table work (O4, O5)

**Graph drops** (D8):
- **(a) A graph-first table.** `gspo` holds row = graph `00` s `00` p `00` o, maintained like an index and created only when asked for.
  - A drop reads the graph's own range and deletes exact cells, as `deleteMatches` does, so its cost is the size of the graph.
  - It costs about 21 more B per triple on disk (13 %) and one more cell per write.
- **(b) Graph tombstones.** An `ns` record says that graph G is dropped for labels L up to time T. A scan filter hides such cells, and a compaction filter removes them.
  - This is constant-time, but it is code on every read path of every backend (an Accumulo iterator, an HBase coprocessor, a RocksDB compaction filter plus `RocksScan`).
  - It also has to match `dropGraph`'s rule that a drop removes only cells visible to the caller.
- **(c) Accept a full scan per drop.**

**`purge` and index rebuilds:**
- **`purge`** keeps reading everything visible. Where the caller can see everything, dropping and recreating the tables is equivalent and constant-time.
- **Index rebuilds** become a bulk build whose input is `spo`.

### 6.5 Dictionary encoding: go/no-go (M5, O11)

A spike measures it before any product change: offline, on a 1B-triple sample, without touching the stores.

**What the spike writes:** `spo` files twice, once as today and once with IDs plus both dictionary directions.

**What it compares:**
- compressed size;
- full-scan rate;
- the cost of decoding the results of a batch of 1,000 lookups.

**Go only if both hold:**
- the encoded variant, dictionary included, is at least 30 % smaller on disk, or at least 2× faster to scan or to send;
- the label design below is accepted.

**If it goes, the design constraints are:**
- **Dictionary cells carry labels:** row = term, qualifier = label, value = ID, with one cell per label a term was written under. A reader decodes only what its labels allow. Stale cells after deletes reveal to label holders only that a term was used.
- **Order-dependent values stay inline:** numbers, dates, and triple terms, because range filters and `TripleTermRangeSource` need value order. IDs are for IRIs and plain strings.
- **IDs are assigned in the bulk build** (a join of distinct terms against the dictionary). The online path assigns new terms through a conditional write: Accumulo's `ConditionalWriter`, HBase's `checkAndMutate`, a lock in RocksDB.
- **IDs are spread, not sequential:** bit-reversed or salted, so new subjects don't all land on the last tablet.
- **A new storage version.** `EncodingCompatibilityTest` keeps pinning the old one, and stores of the old version stay readable.

**Cheaper alternatives to try first:**
- a namespace table that replaces common IRI prefixes by a short code: much smaller, and order-preserving within a namespace;
- HBase's `FAST_DIFF` block encoding, which BACKENDS-STUDY.md P4 already plans.

### 6.6 Big scans and analytics (O7, O12)

- **Parallel reads of one large range (O7).**
  - **When:** a statement pattern whose estimate is large and whose order isn't needed.
  - **How:** split its range at the tablet (or region) boundaries and read the pieces with a batch scan.
  - **Gain:** that uses every server holding the range. It doesn't remove the funnel into one JVM, but it lifts the rate to what the JVM can absorb (M4).
- **Analytics (O12, D1 (b))** means a distributed engine over the same data:
  - Spark or Trino reading the tables through the SPI;
  - or, offline, reading RFiles and HFiles directly;
  - plus pushdown capabilities for filters and partial aggregates (section 5.2), behind capability flags.

  That is a product of its own, so it comes last, and only if D1 asks for it.

### 6.7 Filters as key ranges (O13, O14)

**Today:** a `FILTER` on a pattern's object is evaluated by RDF4J over everything the pattern reads. `?s :age ?a FILTER(?a > 30)` reads every `:age` row of `po`.

**Why a range is possible:**
- `po` rows are `p 00 object 00 s 01 marker`, so within one predicate they sort by the object's data bytes.
- Within one datatype, mango's encodings sort like the values (ARCH.md 7.3).
- Strings, language-tagged strings, IRIs and custom datatypes store their lexical form as the data, so they sort as text.

**The rule:**
- **Only narrow.** As with the search indexes (ARCH.md 15.1), the filter stays in place, so the range only has to be a superset of the matches, never exact.
- **Which patterns:** a pattern with a bound predicate and an unbound object, filtered on that object.

**O13, prefixes.** `STRSTARTS(?o, "abc")` and `STRSTARTS(STR(?o), "abc")` become the range of `p 00 abc`.
- **Strings, language-tagged strings and IRIs** start with their lexical form, so every match is in that range.
- **Numbers, dates, booleans and triple terms** make `STRSTARTS` an error, which is false. Their encoded bytes may or may not fall in the range, and that doesn't matter.
- So no extra knowledge is needed.

**O14, comparisons.** `<`, `<=`, `>` and `>=` against a constant, and two of them for a between.
- **Numeric types:** SPARQL compares across them, so the scan is one range per numeric type the predicate uses, each bound encoded by that type's resolver.
- **Types whose stored data sorts like the value:** `xsd:int`, `xsd:integer`, `xsd:long`, `xsd:short`, `xsd:byte`, `xsd:double`, `xsd:float`, and `xsd:dateTime` (UTC milliseconds).
- **Unsafe:** `xsd:decimal` and other custom datatypes store their lexical text, which doesn't sort numerically. A predicate with any such value is never narrowed.
  - `xsd:date` is one of them: it has no resolver of its own. So the generator's dates (`birthDate`, `foundingDate`, `releaseDate`, `installedOn`) are not narrowed, though `YYYY-MM-DD` without a time zone sorts as text. Narrowing them would need the markers to tell plain dates from dates with a zone or a negative year.

**What O14 must know:** the datatypes a predicate's objects use, without ever missing one, since a missed type would lose answers. Neither of the obvious sources will do:
- the statistics counters, because they can be turned off (`--stats off`, `tripatha.stats=false`), and a store written partly with them off would undercount;
- the row layout, because the type is the last part of a `po` row, after the subject, so the types of a predicate aren't grouped anywhere.

**The design (2026-11-16): datatype markers.**
- **Where:** one cell per predicate, datatype and label in `eval`:
  - row: the predicate;
  - family: `t`;
  - qualifier: the datatype IRI;
  - visibility: the statement's label;
  - value: a counter delta, so it works with every backend's summing.
- **When written:** by every `add`, once per call and key, as graph markers are, and whether or not statistics are on.
- **Deletes never remove them.** A marker for a type with no values left only makes O14 narrow less.
- **Cost:** one more `eval` cell per single-statement insert, and nearly nothing for bulk adds. That is about 45 predicates × 1 or 2 datatypes per call on the loader's data.
- **Reading:** one exact row per predicate, with the query's labels, so a reader learns only the types of statements it may see.
- **Old stores:**
  - Markers are complete only for a store written by code that maintains them. So `init()` writes a flag in `ns` (row `tripatha`, family `datatypes`) only when it creates the store.
  - Stores created earlier have no flag, and O14 stays off for them until a rebuild writes their markers (with O5).
  - A writer older than O14 must not write to a store that has the flag. So the markers raise the storage version, and from then on a DAO refuses to write to a store whose version is newer than its own (D12).
    - Today the DAO writes the version only when it is missing and never checks it, so builds from before the check aren't stopped. No such writer is deployed: the project is a pre-release snapshot.
- **When a range is used:** only when every datatype the markers report is one of the order-preserving types above, and comparable with the constant (numeric with numeric, `dateTime` with `dateTime`). Otherwise the pattern reads its whole range, as today.
- **Bounds:** computed in each type's own domain, as a superset; the filter decides the edges. Examples:
  - `?x > 3.5` on `xsd:long` starts at 3;
  - a constant outside a type's range gives that type an empty range or its full range.
- **Dates:** a `dateTime` without a time zone was stored in the writing machine's zone, so date ranges are widened by 14 hours each way.
- **The setting:** `query.valueranges` turns it off. The test compares every query with it on and off.

**Where it fits:**
- An optimizer after `IndexSearchOptimizer` marks the pattern with its ranges.
- `RyaStoreQueryEngine` reads them as a batch scan, unordered, or as one ordered scan per range if order matters.
- Counters estimate the narrowed pattern as today, times a selectivity, as indexed patterns are.

**Tests:** the same answers with and without the optimizer, over every datatype, mixed-type predicates, labels, named graphs, and the batch-size and index switches (like `assertQueriesAgreeWithAndWithoutIndexes`).

### 6.8 The join path (O15, O16, O17, O21)

**What `BatchedJoinStep` does today:**
- it reads up to `query.join.batchsize` (1,000) left rows;
- it fetches all their right-hand lookups in one `StoreTripleSource.prefetch`, and waits;
- then it joins row by row against that cache.

Nothing overlaps. The first result waits for 1,000 left rows and their whole fetch, and a `LIMIT 10` still fetches 1,000 lookups.

**O15, adaptive windows.**
- **The change:** the first window is 64 rows, doubling up to the batch size, as `EntityPrefetchStep` already does.
- **What it gains:** small queries and `LIMIT`s pay for small fetches; large ones reach full batches after a few windows.

**O16, more lookups in flight.** While window n is joined, window n+1's left rows are read and their fetch runs on a background thread. With several servers, more than one window can be in flight.
- **What the baseline says:** on the test cluster the joins wait on storage 94 to 97 % of the time (4.6). So overlapping a fetch with the join's own work hides only the few percent the join spends on the client.
- **Where it pays:** fetches in flight together, spread over several tablet servers. That needs a multi-server cluster to measure (M3), so O16 comes after O15 and O21.
- **What stays the same:** the invariant that a prefetch is complete or absent. A window is registered in `StoreTripleSource` only when its fetch has finished, on the consumer thread.
- **What the background fetch must carry:**
  - the connection's configuration (labels);
  - its trace scope, which is a `ThreadLocal`, so the fetch reports to the same trace;
  - the query's timeout and cancellation.
- **Entity prefetch:** it gets the same treatment for its windows.

**O17, per-row work.** Two costs `BatchLookup.fetch` adds to every row it reads:
- **Re-encoding:** it re-encodes the object of every result (`encode(stored.getObject())`), although the row it came from already holds those bytes.
- **Allocation:** it builds a `CellId` of strings and buffers for deduplication.

Both are candidates. M8's profile decides whether they, the row decoding, or RDF4J's own work dominate. Only what it shows gets changed.

**O21, one range per subject.** A star asks for each of a subject's links as its own range: `s 00 p1 00`, `s 00 p2 00`, and so on. These are neighbouring rows of `spo`, yet each is a seek, about 70 µs on the test cluster (4.6).
- **The change:** when a window asks for two or more links of one subject on `spo`, with the same graph restriction, it reads the range `s 00` once.
- **Why it stays exact:** `BatchLookup` already matches every returned cell to the keys exactly, and drops the rest.
- **The risk is a hub subject.** A subject with a million statements would read them all to answer two lookups. Bounds:
  - the window's `query.join.prefetchlimit`, as today: an overflowing window falls back to one lookup per key;
  - a per-subject cap: a subject whose range returns more than a few hundred cells stops being read whole, and its keys are asked for one by one.
- **The gain:** the number of seeks, measured as ranges on M8. `star-in` makes 2.4 per driver row today.
- **The result (2026-11-16):** ranges halved and cells read quadrupled. Warm Accumulo was no faster and RocksDB was 50 to 64 % slower, so it ships off by default (`query.join.subjectranges`). The case for it is untested: on a cold cache a seek is a block read, and a subject's other cells come in the same block.

**Tests:** `BatchedJoinIT`, `EntityPrefetchIT` and `QueryTraceIT`, plus the whole IT suite with `-Dtripatha.test.joinBatchSize=2`.
- For O16: a cancelled and a timed-out query, which leave no fetch running.
- For O21: a hub subject, labels, named graphs, and the default graph.

### 6.9 IRI prefix codes (M9, O18)

**The idea.** A small table maps common IRI prefixes (namespaces) to short codes, and rows store the code followed by the local name. `urn:tripatha-gen:e/a759ea27d4727622` (35 bytes) would become 2 or 3 bytes of code plus 16.

**What it could gain:**
- fewer bytes sent: entity, class and predicate IRIs share long prefixes;
- more rows per cached block;
- shorter keys to compare and to decode.

**What it would cost:**
- **Storage format:** a new storage version (invariant 2), with `EncodingCompatibilityTest` pinning the old one.
- **Reserved bytes:** codes must avoid `00` and `01` (invariant 1), and the bytes triple terms escape inside (`02`, `03`).
- **Order:** it holds within a namespace, but across namespaces the codes' order replaces the text order. So O13's prefix ranges on IRIs map a namespace prefix to its code range.
- **Labels:** a namespace used only under label `A` must not be revealed to others. So code cells carry the labels they were written under, as graph markers do.
- **New namespaces:** a namespace not seen before needs an atomic assignment, with a conditional write on Accumulo and HBase. Namespaces are few and cached, so that cost is rare.

**M9's result (2026-11-16).**
- **How it was measured:** the generator's rows for 200,000 entities (1.5M triples) were written to Accumulo RFiles twice: once as today, and once with every IRI prefix used at least 100 times replaced by a 2-byte code.
- **The prefixes:** 10 were registered, among them the entity, ontology and graph namespaces, `rdf:` and `rdfs:`.
- **Two IRI styles:** the generator's short ones (`urn:tripatha-gen:e/…`), and longer, more typical ones (`http://data.example.org/tripatha/e/…`).

| Per triple, per statement table (average of `spo`, `po`, `osp`) | Full IRIs | Prefix codes | Change |
|---|---|---|---|
| Key bytes a writer sends: short IRIs | 143.6 | 80.8 | −44 % |
| the same: long IRIs | 191.1 | 81.4 | −57 % |
| Block-cache size (the uncompressed file): short IRIs | 82.3 | 61.8 | −25 % |
| the same: long IRIs | 95.1 | 62.0 | −35 % |
| On disk, gz (Accumulo's default): short IRIs | 22.5 | 21.6 | −4 % |
| the same: long IRIs | 22.8 | 21.6 | −5 % |
| Full scan and decode | | | no change beyond the ±15 % noise |

**Why the disk barely moves.** RFile stores each key's shared prefix with the previous key only once, and compresses the rest. Longer IRIs cost only 0.3 B per triple more on disk than short ones. What prefix codes would shrink is the uncompressed form: the bytes a writer sends, and the cached blocks.

**Decision (D11): not now.**
- **Disk:** at −4 to −5 %, far below the 20 % bar. The cost is a new storage version and the label and assignment work above.
- **When to revisit:**
  - a cluster (M3) shows lookups at scale bound by cache misses, where 1.3 to 1.5× more statements per cached block would show;
  - or the load's bandwidth becomes the limit, which is deferred work.
- **On HBase,** `FAST_DIFF` block encoding keeps cached blocks prefix-encoded without a format change (BACKENDS-STUDY.md P4).

**Go/no-go (M9, D11):**
- **Disk:** RFile's relative-key encoding and block compression already absorb most repeated prefixes, so the gain on disk may be small.
- **The measurement:** M9 writes the same rows both ways and compares bytes sent, compressed size, and the time to scan and decode them.
- **The bar:** go only for at least 20 % fewer bytes on disk, or at least 1.5× faster scanning and decoding.

### 6.10 "All entities matching X": merge joins and stars in the servers (O22, O23)

The target workload (D1) is entities of large classes selected by several properties and values, such as people of one nationality working for one company and born after 1990. Today such a star is a driver range followed by one lookup per driver row, for each other property. The rows flow through one JVM, and each lookup is a seek.

**What is already there.** `po` holds, for every predicate and object, the sorted list of its subjects: rows `p 00 o 00 s`. A star with constant objects is the intersection of such lists.

**O22: merge joins on the client.**
- **How:** for patterns `?x p1 o1`, `?x p2 o2`, … in one join, read each `po` range in subject order and intersect them as they stream.
  - When one list is much shorter, it drives, and the others are looked up for its subjects, as today. A leapfrog intersection, seeking each list to the next candidate, is not possible on the client: the SPI's `CellScanner` and Accumulo's client scanner can't seek inside an open scan, so a probe is a new range, which is a lookup. Leapfrogging belongs in a server-side iterator (O23).
  - Patterns with unbound objects (attributes to fetch) are then batched lookups of the matching subjects, as today.
- **Parallelism:** the subject space is cut at the tablet boundaries of `spo`, or at sampled split points, and the slices are intersected on several threads at once, each over the servers holding them.
- **Semantics:**
  - RDF4J's own join produces the same bindings.
  - Rows come out in subject order instead of the driver's order, which SPARQL doesn't fix without `ORDER BY`.
  - Labels and graphs are as for any range read; each list is read with the query's labels, as one range.
- **Where it pays:** when the driver alone is large, and a constant pattern would otherwise cost one lookup per driver row.
- **Measured on:** M10's class-star queries, against today's batched joins, as variants of one run.
- **Design details:**
  - **Bag semantics:** each pattern contributes, for a subject, as many rows as it has matching statements: several graphs, or one statement under several labels. The intersection carries a count per subject and pattern, and emits their product.
  - **Merge or look up:** the `po` counters give each list's length.
    - Merging costs both lengths in sequential cells, about 1.7 µs each on the test cluster.
    - Today's batched lookups cost the driver's length in lookups, about 59 µs each.
    - So merge when the longer list is under about 35 times the driver, and look up otherwise. The ratio is a setting, measured per backend.
  - **Slices:** all of a star's lists are cut at the same subject boundaries, taken from `spo`'s tablet splits or sampled. Each slice is one ordered scan per pattern, intersected on its own thread.
  - **Scope:** patterns in the default scope or one named graph, constant predicates and objects, one shared subject variable. Patterns with unbound objects stay batched lookups over the merge's output, as entity prefetch does today.
  - **The type check:** each list is a `po` range with its object's row suffix (O20), so a value of another type with the same data is dropped on the server, as in a single lookup.
  - **Value conditions don't merge.** A prefix range (O13) or a value range (O14) of `po` is sorted by value, then by subject, so it can't be intersected as it streams. A star with a value condition is driven by the value range when that is short (O14), or merges its constant patterns and looks the value up per match. A later step could read a short value range into a set of subjects and filter the merge's output with it.
  - **RDF4J's own merge join** (6.1, experimental, `TripleSource.getSupportedOrders`) was considered and not used. It joins two inputs at a time, decides with a fixed global factor (10×) where O22 needs a per-backend ratio, relies on the triple source's value comparator matching the stored order, and marks joins as merge joins, which turns off the batched joins and entity chains above them.


**O23: the same intersection in the servers.**
- **The table:** optional, `<prefix>entity`, maintained by `IndexManager` like the text and geo indexes.
  - Row: a shard, from a hash of the subject, so a subject's postings are all in one shard and the shards spread over every tablet server.
  - Column family: the predicate and object.
  - Column qualifier: the subject.
  - Visibility: the statement's label.
- **The work:** a star is sent to every shard at once, with a batch scan over all shard rows. In each shard a server-side iterator seeks the star's posting lists and intersects them, the way Accumulo's intersecting iterator does for documents. Only matching subjects come back; their attributes are then batched lookups.
- **Cost:**
  - about another copy of `po`, on disk and in writes (roughly 20 B per triple);
  - Tripatha code on the servers: an iterator jar on Accumulo's tablet servers, a second coprocessor on HBase. RocksDB has no servers, but its shards could be intersected on several local threads;
  - value filters need O14's ranges inside a posting list.
- **When:** only if O22's measurements show the client's intersection, or the transfer of the lists, as the limit (D13).

---

## 7. Plan

Re-ordered on 2026-11-16: internal optimizations first, load work deferred. Each phase ends green on `mvn clean verify`, and on the RocksDB and HBase IT runs. Each can be merged on its own.

### Phase 0: counters and the benchmark

- **O1**, the loader's counters: built; green on Accumulo, HBase and RocksDB.
- **M8**, the local join benchmark: done (`tripatha.loader/bench.sh`); the baseline is in section 4.6.

**Exit:**
- O1 merged;
- the M8 baseline report: latencies, a profile, and scans and cells per query.

### Phase 1: the join path and filters as key ranges

In this order, each measured on M8 before and after (on RocksDB and on the Accumulo test cluster):
1. **O15**, adaptive windows: done;
2. **O13**, `STRSTARTS` as a prefix range: done;
3. **O21**, one range per subject: built and measured, off by default (no gain warm; to be tried cold, M3);
4. **M8's follow-ups**: CPU apart from blocked threads, and settings compared back to back in one run;
5. **O20**, the type check without a regex, and **O19**, RocksDB key decoding: done;
6. **M10**, class-star queries in the benchmark, then **O22**, merge joins of subject-sorted ranges: done. Next, O24: slices intersected in parallel, and reading only the subjects of each cell instead of decoding whole statements;
7. **O14**, comparisons as value ranges, with the datatype markers and the storage-version check (D12, decided). Value conditions in class stars need it;
8. **O16** and **O17**, only if the split profile shows where the Accumulo joins' time goes;
9. **O7**, parallel reads of one large range;
10. **O23**, stars evaluated in the servers, after D13.

**Exit:**
- every change has an M8 report;
- answers are identical with every optimization on and off;
- every IT is green with `-Dtripatha.test.joinBatchSize=2` and `-Dtripatha.test.indexes=true`.

### Phase 2: IRI prefix codes

M9 ran on 2026-11-16: −4 to −5 % on disk, −25 to −35 % in the cache (section 6.9). D11's recommendation is not now. O18 is deferred until a cluster measurement (M3) shows lookups bound by cache misses.

### Phase 3: whole-table operations and planning at scale

- **O4**, graph drops per D8;
- **O5**, index rebuilds and purge;
- **O6**, text planning from posting counts;
- **O8**, approximate counts;
- **O9**, deprecating `tbl.hashprefix`.

**Exit:**
- an AND of a common and a rare word is driven by the rare one;
- a graph drop costs the graph's size (with D8 (a)).

### Later

**Load work, deferred on 2026-11-16:**
- **O2:** the loader at 1T;
- **O3 and O10:** the bulk build, with its spikes (M6);
- **M2:** the ingest measurements;
- **the 1T milestones.**

Sections 6.2 and 6.3 keep their designs.

**Measurements on a real cluster:**
- **M1, M3 and M4** whenever a cluster is available;
- **M7**, with phase 3.

**Conditional:**
- **dictionary encoding (O11)**, after M5;
- **an analytics engine (O12)**, only for D1 (b).

---

## 8. Published trillion-triple results

For scale and context, not as targets: neither result is directly comparable to Tripatha. They used different hardware, no visibility labels, and different reasoning and load setups. Tripatha's case at 1T has to be shown by its own run (phase 3).

| System | Result | How well it is confirmed |
|---|---|---|
| AllegroGraph (Franz) | 1.009T triples loaded in 338 h 5 min, about 829,556 triples/s. Announced 16 August 2011, as a pre-release | **The date** is from Franz's press release. **The time and rate** come only from a search engine's summary of Franz's benchmark table. The table itself is an image and couldn't be read here, but the two figures agree with each other. **The hardware is unconfirmed.** The press release names none, only support from Intel and Stillwater SuperComputing. The benchmark page's footnotes include both "32 core Intel E5520, 1 TB RAM" and "240 core Intel x5650, 2.66GHz, 1.28TB RAM, 88TB Disk", without saying which row each describes |
| Oracle Database | about 1T edges, LUBM 4400k: *Oracle Database: Benchmarking a Trillion Edges RDF Graph* (June 2020) | **The figures are second-hand.** According to search summaries, the paper describes a test from September 2014 on Oracle Database 12.1.0.1 on Exadata, loading 1.42M quads per second. The W3C LargeTripleStores page gives 1.08T triples on an Exadata X4-2. The PDF couldn't be fetched (HTTP 403) |
| Apache Rya | "billions of triples", on Accumulo with `spo`/`po`/`osp` tables, as here | **No figures.** The claim is from the Apache incubator proposal. The paper (Punnoose, Crainiceanu, Rapp, Cloud-I 2012) couldn't be extracted here, so its figures are unconfirmed |

---

## 9. Sources

**Tripatha at `2321ae3`:**
- `ARCH.md` sections 7, 9, 10, 11, 13 and 15; `BACKENDS-STUDY.md` sections 3.8 and 7; the README's loader section;
- the code:
  - `WholeRowHashedTripleResolver` and `GraphSizes` (the hashed-layout check);
  - `RdfCloudTripleStoreEvaluationStatistics` (missing counter → 1, except the `DoNotExpandSP` sum);
  - every reader of the counters: `RyaStoreEvalStatsDAO.getCardinality` and `graphSizes`, `GraphSizes`, the loader's `Verify`;
  - `RyaStoreDAO` (`dropGraph`, `purge`, `MAX_PENDING_COUNTERS`);
  - `EvalStatsMutations`;
  - `IndexSearcher` (`PROBE_LIMIT`, batched geo resolution);
  - `InferenceEngine.refreshGraph` and `fetchInstances`;
  - the loader's `Prepare`, `SplitPlanner`, `Progress`, `LoaderOptions` and `DryRun`.

**Measurements (2026-11-16, development machine, JDK 25):**
- `run.sh dry-run --triples 1000B --text-index --geo-index`, and the same without the indexes;
- a full aggregation of `EvalStatsMutations.counterKeys` over complete generated datasets of 100k and 400k entities (default dataset options), also counting distinct terms;
- the same counters summed per block of 1,000 entities over 200k entities, checking that no per-subject key spans two blocks.

The last two were one-off programs against the loader's classes; O2 moves them into `dry-run`.

**Published results:**
- Franz: https://allegrograph.com/press_room/franzs-allegrograph-sets-new-record-1-trillion-rdf-triples/ and https://allegrograph.com/benchmarks/
- Oracle: *Oracle Database: Benchmarking a Trillion Edges RDF Graph*, https://oracle.com/a/tech/docs/rdfgraph-1-trillion-benchmark.pdf (not readable here: HTTP 403); W3C, https://www.w3.org/wiki/LargeTripleStores
- Punnoose, Crainiceanu, Rapp, *Rya: A Scalable RDF Triple Store for the Clouds*, Cloud-I 2012 (DBLP `conf/cloudi/PunnooseCR12`); the Apache Rya incubator proposal
- Spark's 2014 sort record (Daytona GraySort, 100 TB): quoted from memory, for scale only

**Unconfirmed, settled by M1 to M7:**
- bytes on disk per table at 1B and 10B;
- the online path's ingest rate on a real cluster;
- single-client scan rates;
- the dictionary estimate of section 5.1;
- bulk counters on RocksDB and HBase;
- HBase bulk files with visibility tags;
- Spark on JDK 25.
