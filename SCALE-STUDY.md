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

**Order of the work, and why** (re-ordered 2026-11-16: internal optimizations first, load work deferred; re-ordered again on 2026-11-19, items 10 and 11 and section 7):
1. **Skip counters of 1 in the loader (O1).**
   - The planner already reads a missing counter as 1, so counters holding 1 carry no information. 90 % of the stored counters are such.
   - Measured: the cells sent per triple drop from 11.6 to 8.2, and the bytes from 1,065 to 871.
   - Green on Accumulo, HBase and RocksDB.
2. **A local join benchmark (M8).**
   - A fixed query suite over a generated store, with latencies, a profile, and scans and cells per query from the trace.
   - Every optimization below reports its effect on it, before and after.
3. **Fewer lookups (O15): done.** Batched joins start with small windows, so `LIMIT` queries stop early: `chain-limit` went from 1,003 ranges to 67.
   - O21 (one range per subject) was built and measured, and is **off by default**. It halved the ranges and read 4 times the cells: no gain on warm Accumulo, and 50 to 64 % slower on RocksDB.
4. **Filters as key ranges (O13, O14 and O25 done).**
   - O13: `STRSTARTS` reads only the matching prefix, so `prefix` reads 522 cells instead of 133,579.
   - O14: comparisons on numbers and date-times read one range per datatype, with the datatype markers of D12 and a storage-version check. `range-date` reads 8 cells instead of 240,443: 196 → 5.4 ms on Accumulo (section 4.8).
   - O25: the ranges of a pattern in a join are counted for its estimate, up to the shortest other pattern of the join (at most 1,000,000 cells; a fixed 10,000 until 100M showed it misplanning). A condition most values pass no longer drives its star (it was 8 times slower on Accumulo with the fixed estimate).
   - **Done meanwhile:**
     - the benchmark's two blind spots (M8): it now reports CPU apart from waiting, and compares settings within one run. It shows Accumulo joins are bound by the servers, not the client;
     - the type check without a regex (O20) and RocksDB key decoding (O19): on RocksDB, range scans are 23 to 31 % faster and joins 13 to 19 %.
   - **Then,** if the split profile supports them: more lookups in flight (O16), less client work per cell (O17), and parallel reads of one large range (O7: a class on its own is done, item 5).
5. **"All entities matching X" (D1): class stars in the benchmark (M10), merge joins (O22), value ranges (O14) and stars in the servers (O23) done.**
   - O22 reads a star's constant patterns as subject-sorted `po` ranges and intersects them. On the Accumulo test cluster that is 3 to 10 times faster than one lookup per driver row: `class-large` 1,446 → 137 ms, `class-2` 267 → 47 ms. On RocksDB, where a lookup is cheap, it gains only for lists of similar lengths, so the ratio that decides is per backend (section 4.7).
   - O14 makes a selective value condition the driver of its star: `class-value` 3,964 → 151 ms on Accumulo, `class-value-int` 1,796 → 408 ms (section 4.8). With O25 a condition most values pass leaves the star to its constant patterns.
   - O24 reads the merges' lists as subjects only, in 4 slices of the subject space on threads of their own: `class-large` 109 → 67 ms on Accumulo, 94 → 30 ms on RocksDB (section 4.9).
   - O7a reads a long star pattern on its own, a whole class say, as a merge of one list, its subjects in slices: `class-scan` 2.8 times faster on RocksDB and 2.3 on Accumulo at 10M, 4.9 times on RocksDB at 100M (section 4.12).
   - O23 keeps an optional entity index, sharded by subject, and the store intersects a star's lists in each shard: in Accumulo's tablet servers, with native seeks on RocksDB. Stars are 2.3 to 4 times faster on the benchmark, with 15 to 160 times fewer cells reaching the client. Counts, of a star or of one pattern such as a class's size, are 2.7 to 4.2 times faster on Accumulo, which returns one cell per shard. Value stars are 5 to 10 % slower. It costs 17 % more disk and about a fifth of the load rate (section 4.10). HBase intersects in its region servers too, with a coprocessor endpoint (D14 (a), section 4.21): stars 39 to 81 % faster there. Hash versus subject-range shards is open until M3.
6. **A step up: 100M triples, bigger than memory (section 4.11).**
   - What scales as designed: selective queries don't depend on the store's size, and ranges, index searches and the entity index's stars grow with what they read or less.
   - Lookups that miss the page cache cost about 4 times more (19 against 4.8 µs on local NVMe). At 10^12 most do, so queries bound by lookups should be planned with that figure.
   - Two defects, fixed: O25's fixed cap of 10,000 misplanned value stars (now relative to the star, `class-value-int` 510 → 224 ms), and O24's slices stopped running in parallel when no lookups followed (now one shared queue, 3.7 times faster than one slice).
7. **Against QLever and Oxigraph on a standard benchmark, WatDiv (M11, section 4.13).**
   - Each engine measured alone, Tripatha as its native executable. Over WatDiv's 100 queries at about 10M triples, Tripatha has the lowest geometric mean (3.8 ms), then Oxigraph (4.0) and QLever (10.1).
   - Small queries: Tripatha and Oxigraph are close, within half a millisecond on 9 of the 17 smaller templates. QLever pays 20 to 70 ms on selective stars and snowflakes.
   - Large intermediate results: QLever is 3 to 11 times faster than Tripatha (C3 346 ms against 3,872, C2 37 against 121). It reads IDs from a dictionary and merges whole sorted lists, which is where O11 and O22 to O24 point.
   - **The same day, after O16, O17 and the native executable's heap and build (sections 4.14 and 4.15):** 2.27 ms against Oxigraph's 3.63 and QLever's 9.49, the complex queries 67.9 ms against QLever's 66.3. QLever still leads on C2 and C3, which O26 targets.
   - **2026-11-20, after O26 (section 4.22):** 2.6 ms against Oxigraph's 4.0 and QLever's 10.1 (every engine about 10 % slower than the evening before), the complex queries 56.1 ms against QLever's 67.3, and C3 268 ms against 350: Tripatha now leads on C3. QLever still leads on C2.
8. **IRI prefix codes: measured (M9), and deferred.**
   - Short codes for common IRI prefixes save only 4 to 5 % on disk: RFile already stores shared prefixes once, and compresses the rest.
   - They save 25 to 35 % in the block cache and 44 to 57 % of the bytes a writer sends, which isn't enough to justify a storage-format change now (section 6.9).
9. **Whole-table operations and planning at scale (O4 to O6, O8, O9).**
10. **Next (2026-11-19): the load work,** no longer deferred, since it blocks 1T outright: the loader at 1T (O2, done: section 4.16), the bulk spikes (M6, done: section 4.17), then the bulk builds of Accumulo and RocksDB (O3) and of HBase (O10).
11. **Then queries in the servers, on every backend:** HBase's coprocessor endpoint (D14 (a), done for the entity index: section 4.21), then patterns whose object is never used, counted (O26: done, counted in the servers on Accumulo and HBase, section 4.22). Every optimization must hold on Accumulo, HBase and RocksDB at 10^12. Whole-table operations and planning at scale follow (item 9), then the fixed cost per query (M14). Dictionary encoding (O11) and an analytics engine (O12) stay conditional.

**Dictionary encoding comes late on purpose (R1, section 5.1).**
- The statement tables send 446 B per triple (0.5 PB at 1T), but compress about 7× on disk.
- IDs plus a dictionary of up to 450 billion terms come out about even on disk for this data.
- It is a format break that touches value ordering, triple terms, counters, indexes and labels.

So it is a measured go/no-go (M5), and nothing waits for it.

**Decisions for you:**

| # | Decision | Options | Recommendation | Decided |
|---|---|---|---|---|
| D1 | Which queries must work at 1T | (a) selective: lookups, entity stars, batched joins, search, approximate counts; (b) also analytics over the whole store (large scans, exact aggregates) | (a) as the target. (b) only through a separate engine (O12): no single process can stream a large share of 1T rows interactively | 2026-11-16: **"all entities matching X"**: entities of large classes selected by several properties and values. That is (a) plus large stars. It puts O22 and O23 on the plan, and makes O14 (D12) matter more |
| D2 | How the data arrives | (a) one bulk build, then small online updates; (b) a continuous stream at high rate | (a). The online path stays for updates; (b) would need the online path at about 1M triples/s, which nobody has measured (section 5.4) | 2026-11-19: **(a)**, with the plan of section 7 |
| D3 | Backend for the first 1T run | Accumulo, HBase | Accumulo. Labelled RFiles import directly, and bulk counters sum through the existing combiner. HBase needs label ordinals in its files and unique counter timestamps (section 6.2) | 2026-11-19: **Accumulo first.** Every optimization must still hold on all three backends at 1T, so HBase's bulk build (O10) follows in the same phase |
| D4 | Source data | (a) the synthetic loader; (b) a real dataset | Both: (a) for the benchmark ladder, (b) for acceptance. (b) must come as N-Triples or N-Quads, which split by line; Turtle can't be parsed in parallel | 2026-11-21: **both** (the user): the generator for the ladder, and N-Triples or N-Quads files through `tripatha.spark`'s `files` (O28, section 4.28) |
| D5 | What sorts the bulk build | (a) the loader itself: partitioned sorted runs, then one merge per tablet; (b) Spark | (a), unless M6 shows Spark runs Tripatha's JDK 25 code. That is unproven, and (a) reuses the loader's parts, progress and resume | 2026-11-19: **(a)**, unless M6 shows Spark runs Tripatha's JDK 25 code. 2026-11-21: **(b) for Accumulo:** the user's cluster runs Spark 4.2 on Java 25 (on Kubernetes), with Accumulo's volumes on S3, and Spark 4.2's tasks run Tripatha's code. Built as `tripatha.spark` (O28, section 4.28); (a) stays the build for RocksDB and HBase, and for Accumulo without Spark |
| D6 | Counters with a total of 1 | (a) don't write them: the per-subject families in the loader (O1), every family but `p` in a bulk build (O3); (b) write every counter | (a): the same estimates for a load into an empty store (section 6.3) | 2026-11-19: **(a)** |
| D7 | The hashed row layout (`tbl.hashprefix`) | keep, deprecate | deprecate: it doesn't do what it is kept for (section 5.3) | 2026-11-19: **deprecate** (O9, phase 6) |
| D8 | Dropping a graph at 1T | (a) a graph-first table (`gspo`), optional like the indexes, so a drop costs the graph's size; (b) graph tombstones hidden by scan and compaction filters on every backend; (c) accept a full-table scan per drop | (a) if graphs are the unit you load and replace, (c) otherwise. (b) puts a filter on every read path of every backend || 2026-11-20: **(c), keep a full scan per drop**: no `gspo` table and no tombstones on the read paths (O4 closed) |
| D9 | Dictionary encoding | (a) decide after M5, on the criteria of section 6.5; (b) start now | (a) | |
| D10 | Disks | NVMe SSD, HDD | NVMe for interactive use: at 1T most lookups miss the cache, and each miss is one read | 2026-11-19: **NVMe** |
| D11 | IRI prefix codes in the stored keys | (a) decide after M9, on the criteria of section 6.9; (b) start now; (c) never | (a), and M9 says **not now**: −4 to −5 % on disk is far below the bar. The cache gain (−25 to −35 %) is worth revisiting if a cluster shows lookups bound by cache misses (M3) | |
| D12 | Datatype markers for O14 (section 6.7) | (a) write them on every add from now on, flag new stores, rebuild old stores with O5; (b) no markers, so no O14 | (a) if range filters on numbers and dates matter to the workload. They cost one more `eval` cell per single-statement insert, cover new stores only until a rebuild, and forbid writes from older versions into flagged stores | 2026-11-16: **(a), with a storage-version check.** The workload's value conditions need O14. The DAO gets a version check: the markers raise the storage version, and a DAO refuses to write to a store whose version is newer than its own (older stores stay writable). Today it writes the version when missing and never checks it. A rebuild command for older stores comes with O5. O14 comes after O22 |
| D13 | The subject-partitioned table of O23 | (a) an optional table like the text and geo indexes, with Tripatha server code on the tablet servers (an Accumulo iterator jar, an HBase coprocessor); (b) none, so stars stay with O22 on the client | decide after O22 is measured: (a) only if the client's intersection is the limit. It costs about another copy of `po` on disk and in writes, and an iterator jar to deploy | 2026-11-17: **(a).** At 10M the client is not the limit (`class-large` merges 247,677 cells in 137 ms on Accumulo). At 10^12 it is: the lists of large classes and common values grow about 10^5 times, so `class-large`'s merge would read about 25 billion cells, some 4 hours on one thread, every cell crossing to one client, and `class-count` the same to return one number. In the servers each shard is intersected in parallel, with seeks that skip what can't match, and only matches or counts come back. It pays for counts, `LIMIT`s and intersections much smaller than their lists. **How:** optional and off by default, like the text and geo indexes; built after O25 and O24; Accumulo first, the same shards on local threads for RocksDB, HBase after. The benchmark gets counts and selective intersections of long lists; proving the gain at scale needs M3 and a larger load, deferred with the load work |
| D14 | How HBase intersects the entity index (O23, phase 4) | (a) a coprocessor endpoint on each region server doing what the Accumulo iterator does; (b) a semi-join: the shortest term's subjects per shard, then a seeking filter checks only those in each next term; (c) defer: HBase keeps the client intersection | An HBase filter sees a shard's cells as one stream ordered by term, so it can't leapfrog between the terms' lists, and it can't add a cell with a match's count without buffering the whole row. (a) is the right design but needs protobuf service definitions in the build; (b) still moves the shortest list whole | 2026-11-18: **(c), defer,** until phase 5's measurements; then, the same day, **(a), when HBase at 10^12 is an active target** (the milestone is deferred with the load work). The client intersection is about as fast as no index (section 4.10), so until then the index is to be left off on HBase (README). (b) is out: it moves the shortest list whole, and for stars whose every list is long that is the 10^12 problem D13 is about. 2026-11-19: **(a), now** (phase 4): every optimization must hold on all three backends at 10^12, so HBase at 10^12 is an active target. One endpoint counts ranges for O26 and intersects the entity index. 2026-11-20: **the intersection is done** (section 4.21): `IntersectionEndpoint` in `tripatha.hbase.coprocessor`, paged, under the caller's labels through the region's `preScannerOpen`, with its protobuf code generated by protoc 34.0 and checked in. O26 adds the range counts 2026-11-20: **the range counts are done too** (section 4.22): `RangeCountEndpoint` on every statement table |

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
| O1 | **Loader: don't write per-subject counters of 1** (`s`, `sp`, `so`), summed per block. Includes reading a missing counter as 1 in the inference sum, in `verify` and in `LoaderIT` (section 6.1) | R5 | measured (`dry-run` at 1T, both indexes): cells sent per triple 11.6 → 8.2 (−29 %), bytes 1,065 → 871 (−18 %), `eval` 348 → 154 B. *est.* −20 % on disk (about 100 TB less at 1T with 3 replicas) | — | 0 | Done (PR #15) |
| O2 | **Loader at 1T:**<br>• `--triples 1T`<br>• `prepare --tablet-size`, planning splits by size instead of 4 per server<br>• `dry-run` reports stored counter cells and their share of 1s | R8, R9 | tablets near the target size from the start, instead of about 50 GB tablets splitting online | — | 3 | Done (2026-11-20, section 4.16): `--triples 1T`; `prepare` plans each table's splits by its estimated size on disk over `--tablet-size` (10G), at least 4 per server, and makes that size the backend's split threshold (`TableAdmin.setTargetSize`); HBase splits in rounds; the progress file is a watermark plus the blocks above it; `dry-run` reports the stored counters per family |
| O3 | **Bulk build on Accumulo and RocksDB:**<br>• sorted files per tablet, then an import<br>• every counter of 1 left out except `p`<br>• the DAO's guards in the writer<br>• input from the generator or from N-Triples/N-Quads files<br>(section 6.2) | R4, R5, R11 | no write-ahead log, memory tables or compactions. *est.* counters about 5.4 → 0.5 stored cells per triple, 160 → about 115 B per triple on disk | O1, M6 | 3 | Done for Accumulo and RocksDB (2026-11-20, sections 4.18 and 4.19): `build`, the same cells as `load` (checked cell by cell), at 10M 1.5 times faster on RocksDB and 13 times on the Accumulo test cluster, `eval` 90 % smaller. The 10B milestone needs a cluster |
| O4 | **Graph drops in time proportional to the graph** (D8) | R6 | a drop reads the graph, not the 5 cells per triple of every table (about 5T cells at 1T) | D8 | 5 | Won't do: D8 (c), 2026-11-20 |
| O5 | **Index rebuilds through the bulk build; purge by recreating tables** when the caller sees everything | R6 | a rebuild becomes a batch job; such a purge becomes constant time | O3 | 5 | Done (2026-11-20, section 4.23): the loader's `rebuild` sorts `spo` by split range into the indexes' files and imports them, while writers record their deletes for the job to replay; graph drops wait for it. Accumulo test cluster 3M: 16.6 → 5.8 s; RocksDB 10M: 18.5 → 9.3 s. `purge` left as it is (no production caller) |
| O6 | **Text planning from per-term posting counts** | R7 | an AND is driven by its rarest word | — | 5 | Done (2026-11-21, section 4.25): `<prefix>textcount`, one counter per word, predicate and label, kept by every write path; a word past the probe is sized by it when the counts are complete. `text-and` 291.5 → 112.3 ms (RocksDB 10M), 270.6 → 212.5 ms (Accumulo 3M); the load +8 %, the table 0.4 % of the store |
| O7a | **Parallel reads of one large range, constant object:** a star pattern on its own (`?x a :C`: alone, counted, or driving lookups) read as a merge of one list, its subjects in slices at `spo`'s split points (section 6.6) | R2 | every server holding the list reads at once, and the client decodes no statements | O24 | 1 | Done: `query.join.mergesingle` (10,000). A whole class returned is 2.8 times faster on RocksDB and 2.3 on Accumulo at 10M, 4.9 times on RocksDB at 100M; counted without the entity index, 2.1 to 4.3 times; with a lookup per member, 13 to 16 % on RocksDB and nothing on Accumulo, where the lookups are the time (section 4.12) |
| O7b | **Parallel reads of one large range, variable object:** `?s :p ?o` (a whole predicate, such as a filter no index covers), cut at the table's own split points, statements decoded on the slice threads (section 6.6) | R2 | every server holding the range reads at once | — | 1 | Not started: later, when a workload needs it (decided 2026-11-19) |
| O8 | **Approximate counts from the counters** (class sizes, like `stats:approxSize`) | R12 | a count in a few reads instead of a scan | — | 5 | Done (2026-11-20, section 4.24): `stats:approxInstances` (a class's `rdf:type` statements) and `stats:approxTriples` (a predicate's statements) answered from the counters, with `stats:approxSize`, by `ApproxCountOptimizer`. A class's size: 0.4 ms and 1 cell against 12.4 ms and 53,432 cells counted (RocksDB 10M); 1.2 against 12.7 ms on Accumulo, 0.9 against 14.1 on HBase (3M) |
| O9 | **Deprecate `tbl.hashprefix`** | R3 | removes a layout that costs 33 B per row and spreads nothing | D7 | 6 | Done (2026-11-21, section 5.3): the setting and `WholeRowHashedTripleResolver` are `@Deprecated`, a DAO opening a store on the layout warns, and existing stores keep working |
| O10 | **HBase bulk build:** label ordinals in the files, counter timestamps per import | R4, R10 | as O3, on HBase | O3, M6 | 3 | Done (2026-11-20, section 4.20): `tripatha.hbase.bulk`, HFiles with tags of label ordinals (recorded in the canaries by `labels`), loaded as the store's user; coprocessor mode only, increment mode refused (decided 2026-11-20) |
| O11 | **Dictionary encoding**, only if M5 says go (section 6.5) | R1 | to be measured | M5, D9 | later | Not started |
| O12 | **An analytics engine and pushdown**, only if D1 (b) (section 6.6) | R2 | whole-store analytics | D1 | later | Not started |
| O13 | **`STRSTARTS` as a prefix range:** a filter `STRSTARTS(?o, "abc")` on a pattern's object narrows its scan to the objects whose data starts with `abc`, with no type check; the filter stays. Not `STRSTARTS(STR(?o), …)`: numbers, dates and booleans aren't stored as their text (section 6.7, ARCH.md 12.8) | R2 | measured (M8): `prefix` 92 → 0.7 ms on RocksDB and 72 → 2.2 ms on Accumulo, 133,579 → 522 cells. `query.prefixranges` turns it off | M8 | 1 | Done (PR #15) |
| O14 | **Comparisons as value ranges:** `<`, `<=`, `>`, `>=` on a pattern's numeric or date object narrow its scan to the value range, one range per stored type; the filter stays. Needs a record of the datatypes each predicate's objects use (section 6.7) | R2 | the same, for numbers and dates | M8 | 1 | Done: datatype markers, storage version 1.1.0 with a write check, `ValueRangeOptimizer`; on by default (`query.valueranges`). Range queries 5 to 36 times faster on Accumulo, `class-value` 26 times; a condition most values pass is 4 to 8 times slower in a star (section 4.8, O25) |
| O15 | **Adaptive batch windows in batched joins:** the first window is 64 left rows, doubling up to `query.join.batchsize`, as entity prefetch does (section 6.8) | R2 | measured (M8, section 4.6): `chain-limit` 114 → 19 ms on Accumulo and 6.9 → 0.7 ms on RocksDB (1,003 → 67 ranges); `type-limit` 164 → 40 ms and 22.6 → 1.6 ms (2,004 → 260 ranges). No cost on large joins: `chain` −7 % with 16 batch scans instead of 13 | M8 | 1 | Done (PR #15) |
| O16 | **More lookups in flight:** batched joins and entity prefetch keep the next window's fetch running while the current one is joined, and more than one when the store has several servers (section 6.8) | R2 | the tablet server is the limit on the test cluster (4.6), so overlapping with client work gains nothing. More server parallelism (more tablets, concurrent batch scans) is the open question, for a cluster (M3) | M8 | 1 | Done for the reading of a batch (2026-11-19, section 4.14): a batch of disjoint lookups is read on up to `query.join.lookupthreads` threads, 4 by default on RocksDB, 1 on Accumulo and HBase until measured. WatDiv C3 2,371 → 1,189 ms on the native executable (−50 %); the join benchmark's lookup-bound queries −17 to −44 %. Then batches of several shapes, such as C2's, which bind objects (C2 126.7 → 79.1 ms), and fetching an entity chain's next window on another thread while the current one is joined (`query.join.prefetchahead`, on by default on RocksDB): C3 987 → 893 ms, WatDiv's complex queries 112.2 → 97.7 ms. Not done: the Accumulo and HBase defaults, 1 thread and no fetching ahead until measured on a cluster (M3). |
| O17 | **Less work per looked-up row:** `BatchLookup` matches on the row's encoded object instead of encoding every result again, and allocates less per row (section 6.8) | R2 | dropped for joins: on Accumulo the query thread's CPU is 2 to 3 % of a join's wall time (4.6). It could matter for range scans, which are client-bound | M8 | 1 | Done for the lookup path's CPU (2026-11-19, section 4.14): batches whose ranges can't overlap skip the cell check (any shapes, unless one key's parts are a strict prefix of another's), entity chains also the objects' re-encoding, statement rows and RocksDB keys are decoded in one pass. WatDiv on the native executable: C3 3,872 → 2,268 ms (−41 %), the complex queries −24 %, all 100 queries −11 %; the join benchmark within noise. |
| O18 | **IRI prefix codes**, only if M9 says go (section 6.9) | R1 | measured (M9): −4 to −5 % on disk, −25 to −35 % in the block cache, −44 to −57 % of the key bytes a writer sends, no clear change in scan and decode time | M9, D11 | 2 | Deferred (2026-11-16: below the bar on disk; revisit after M3, see 6.9) |
| O19 | **RocksDB key decoding:** `RocksCodec` encodes and decodes keys by copying the runs between escapes in bulk, instead of a byte at a time through a synchronized `ByteArrayOutputStream` | — | measured with O20 on RocksDB (M8, against `rocksdb-20261116-204243`): `count-class` 71 → 25 ms, range scans −23 to −31 %, joins −13 to −19 % | — | 1 | Done (PR #15) |
| O20 | **The object-type check as a row suffix:** `ScanSpec.rowSuffix`, set by the query engine when the type is the only row condition. RocksDB compares bytes. Accumulo and HBase filter with the equivalent linear `[\s\S]*` and the quoted suffix, instead of three open groups. It also fixes datatype IRIs being pasted into the expression unquoted, which let a `.` in a custom datatype IRI match any character | R2 | measured with O19, above. On Accumulo it means less tablet-server CPU per type-checked cell, not measured separately | — | 1 | Done (PR #15) |
| O21 | **One range per subject:** when a window looks up two or more links of the same subject in `spo`, it reads the subject's range once and lets `BatchLookup`'s exact matching keep the cells asked for. A batch whose subject ranges return more than 64 cells per subject plus 1,024 is re-read one range per link (section 6.8) | R2 | measured (M8): `star-in` 17,594 → 8,799 ranges but 25,445 → 103,265 cells; `star-optional` 7,601 → 2,537 ranges, 9,345 → 28,772 cells. Accumulo: no change beyond noise. RocksDB: +64 % and +49 %. So `query.join.subjectranges` is **off by default**. Untested hypothesis: on a cold cache a seek is a block read and the extra cells are in the same block (M3) | M8 | 1 | Built, off by default |
| O22 | **Merge joins of subject-sorted ranges:** for a star with constant objects (`?x a :Person ; :worksFor :Acme ; :nationality :IT`), each pattern is a `po` range sorted by subject. Stream them side by side and intersect, instead of one lookup per row. The subject space is sliced so several slices are intersected at once (section 6.10) | R2 | measured on M10's class stars (section 4.7): 3 to 10 times faster than a lookup per driver row on the Accumulo test cluster, `class-large` 1,446 → 137 ms | M10 | 1 | Done: `SubjectMergeJoin`, on by default with a ratio per backend (35 Accumulo, 20 HBase, 8 RocksDB); measured in section 4.7. Slices in parallel and subject-only reads are O24 |
| O23 | **Stars evaluated in the servers:** an optional subject-partitioned table, maintained like an index. A shard holds, per predicate and object, the sorted subjects of its share of the store. A server-side iterator (Accumulo) or coprocessor (HBase) intersects a star's lists inside every shard in parallel, and returns only the matching subjects (section 6.10) | R2 | the distributed join for "all entities matching X" (D1). To be measured against O22 | O22, D13 | 2 | Done on Accumulo and RocksDB (PR #17): the entity index (`index.entity`, storage version 1.2.0), read shard by shard by merge joins and intersected by the store (`Store.intersect`), in Accumulo's tablet servers (`tripatha.accumulo.iterators`) and with RocksDB's native seeks. The store also counts a star, or one pattern (a class's size). On the benchmark stars are 2.3 to 4 times faster, counts 2.7 to 4.2 times on Accumulo (one cell per shard back), `count-class` 4 times; value stars 5 to 10 % slower. The index costs 17 % more disk and a fifth of the load rate (section 4.10). On HBase since 2026-11-20 (D14, section 4.21): a coprocessor endpoint in its region servers, 39 to 81 % faster than without the index on stars and counts |
| O24 | **Merge joins, part 2:** intersect a star's lists in slices of the subject space on several threads at once (cut at `spo`'s tablet splits or at sampled subjects), and read only each cell's subject instead of decoding the whole statement (section 6.10) | R2 | O22 reads one list per pattern on one thread, and decodes every cell | O22 | 1 | Done: subjects read as bytes, no statement decoding; slices cut at `spo`'s split points (`TableAdmin.splitRows`), up to `query.join.mergeslices` (4) on threads of their own. With 4 slices merges are 1.6 to 2 times faster on Accumulo and HBase and 3 times on RocksDB (section 4.9). At 100M triples the slices stalled when no lookups followed: 4 were 11 % faster than 1. Since 2026-11-18 they share one queue then: 3.7 times (section 4.11) |
| O25 | **A real estimate for a value range:** count the range's cells up to a cap (about 10,000) when its pattern is in a join, and use the count when the range is shorter, or assume most of the predicate when it isn't. Only the join order depends on it, so a pattern on its own needs none | R2 | O14's estimate is a fixed tenth of the predicate: `class-value-wide` (`?v > 0`, 91 % pass) is 8.2 times slower on Accumulo with it, 4.1 on RocksDB and 3.6 on HBase (section 4.8) | O14 | 1 | Done: counted up to `query.valueranges.count` (10,000) for a pattern in a join. `class-value-wide` is back within 1 to 6 % of the merge plan, and the selective stars keep their gains; a condition between the cap and the star's lists (`class-value-mid`) gets the merge plan, 1.2 to 2.75 times slower than the range would be (section 4.8). Since 2026-11-18 the count stops at the shortest other pattern of the join, at most 1,000,000 cells. At 100M the fixed cap had lost `class-value-int` its plan (510 → 224 ms with the fix); `class-value-mid` now gets the range plan, 12 to 39 % faster at 10M; a wide condition pays 1 to 7.5 % for the longer count (section 4.11) |
| O26 | **Patterns whose object is never used, counted in the servers:** a pattern whose object variable appears once and isn't returned (`SELECT ?s WHERE { ?s :p ?o … }`) only decides how many times a row comes back. Its lookups ask the store for one count per range, under the query's labels and the graph condition, instead of reading and decoding every match; the joins repeat the row that many times, and with `DISTINCT` existence is enough. Accumulo counts with an option of its iterators, RocksDB with native iteration that copies no values, HBase with the coprocessor endpoint of D14 (a client count where it isn't deployed). Never from the `eval` counters: they leave out 1s and can drift | R2 | one cell per range crosses the network instead of every match (about 10 times fewer for `?s :friendOf ?f`). On WatDiv C1 to C3, where QLever leads, decoding and row building were 29 % of C3's CPU (section 4.14) | D14 | 4 | Done on every backend (2026-11-20, section 4.22): `CountedPatternOptimizer` marks the patterns, batched joins and entity chains fetch counts with their statements, and the store counts without reading: natively on RocksDB, in Accumulo's tablet servers (`RangeCountingIterator`) and HBase's region servers (`RangeCountEndpoint`), the count running alongside the window's reads. WatDiv C3-01 in-process −37 to −49 %, and on the native executable C3 533 → 268 ms (QLever 350); on the join benchmark `star-unused` sends 23 % fewer cells to the client on Accumulo and 12 % fewer on HBase, and `chain-unused` is 20 % faster on HBase |
| O27 | **Planning's counter reads in one round trip:** the counters every pattern's estimate needs read together, before join ordering, instead of one round trip per pattern (`RdfCloudTripleStoreEvaluationStatistics.prefetch`, `query.stats.prefetch`) | — | one batch scan per query's planning | M14 | 6 | Done (2026-11-21, section 4.26): the median plan 1.69 → 1.23 ms on the Accumulo test cluster, 0.50 → 0.37 on HBase; the same plans and rows |
| O28 | **The bulk build as a Spark job, on Accumulo:** the DAO's cells made in Spark's tasks, sorted by its shuffle into one RFile per range of a tablet, written by the executors to one of Accumulo's volumes (S3 or HDFS), then imported. Input from the generator or from N-Triples and N-Quads files | R4 | O3's build spread over a cluster's executors instead of one machine's threads; real RDF files as input | O3, D5, M6 | 3 | Done (2026-11-21, section 4.28): `tripatha.spark`, the same cells as `build` and as a DAO load (checked cell by cell); at 10M on one machine 63.5 s against `build`'s 50.8 s, and WatDiv's 10.95M triples from N-Triples in 52.3 s. First cluster run: 500M triples on the user's cluster |

### 2.2 Measurements

All on a real cluster with NVMe disks, unless the row says otherwise.

| # | Measurement | Feeds | Status |
|---|---|---|---|
| M1 | **Bytes on disk per table**, at 1B and 10B triples on Accumulo, and for a real dataset of at least 1B (D4). Replaces the README's test-load figure | section 4, cluster size, O3 | Not started |
| M2 | **Ingest on the current write path:** triples/s against the number of servers, and the compaction backlog. Also BACKENDS-STUDY.md's benchmark B0, which never ran | D2, section 4.5 | Planned: phase 3, on a cluster (2026-11-19) |
| M3 | **The query suite:** lookups, star and chain joins, entity prefetch, text, geo, `stats:approxSize`, inference. p50 and p99, cold and warm, with scans and cells per query from the trace. Also `query.join.subjectranges` on and off, on a cold cache (O21) | the 1T run's acceptance | Not started |
| M4 | **Single-client scan rates**, Scanner and BatchScanner | R2, O7, D1 | Not started |
| M5 | **The dictionary spike,** offline, on a 1B-triple sample (section 6.5) | D9, O11 | Not started |
| M6 | **Bulk spikes:**<br>• Accumulo RFiles with labels and counters, written from JDK 25 and imported into a live table (with a `LoadPlan`)<br>• RocksDB SST files with `merge` entries, ingested into a family that already has counters<br>• HBase HFiles with visibility tags through the expression resolver, and the same counter file imported twice in coprocessor mode<br>• Spark executors running `tripatha.api` code on JDK 25 | O3, O10, D5 | Done (2026-11-20, section 4.17) for Accumulo, RocksDB and HBase in both counter modes: `AccumuloBulkSpikeIT`, `RocksBulkSpikeTest`, `HBaseBulkSpikeIT`. The Spark spike wasn't run then: D5 (a) was taken with the plan. It ran on 2026-11-21 (section 4.28): Spark 4.2.1 on Java 25, with Hadoop 3.5, runs Tripatha's encoding in its tasks (`SparkJava25SpikeTest`), and the job's jar runs under the distribution's own `spark-submit` |
| M7 | **Maintenance at 10B:** `DROP GRAPH` of a small and of a large graph, graph sizes, an index rebuild | O4, O5 | Not started (with phase 5) |
| M8 | **A local join benchmark,** no cluster needed: a fixed query suite over a generated store of about 10M triples, on RocksDB and the Accumulo test cluster. Star and chain joins, entity prefetch, OPTIONAL, `LIMIT`, value and prefix filters, text and geo. Reports p50 and p99, a profile of where the time goes, and scans and cells per query from the trace. Checked in, so each optimization reports before and after | O13 to O17 | Done: `tripatha.loader/bench.sh` (`JoinBench`); baseline in section 4.6. Follow-ups: report CPU samples apart from threads blocked in native code, and compare settings back to back in one run (`--conf` variants) |
| M9 | **IRI prefix codes, measured offline:** the generator's `spo`, `po` and `osp` rows written to RFiles as today and with prefix codes. Compares bytes sent, compressed size, and scan and decode time (section 6.9) | D11, O18 | Done 2026-11-16: results in section 6.9 |
| M10 | **Class-star queries in the join benchmark:** entities of a large class selected by two to four constant properties, with and without a value filter and with attributes fetched. For example, people of one nationality working for one company, and observations of one property above a value. They measure O14, O22 and O23 | O14, O22, O23 | Done: seven `class-*` queries; baseline in section 4.7 |
| M11 | **Against other engines, on a standard benchmark:** WatDiv's 20 basic templates at about 10M triples, on Tripatha's endpoint over RocksDB, QLever and Oxigraph, over HTTP, with the same data and checked rows (`compare.sh`, tripatha.loader/README.md) | The endpoint's cost per query, O11, O22 to O24 | Done: section 4.13 |
| M12 | **Where a query's time goes, in-process:** `QueryProfile` (tripatha.loader test sources) runs one query on a RocksDB store and splits its time between reading the rows and writing them, the store's scans by reason, and JFR CPU samples. Applied to WatDiv's complex queries | O16, O17, O11 | Done: section 4.14 |
| M13 | **The native executable's collector and build:** its heap settings, and native-image's `-O3`, `-march=native` and profile-guided optimization, on WatDiv over HTTP against the JVM on the same path | M11 | Done: section 4.15 |
| M14 | **The fixed cost per query:** where a small query's time goes between the request and its first scan, on the JVM endpoint that Accumulo and HBase use and on the native executable, over HTTP with warm JITs (Oxigraph answers 7 small WatDiv templates 0.02 to 1.9 ms sooner) | phase 6 | Done (2026-11-21, section 4.26): the fixed cost is about 0.13 ms per request on the native executable and 0.14 on the JVM (Oxigraph 0.08), and Tripatha is the faster on small WatDiv queries measured warm (1.0 against 1.2 ms). On Accumulo and HBase, planning's counter reads are the fixed cost: now read together (O27) |

### 2.3 Milestones

| Milestone | Needs | Exit | Status |
|---|---|---|---|
| 10B bulk build on Accumulo | O3 | the build is measured, with a time model to 1T | Planned: phase 3 (2026-11-19) |
| 1T on Accumulo | O1 to O9, M1 to M7 | a report against the extrapolations of M1 to M4, with every gap explained | Planned: after phases 3 to 5 and the cluster measurements |
| 1T on HBase | O10, D14 | the same report on HBase | Planned: after phases 3 to 5 and the cluster measurements |

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

### 4.8 Comparisons as value ranges (O14)

Measured on 2026-11-17 as variants of one run per backend: `query.valueranges` off, and on (the default). The RocksDB store was loaded again for it, since a store from before the markers is never narrowed. Reports: `rocksdb-20261117-072740` (the whole suite), `accumulo-20261117-075231` and `hbase-20261117-080326` (the six queries with a comparison).

| Query | Condition, and how many values pass | Rows | RocksDB off → on | Accumulo off → on | HBase off → on | Cells off → on |
|---|---|---|---|---|---|---|
| `range-number` | `population > …` (`xsd:long`): 387 of about 26,900 | 387 | 22.7 → **1.1 ms** | 27.7 → **5.4 ms** | 37.3 → **4.5 ms** | 26,917 → 390 |
| `range-date` | a one-hour window of `resultTime` (`xsd:dateTime`): 5 of 240,441 | 5 | 293 → **1.1 ms** | 196 → **5.4 ms** | 242 → **3.3 ms** | 240,443 → 8 |
| `range-double` | `hasResult > 60` (`xsd:double`): 922 of 240,441 | 922 | 197 → **1.8 ms** | 151 → **5.5 ms** | 165 → **4.4 ms** | 240,442 → 926 |
| `class-value` | observations of one property with `hasResult > 60` | 218 | 606 → **12.5 ms** | 3,964 → **151 ms** | 1,268 → **26.9 ms** | 352,790 → 2,068 |
| `class-value-int` | companies of one industry with `numberOfEmployees >= 100` (`xsd:integer`): 2,645 of 53,431 | 201 | 114 → **29 ms** | 1,796 → **408 ms** | 266 → **58 ms** | 70,973 → 4,023 |
| `class-value-wide` | `class-value` with `hasResult > 0`: about 218,500 of 240,441 | 51,116 | 611 → 2,522 ms (**4.1× slower**) | 3,958 → 32,398 ms (**8.2× slower**) | 1,095 → 3,934 ms (**3.6× slower**) | 352,790 → 488,234 |

**What it shows:**
- **A bounded pattern reads only its range,** plus the markers of its predicate (one row). `range-date` reads 8 cells instead of 240,443, and the three range queries become single-digit milliseconds on every backend.
- **A selective value condition now drives its star.** `class-value` used to merge the type with the property list (O22) and look the result up for each of the 56,173 matches. Now it reads the results from 60 up (about 925 cells) and looks up their property and type: 1,849 ranges instead of 56,178, 26 times faster on Accumulo. `class-value-int` reads the employee counts from 99 up and looks up 5,295 ranges, instead of 9,698.
- **A condition most values pass makes it the wrong driver.** Its estimate is fixed at a tenth of the predicate (24,000 for `hasResult`), below the property list's 56,173, so the range drives. But about 218,500 values pass, each looked up twice: 437,101 ranges. That is 3.6 to 4.1 times slower on HBase and RocksDB, and 8.2 on Accumulo, where a lookup costs most. The estimate can't serve both sides: at 0.3, `class-value` would not be driven by its range either. What would fix it is a real estimate of the range, which needs a count (see the tracker, O25).
- **Rows** were the same in every pair.
- **Nothing else changes.** The 15 queries without a comparison read exactly the same scans, ranges and cells with the setting on and off (the RocksDB run covers the whole suite).
- **The type check costs nothing visible:** each range is one scan with a row suffix, and the generator's predicates have one type each.
- Accumulo's "off" for `class-value` (3,964 ms) is slower than the 2,141 ms measured for the same plan in 4.7. Runs on different days differ that much; compare within a run.

**After O25** (a counted estimate), as three variants of one run per backend: value ranges off, O14's fixed estimate (`query.valueranges.count=0`), and the count up to 10,000 cells (the default). Reports `rocksdb-20261117-085412` (the whole suite), `accumulo-20261117-092313` and `hbase-20261117-093904`. A new query, `class-value-mid` (`hasResult > 40`, about 22,000 values pass), sits between the cap and the property list.

| Query | RocksDB off / fixed / counted | Accumulo off / fixed / counted | HBase off / fixed / counted |
|---|---|---|---|
| `class-value` (`> 60`) | 604 / 12.0 / **8.8 ms** | 3,971 / 149 / **152 ms** | 1,106 / 25.3 / **25.6 ms** |
| `class-value-int` (`>= 100`) | 116 / 28.9 / **21.9 ms** | 1,773 / 405 / **404 ms** | 261 / 57 / **61 ms** |
| `class-value-mid` (`> 40`) | 605 / 255 / 609 ms | 3,965 / 3,315 / 3,986 ms | 1,167 / 408 / 1,123 ms |
| `class-value-wide` (`> 0`) | 611 / 2,513 / **619 ms** | 3,943 / 32,326 / **3,990 ms** | 1,667 / 4,992 / **1,762 ms** |
| `range-date` (alone, not counted) | 287 / 1.2 / 0.7 ms | 203 / 5.3 / 4.5 ms | 274 / 4.1 / 2.8 ms |

- **The losing side is gone.** `class-value-wide`'s range reaches the cap, is estimated like its whole predicate, and the star merges its constant patterns as before O14: within 1 to 6 % of the plan with ranges off. The difference is the count's 10,000 cells.
- **The selective stars keep their gains.** Their ranges count 924 and about 2,670 cells, under the cap, so they still drive: `class-value` is 26 times faster than with ranges off on Accumulo and 43 times on HBase.
- **What the cap gives up:** `class-value-mid` counts past 10,000 and gets the merge plan, though its range would drive it 2.4 times faster on RocksDB, 2.75 times on HBase, and only 1.2 times on Accumulo, where its 43,907 lookups cost most. A higher cap would win it back on RocksDB and HBase, for a longer count on every wide condition in a join (about 1 µs a cell): a per-backend cap, like O22's ratio, is the next step if such conditions matter. *(2026-11-18: the cap is now the shortest other pattern of the join, at most 1,000,000 cells, and `class-value-mid` gets the range plan: section 4.11.)*
- **A pattern on its own isn't counted:** the range queries read the same cells with and without the count.
- **Nothing else changes:** on RocksDB the other 15 queries read exactly the same in all three variants.

### 4.9 Merge joins in slices, reading subjects only (O24)

Profiled first (2026-11-17, before O24), on `class-large`'s merge of three lists (247,677 cells):
- **RocksDB:** 172 ms, all of it on the query thread: 44 % decoding cells into statements (`WholeRowTripleResolver.deserialize` alone 34 %), 40 % the backend's key decoding, 7 % comparing subjects as strings.
- **Accumulo:** 176 ms, of which the query thread's CPU is 55 ms: 42 % decoding statements and 41 % the Thrift client. The rest is waiting for batches.

So O24 does two things: it reads only each cell's subject, as bytes (the decoding share), and cuts the lists into slices of the subject space, intersected on threads of their own (the waiting share, and the CPU share on RocksDB). The benchmark stores of Accumulo and HBase have 3 split points, so 4 slices at most; RocksDB's come from its SST files.

As variants of one run per backend, `query.join.mergeslices` 1 (subjects only, one thread), 2, 4 and 8. Reports `rocksdb-20261117-201357` (the whole suite), `accumulo-20261117-203103` and `hbase-20261117-204751`:

| Query (rows) | RocksDB 1 / 2 / 4 / 8 | Accumulo 1 / 2 / 4 | HBase 1 / 2 / 4 |
|---|---|---|---|
| `class-2` (312) | not merged on RocksDB | 40.0 / 26.3 / **20.4 ms** | 48.3 / 36.4 / **25.0 ms** |
| `class-3` (312) | not merged | 89 / 69 / 69 ms | 52 / 36 / **29 ms** |
| `class-attrs` (361) | not merged | 91 / 70 / 71 ms | 42 / 29 / **23 ms** |
| `class-large` (14,379) | 93.5 / 60.4 / **29.9** / 21.9 ms | 109 / 83 / **67 ms** | 162 / **100** / 124 ms |
| `class-count` (1) | 92.6 / 60.3 / **28.9** / 18.6 ms | 103 / 78 / **59 ms** | 132 / 90 / **76 ms** |
| `class-value-mid` (5,233) | 429 / 291 / 291 / 297 ms | 2,632 / 2,517 / 2,516 ms | 969 / 828 / 888 ms |
| `class-value-wide` (51,116) | 382 / 270 / 269 / 270 ms | 2,676 / 2,579 / 2,588 ms | 1,122 / 1,137 / 1,118 ms |

**What it shows:**
- **Subjects only:** before O24 the same merges took 172 ms (RocksDB) and 176 ms (Accumulo) for `class-large` in the profiling runs; with one slice now 94 and 109 ms. The runs differ (another build shared the machine during some of them), so this is indicative; the slices below are compared within one run.
- **Slices:** with 4, merges are 1.6 to 2 times faster on Accumulo and HBase, where they wait on the servers, and 3 times on RocksDB, where they are CPU on the client; RocksDB gains up to 4.3 times with 8. The value stars gain 0 to 32 %: most of their time is the lookups after the merge.
- **The handoff matters:** a first version handed every match over one at a time. With 14,379 matches the query thread, waiting on an empty queue, paid a park and an unpark per match: total CPU rose 45 % and 2 slices were slower than 1 on RocksDB. Chunks of 32 doubling to 1,024 removed that: on RocksDB the total CPU now rises 9 % with 4 slices.
- **Hence 4 slices by default** on every backend: the best or close to it everywhere, and a bound on the threads one query takes on a busy endpoint.
- **Rows** were the same in every variant. Sliced merges read a few cells fewer: each slice stops reading as soon as one of its lists runs out. The other 18 queries read exactly the same cells (RocksDB, whole suite).
- **At 10^12** this divides the time by a constant: the lists still all cross to one client. O23 is the answer there.

### 4.10 Stars from the entity index (O23)

The benchmark store with the entity index (`bench.sh --entity-index`: built on the kept RocksDB store in 18 s, loaded with it on Accumulo; 16 shards). Three variants of one run per backend:
- **po:** without the index (`query.useindex=false`): O22 and O24's merge of `po` lists.
- **client:** shards intersected on the client (`query.entityindex.servers=false`).
- **store:** shards intersected by the store: in Accumulo's tablet servers, with RocksDB's native seeks.

`class-2` and `class-3` from reports `rocksdb-20261118-065152` and `accumulo-20261118-071358`; the rest from `rocksdb-20261118-071754` and `accumulo-20261118-073754`, the final code. p50, and the cells the query reads:

| Query (rows) | RocksDB po / client / store | Accumulo po / client / store | Cells, po / store |
|---|---|---|---|
| `class-2` (312) | 8.6 / 8.4 / **3.5 ms** | 19.6 / 18.2 / **7.8 ms** | 49,686 / 314 |
| `class-3` (312) | 14.4 / 14.0 / **5.1 ms** | 68.8 / 67.5 / **17.8 ms** | 49,999 / 315 |
| `class-attrs` (361) | 27.2 / 27.5 / **8.0 ms** | 69.3 / 69.8 / 68.7 ms | 50,646 / 962 |
| `class-large` (14,379) | 29.8 / 24.2 / **24.3 ms** | 62.4 / 55.3 / **24.5 ms** | 247,657 / 14,382 |
| `class-count` (1) | 28.9 / 22.9 / **22.5 ms** | 54.2 / 52.2 / **23.2 ms** | 247,657 / 14,382 |
| `class-value-mid` (5,233) | **251** / 279 / 278 ms | **2,464** / 2,580 / 2,602 ms | 362,784 / 122,350 |
| `class-value-wide` (51,116) | **259** / 283 / 282 ms | **2,452** / 2,572 / 2,593 ms | 362,784 / 122,350 |

Cells are Accumulo's; RocksDB's `po` variant reads fewer on the small stars (2,846 and 5,379), because its lower merge ratio looks up the long lists instead.

**What it shows:**
- **Stars and counts:** 2.3 to 4 times faster when the store intersects, on both backends, and 15 to 160 times fewer cells cross to the client. `class-attrs` on Accumulo gains nothing: its time is the lookups of the 361 matches' attributes.
- **Value stars:** 5 to 10 % slower. 56,000 matches are each looked up for their value, and that is most of the time. The intersection itself is cheap, but its matches come in hash order. They are sorted before the lookups, in runs (below), and the query thread waits for the second run to fill.
- **The client intersection** gains little on its own: it reads the same postings as the `po` lists.
- Rows were the same in every variant.

**Three faults the first runs showed** (reports `…-20261118-021952` and `…-024319`, fixed since):
- **RocksDB read a whole shard row for one term.** A one-family scan of one row read every cell of the row and filtered: the client intersection of `class-large` took 1,897 ms. `RocksScan` now seeks to the family's keys.
- **Matches in hash order scattered the lookups.** Shards are hash partitions, so matches come in no useful order across them. On Accumulo, `class-value-mid` took 6.3 s instead of 2.4 s: each scattered lookup reads and decompresses a block of its own. Matches are now sorted by subject when a join or an OPTIONAL follows the merge. There is a first run of 1,024, so a `LIMIT`'s first rows come early, then runs of up to 262,144. Runs doubling from 1,024 were still 2 times slower on Accumulo.
- **The threads were drained one after the other.** While the query thread read the first group of shards, the others stopped after 8 chunks. They now share one queue, since their order doesn't matter.

**Counts by the store** (`SubjectMergeCount`): a count of a whole star is `Store.countIntersection`, one call per group of shards on 4 threads. On Accumulo the iterator counts in the servers, and one cell per shard comes back. On RocksDB the matches are summed in the process. Reports `rocksdb-20261118-093328` and `accumulo-20261118-094955`; `class-3-count` is new, `class-3` counted, a selective intersection of long lists:

| Query (rows) | RocksDB po / client / store | Accumulo po / client / store | Cells, po / store (Accumulo) |
|---|---|---|---|
| `class-count` (1) | 30.3 / 23.1 / **23.6 ms** | 51.8 / 52.0 / **18.9 ms** | 247,657 / **19** |
| `class-3-count` (1) | 14.8 / 14.8 / **5.0 ms** | 61.1 / 62.9 / **14.7 ms** | 49,999 / **19** |

- On Accumulo, counting in the servers beats returning the matches: 18.9 ms against 24.9 for `class-large`'s 14,379 matches. At 10^12 that is the difference between one cell per shard and a cell per matching entity.
- **The first version was slower than the merge:** one call for all the shards, 53.5 ms on Accumulo and 83.6 on RocksDB, all on one thread (Accumulo's batch scanner uses `query.numthreads`, 2). Counting the groups of shards in parallel, as the merge reads them, fixed it.

**One pattern counted by the store** (2026-11-18): a class's size, `count-class` (`SELECT (COUNT(*) AS ?n) WHERE { ?s a o:Sensor }`), is one pattern, never a merge, so until then the client read and counted its 53,432 `po` cells. A group over one star pattern is now counted like a star's, its matches being its postings; never with a dataset or `query.limit`, which change what it counts. Reports `rocksdb-20261118-125953` and `accumulo-20261118-131618`, `po` and `store` variants:

| Query (rows) | RocksDB po / store | Accumulo po / store | Cells, po / store (Accumulo) |
|---|---|---|---|
| `count-class` (1) | 26.3 / **4.4 ms** (−83 %) | 45.9 / **11.6 ms** (−75 %) | 53,432 / **17** |

The Accumulo `store` variant's p90 was 45.4 ms, against 54.7 for `po`: a few slow runs, on a test cluster whose servers share the machine. At 10^12 a class of billions is counted in the shards' servers, and one cell per shard comes back.

**Write and storage cost.** Fresh loads of the 10M dataset, the same day, without and with the index (RocksDB in a new directory each; Accumulo a new test cluster each). Sizes are the tables' files after the load's compaction, one copy, compressed (`TableAdmin.diskUsage`):

| | RocksDB, without / with | Accumulo, without / with |
|---|---|---|
| Load rate | 125,715 / 104,635 triples/s (−17 %) | 14,121 / 11,203 triples/s (−21 %) |
| Load and compaction | 113 / 137 s | 784 / 984 s |
| `entity` | 464 MB, 1.06 times `po` (438 MB) | 233 MB, 1.10 times `po` (212 MB) |
| All tables | 2,786 / 3,250 MB (+17 %) | 1,341 / 1,574 MB (+17 %) |

So the estimate holds: about another copy of `po`, 23 B per triple on Accumulo, about 23 TB at 10^12 (one copy), and a fifth of the load rate. Building it on a loaded store took 18 s on RocksDB (`index.rebuild`).

**Open: shards by subject range instead of hash.** At 10^12 a sorted run of 262,144 matches still spreads a window of 1,000 lookups over about 1/262 of the subject space. Shards cut at subject boundaries, as `spo`'s split points cut O24's slices, would give a merge global subject order with no sorting. That costs balance: the boundaries are fixed when the index is built, while hash shards stay even as the data grows. To be decided with the measurements at scale (M3). The 100M run (4.11) shows the effect already, but only while lookups miss the cache.

### 4.11 A step up: 100M triples, and two fixes it showed

Every figure above is at 10M triples, a store that fits in memory. On 2026-11-18 the benchmark ran on a store ten times larger, bigger than the development machine's memory (24 GB, NVMe disk), with the same code (`main` at `4ba0faf`) and the same flags as a 10M run of the same day: `bench.sh --triples 100M --entity-index --variant po:query.useindex=false --variant store:`, RocksDB, in `tripatha.loader/target/bench-100m` (`BENCH_ROCKSDB_PATH`, with `--out` to the same directory). The store is deleted after each round of runs, so every round loads it afresh: it doesn't fit beside much else on the development disk.
- **The store:** 13,357,823 entities, 99,979,596 triples, 31.5 GB on disk against 3.3 GB at 10M: spo 5,020 MB, po 4,240, osp 4,561, eval 4,638, text 8,383, geo 193, entity 4,450 (1.05 times po).
- **The load:** 42 minutes, 39,681 triples/s against 104,635 at 10M. The disk was nearly full and space was freed during the load, so that isn't a scaling figure. The store held 37 GB at 91 % of the load, before its compaction.
- **Reports:** 10M `rocksdb-20261118-174040`; 100M `bench-100m/rocksdb-20261118-185126`, then `-185358` (slices), `-190056` (the cap) and `-190656` (with `iostat`).

**10M → 100M, same code** (p50; the `store` variant unless noted):

| Query | Rows | p50 | What grew |
|---|---|---|---|
| `lookup`, `chain-limit`, `type-limit` | 8, 10, 100 | 0.6 → 0.5, 0.6 → 0.5, 1.3 → 1.6 ms | nothing |
| `range-date`, `prefix`, `range-double` | 5 → 49, 521 → 5,190, 922 → 9,262 | 0.5 → 0.5, 0.6 → 2.9, 1.2 → 6.2 ms | the range: 6.5 to 10 times the cells |
| `text`, `geo` (indexes) | 78 → 659, 13,432 → 114,433 | 2.0 → 17.8, 135 → 1,388 ms | 8 to 10 times the rows |
| `class-2`, `class-large` | 312 → 2,041, 14,379 → 143,823 | 3.2 → 10.7, 25.6 → 114 ms | 6.5 to 10 times the cells |
| `class-count`, `count-class` | 1 | 25.2 → 115, 5.3 → 32.6 ms | 10 times the cells |
| `star-in`, `chain`, `star-optional` | 7,281 → 44,734, … | 72 → 436, 19 → 198, 19 → 162 ms | 6 to 7 times the lookups |
| `class-large` and `class-count`, `po` | 14,379 → 143,823 | 31 → 895, 31 → 890 ms | **29 times, for 10 times the cells** |
| `class-value-int` | 201 → 2,138 | 15.3 → 547 ms | **36 times, for 10 times the rows** |
| `class-value-mid`, `po` / `store` | 5,233 → 52,537 | 273 → 10,884 / 296 → 16,589 ms | **40 to 56 times** |

**What held:** selective queries don't depend on the store's size; ranges, prefixes and the indexes' searches grow with what they read or less; stars and counts from the entity index grow slower than their cells. **What didn't** had three causes.

**1. Lookups that miss the cache** (measured). In the first runs `class-value-mid`'s 574,058 lookups took 19 µs each, against 4.8 µs at 10M, and `iostat` showed about 19,000 disk reads a second (360 to 500 MB/s) through the timed runs: the store no longer fitted in the page cache. In later runs the cache held more of it (about 640 reads a second), and the same plan took 2.8 s, 4.8 µs a lookup. So the store's size doesn't make a lookup dearer, a miss does: about 4 times on a local NVMe disk, in process. At 10^12 most lookups miss (section 5.8), so queries bound by lookups (joins, value stars, attributes) should be planned with the missing figure. Two effects showed only while lookups missed:
- the joins grew 1.0 to 1.5 times faster than their lookups, against 0.9 to 1.3 times with the cache warm;
- `class-value-mid` from the entity index was 50 % slower than from `po` (16.6 against 10.9 s). Its 560,000 matches exceed one sorted run (262,144), so its lookups probably make several passes over the subject space (not tested: the run size has no setting). With the cache warm the two are within 7 % (`class-value-wide`, the same plan). That is the open question of 4.10: order matters most where lookups go to disk.

**2. A fixed cap in O25's estimate** (fixed). At 100M `class-value-int`'s range holds tens of thousands of cells, past the cap of 10,000, so it was estimated as its whole predicate and lost the plan. The count now stops at the estimate of the shortest other pattern of the join: a range holding that many cells is at least as long as that pattern and wouldn't drive, so counting further only costs time. `query.valueranges.count` becomes the ceiling (1,000,000), which bounds what a count reads to the client. Variants of one run, relative cap against the old fixed one (`query.valueranges.count=10000`). Reports `bench-100m/rocksdb-20261118-195356`, `rocksdb-20261118-195416`, `accumulo-20261118-201133`:

| Query | RocksDB 100M, fixed → relative | RocksDB 10M | Accumulo 10M |
|---|---|---|---|
| `class-value-int` | 510 → **224 ms** (98,032 → 52,900 ranges) | 16.4 → 15.9 ms (same plan) | 266 → 268 ms (same plan) |
| `class-value-mid` | 2,769 → **1,865 ms** | 284 → **174 ms** | 2,441 → **2,147 ms** |
| `class-value-wide` | 2,786 → 2,996 ms (+7.5 %) | 284 → 298 ms (+5 %) | 2,432 → 2,462 ms (+1 %) |

- `class-value-mid` now gets its range plan at 10M too (the cap's cost in 4.8): 39 % faster on RocksDB and 12 % on Accumulo, where lookups cost most.
- The price is on conditions most values pass: their count reads up to the shortest other list (564,000 more cells at 100M) before giving up, 1 to 7.5 %.
- **At 10^12** a ceiling of a million is too few: ranges of billions need an estimate the store makes, by counting in the servers or from a sample.

**3. Merge slices that stopped running in parallel** (fixed, O24). Without the entity index, `class-large`'s 4 slices were 11 % faster than one at 100M (885 against 990 ms), against 3 times at 10M. Each slice's queue holds 8 chunks, about 4,000 matches, and the slices were emitted in subject order: at 36,000 matches a slice, the three later ones filled their queues and waited for the query thread to reach them. Now, when no lookups follow the merge, every slice feeds one queue, as the entity index's shards do, and the rows come in chunks as they are ready. When lookups follow, the slices are still emitted in order: the lookups are the slow part, and they read nearby rows. Report `bench-100m/rocksdb-20261118-195109`, 4 slices against 1:

| Query (rows) | Before (4 / 1 slices) | After (4 / 1 slices) |
|---|---|---|
| `class-large` (143,823) | 885 / 990 ms | **258** / 943 ms (3.7×) |
| `class-count` (1) | 867 / 982 ms | **260** / 939 ms (3.6×) |

On Accumulo at 10M, where the slices' queues never filled, 4 slices still beat 1: 63.5 against 110 ms.

**The whole suite after both fixes:** at 10M (`rocksdb-20261118-194151` against `-174040`) every query returns the same rows and reads the same scans, ranges and cells except `class-value-mid` (its new plan) and `class-value-wide` (its longer count). At 100M (`bench-100m/rocksdb-20261118-194955`) a before/after comparison is confounded by the cache: `class-value-wide`, with the same plan before and after, went from 12.1 to 2.8 s. The variants above, timed alternately in one run, are the measurements of the fixes.

### 4.12 A class on its own, read as a merge of one list (O7a)

A star pattern that no other list joins was read as one `po` range through one scanner, every cell decoded into a statement on the query thread. Such patterns are a whole class (`SELECT ?s { ?s a o:Sensor }`), its count without the entity index, or a class driving a chain of lookups. Without the entity index, at 100M triples, a class of 534,313 members took 270 ms, all of it CPU on that thread.

The pattern is one subject-sorted list, which merges already read well (O22, O24). Since 2026-11-19, a star pattern evaluated once per query whose `po` counter is at least `query.join.mergesingle` (10,000) is a merge of one list (ARCH.md 13.6, item 7): its subjects only, in up to 4 slices at `spo`'s split points, in subject order when lookups follow. The variable-object shape (`?s :p ?o`, O7b) is left for later.

Two queries were added to the benchmark:
- `class-scan`: `SELECT ?s WHERE { ?s a o:Sensor }`;
- `class-label`: the same with each member's `rdfs:label`, one lookup per member.

They were run as variants of one run:
- `off`: `query.join.mergesingle=0`;
- `on`: the default;
- `index`, on RocksDB: the entity index used. `off` and `on` don't use it.

The reports are `rocksdb-20261119-072427` (10M, the whole suite), `bench-100m/rocksdb-20261119-080233` (100M) and `accumulo-20261119-081907` (10M).

| Query (rows at 10M / 100M) | RocksDB 10M, off → on | RocksDB 100M, off → on | Accumulo 10M, off → on | `index`, RocksDB 10M / 100M |
|---|---|---|---|---|
| `class-scan` (53,431 / 534,313) | 27.5 → **9.9 ms** (2.8×) | 279.5 → **57.4 ms** (4.9×) | 39.2 → **17.1 ms** (2.3×) | 5.5 / 42.6 ms |
| `count-class` (1) | 27.2 → **12.7 ms** (2.1×) | 260.9 → **60.9 ms** (4.3×) | 38.9 → **17.4 ms** (2.2×) | 8.2 / 31.5 ms (the store counts) |
| `class-label` (53,431 / 534,313) | 265.6 → 231.9 ms (−13 %) | 2,676 → 2,250 ms (−16 %) | 2,370 → 2,393 ms (+1 %) | 242 / 2,513 ms |

- **Where the time went:**
  - Off, the query thread did all of it: `class-scan` at 100M used 279 ms of its CPU in 279 ms.
  - On, it uses 47 ms, and the slices' threads read the subjects.
  - At 100M the gain is more than 4 slices give. The subjects aren't decoded into statements either, which is O24's gain for merges, now for one list.
- **With lookups following,** the gain is the list's share of the time: 13 to 16 % on RocksDB. On Accumulo there is none: the 53,431 lookups are server round trips.
- **The rest of the suite,** at 10M:
  - reads the same scans, ranges and cells with the setting on and off;
  - every query returns the same rows in every variant;
  - `type-limit` (`?p a o:Person` under `LIMIT 100`) keeps its single scan, since the limit is shorter than the setting.
- **The entity index** stays faster where it applies (5.5 against 9.9 ms at 10M): it reads one term's postings shard by shard, and the store counts.
- **At 10^12,** a class of billions read by one client is bound by what the client can absorb (M4), however it is sliced. So this is a constant factor, not a change of scale. Counts belong to the store (O23) or to the counters (O8).
- **The 100M run began by loading the store again.** The benchmark kept its "loaded" marker in its report directory, and another directory was given. So it deleted the store kept from 2026-11-18 and loaded the same dataset afresh, in 35 minutes.
  - The queries thus ran on a fresh, compacted load: its tables have the sizes of the day before, to the megabyte.
  - An earlier version of this note said the counters were doubled. They weren't: the old tables were deleted before the load, as the run's log says.
  - The record of a finished load is now in the store itself (table `bench_loaded`), and `bench.sh` reads relative paths from the directory it is run in.

## 4.13 Against QLever and Oxigraph, on WatDiv (M11)

Measured on 2026-11-19 with `tripatha.loader/compare.sh` (its README has the setup), on the code of `main` at `9bbd86a`. The comparison uses a standard benchmark only, WatDiv, which none of these engines was tuned for. The join benchmark (M8) measures Tripatha's own optimizations and isn't used against other engines.

**The engines,** on one machine (12 cores, 24 GB, macOS, NVMe), measured **one at a time**: each one's server alone on the machine, then stopped before the next starts.
- Tripatha's endpoint as its native executable (`tripatha.standalone`, GraalVM native-image 25, the Serial garbage collector, `-Xmx6g`), RocksDB inside, with the entity index;
- QLever 0.6.0 (Homebrew, `-m 6G`), a single-machine engine built for large analytical queries;
- Oxigraph 0.5.13, an embedded store on RocksDB, read-only.

Every query is a SPARQL protocol POST whose whole TSV result is read, over the loopback. Each engine first runs every query once, then 3 warm-up and 10 timed runs of each, with a warm cache. QLever's result cache is emptied before each of its runs; checked by hand, C3's compute time drops from 33 to 2 ms when cached and comes back after a clear.

**The benchmark:** WatDiv v0.6 (G. Aluç, O. Hartig, M. T. Özsu and K. Daudjee, *Diversified stress testing of RDF data management systems*, ISWC 2014) at scale factor 100: 10,867,277 distinct statements, and 5 queries from each of its 20 basic templates (linear L1–L5, star S1–S7, snowflake F1–F5, complex C1–C3).
- WatDiv publishes no license, so it isn't in this repository: the script downloads it from its publisher, checks it and builds it.
- It seeds from the clock, so these figures hold for this generated dataset.
- All three engines held the same statements, and all 100 queries returned the same rows on all three.
- S3, S5 and S7 return no rows on any engine (all 15 queries). Their constants exist but don't occur together: ProductCategory7 has 1,620 members and none in Language0; User45120 likes nothing. They weigh on the small-query means as fast empty answers.

**Geometric mean of the p50s** (ms):

| Queries | Count | Tripatha | QLever | Oxigraph |
|---|---|---|---|---|
| All | 100 | **3.8** | 10.1 | 4.0 |
| Linear | 25 | 1.9 | 3.8 | **1.5** |
| Star | 35 | **1.6** | 6.6 | 2.2 |
| Snowflake | 25 | 2.1 | 15.7 | **1.6** |
| Complex | 15 | 263.6 | **64.8** | 403.0 |

**Per template** (the median of its 5 queries' p50s, ms):

| Template | Rows | Tripatha | QLever | Oxigraph |
|---|---|---|---|---|
| L1 | 0–3 | 0.6 | 4.2 | **0.5** |
| L2 | 12–212 | 18.3 | **4.6** | 14.7 |
| L3 | 9–26 | 0.5 | 2.3 | **0.4** |
| L4 | 46–66 | 1.8 | 2.8 | **1.7** |
| L5 | 49–275 | 7.2 | **6.4** | 7.2 |
| S1 | 0–9 | 2.4 | 72.3 | **1.5** |
| S2 | 16–128 | **1.1** | 3.8 | 1.6 |
| S3 | 0 | 12.9 | 6.2 | **5.1** |
| S4 | 0–5 | 5.9 | **3.4** | 47.9 |
| S5 | 0 | **0.4** | 5.6 | 3.6 |
| S6 | 0–16 | 2.6 | 3.0 | **1.5** |
| S7 | 0 | 0.3 | 5.0 | **0.2** |
| F1 | 0–10 | **3.5** | 9.6 | 9.6 |
| F2 | 1–7 | 1.6 | 21.7 | **1.2** |
| F3 | 1–2 | 0.8 | 10.8 | **0.5** |
| F4 | 0–60 | 5.3 | 41.2 | **2.3** |
| F5 | 13–51 | 1.6 | 10.2 | **1.2** |
| C1 | 26 | 39.0 | 21.4 | **14.2** |
| C2 | 6 | 121.2 | **36.7** | 282.2 |
| C3 | 444,070 | 3,871.5 | **345.8** | 16,497.4 |

**Loading** (each engine's own loader, from the same file):

| | Load | On disk |
|---|---|---|
| Tripatha | 99 s | 1.01 GB |
| QLever | 11 s | 0.28 GB |
| Oxigraph | 11 s | 1.22 GB |

Tripatha's figures include its statistics counters and the entity index. It writes through the DAO, as a live store does, with 8 threads (`RdfFileLoad`). QLever and Oxigraph build from a file offline.

**What it says:**
- **Small, selective queries:** Tripatha and Oxigraph are close. On 9 of the 17 linear, star and snowflake templates they are within half a millisecond. Oxigraph is ahead by 3 to 8 ms on L2, S3 and F4, and Tripatha by 3 to 42 ms on S4, S5 and F1. Oxigraph wins 12 templates and Tripatha 3, but Tripatha's geometric mean over all 100 queries is the lowest.
- **An earlier run** had the JVM build of the endpoint, with the three servers running side by side and taking turns. There Tripatha was 1 to 2 ms behind Oxigraph on small queries (geometric mean 6.2 against 4.8). Both things changed at once, so this run doesn't say how much each was worth.
- **Large intermediate results:** QLever wins, 3 to 11 times faster than Tripatha on C3, C2 and L2, and 1.8 times on C1.
  - It works on IDs from a dictionary and merges whole sorted lists. That is the direction of O11 (a dictionary) and O22 to O24 (merges).
  - On C3 (444,070 rows) the native executable takes 3.9 s, where the JVM build took about 2 s in the earlier run: it has no JIT compiler, and its Serial garbage collector handles a large result worse. Tripatha is still second there, 4 times faster than Oxigraph.
- **QLever's floor is higher:** a selective star or snowflake costs it 20 to 70 ms (S1 72, F4 41, F2 22), against 1 to 5 for the others, since it scans each pattern's whole predicate.
- **Oxigraph** is fast on point lookups and short joins, and slow on what reads long lists: S4 48 ms, C3 16.5 s.
- **At 10^12:** QLever and Oxigraph are single-machine stores, so this compares per-query engines at 10M, not designs at scale. The gap that matters carries over, though: large answers, where the cost per row decides. That is O11's and O12's question (section 5).
- **Report:** `tripatha.loader/target/compare/compare-20261119-154501`, and each engine's results in `runs/20261119-151920`.


## 4.14 Where Tripatha's time goes on WatDiv's complex queries (M12)

Measured on 2026-11-19 with `QueryProfile`: one query in-process on the JVM, through the Sail as the endpoint runs it, on a copy of section 4.13's WatDiv store. It times reading every row, and reading them and writing them as TSV; it groups the store's scans by the reason the trace gives them; and it samples the CPU with JFR. C3 and C2 are the two templates where QLever is furthest ahead (4.13).

| | C3-01 | C2-01 |
|---|---|---|
| Rows | 444,070 | 6 |
| Reading every row (p50) | 2,042 ms | 115 ms |
| The query thread's CPU | 1,949 ms | 114 ms |
| Reading and writing TSV | 2,010 ms | 111 ms |
| Garbage collection | 22 ms | 1 ms |
| Lookups (ranges) and cells read | 200,505 and 1.9M | 29,653 and 30,567 |
| RocksDB itself, of the CPU | 1 % | 0 % |

- **The plans:**
  - C3 is a star of six patterns with variable objects. The entity prefetch drives from `dc:Location` (40,101 subjects) and looks up the other five for each one, in batches of 1,000 rows.
  - C2 is a chain of ten patterns, run as batched joins and entity prefetches.
- **Where the CPU goes, C3:**
  - matching cells to lookups (`BatchLookup.scan`, its hash maps growing, `CellId` hashing): about 30 %;
  - turning RocksDB keys back into rows (`RocksCodec.unescape`, `cell`, `escape`): about 22 %;
  - decoding statements: 11 to 13 %, plus 4 to 5 % in `checkNoReservedBytes`, a check that matters only when writing;
  - RDF4J's binding sets: about 6 %.
- **C2:** building the lookups' keys (escaping them for RocksDB, grouping them, hashing ranges) takes about 30 %, and RDF4J 25 %.
- **What it isn't:** RocksDB, writing the results (21 MB of TSV cost nothing measurable), or garbage collection.
- **It all runs on one thread:** the query thread's CPU is 95 to 99 % of the wall time.
- **One range per subject (O21)** doubles C3, to 4.1 s: WatDiv's users have more than 64 statements each, so most subjects are read twice. O21 stays off.

**What it means:**
- The gap to QLever on these queries is client work per lookup and per cell, on one thread. That is O17 (less work per looked-up row) and O16 (decoding on more threads), both dropped earlier because Accumulo's servers were the limit (4.6). On RocksDB the client is the limit.
- A dictionary (O11) would shorten the keys but keep the 200,505 lookups, and the 444,070 rows built one binding set at a time. QLever's advantage is merging whole subject-sorted lists of IDs: it keeps a predicate-subject order, which Tripatha doesn't have, so stars of variable-object patterns can't be merged here.
- **So the cheaper steps come first:** the lookup path's constant factors (O17, no format change), then decoding windows on more threads (O16), each measured with `QueryProfile` and the join benchmark. The dictionary stays M5's decision.
- **Reports:** the profiles in the scratch run of 2026-11-19, reproducible with `QueryProfile --rocksdb-path <copy of watdiv-sf100/tripatha> --query watdiv-sf100/queries/C3-01.rq`.

**After O17** (the same day), three changes to the lookup path, with no change to the stored format:
- `BatchLookup` skips its check for cells it has seen, and the objects' re-encoding that check needs, when the keys of a group share one shape and leave the object free. An entity chain's links `(s, p, ?)` are such keys: their ranges are prefixes ending at a delimiter, so they can't overlap.
- `WholeRowTripleResolver` finds a row's delimiters in one pass and reads its parts in place. An `spo` row's object and type are already one run.
- `RocksCodec.cell` remembers where a key's escaped `00` bytes are, and doesn't scan the key twice.

| | Before | After |
|---|---|---|
| C3-01 in-process, JVM (`QueryProfile`) | 2,042 ms | 1,378 ms (−33 %) |
| C2-01 in-process, JVM | 115 ms | 107 ms (within noise) |
| WatDiv C3, the native executable alone (`compare.sh`) | 3,872 ms | 2,268 ms (−41 %) |
| WatDiv's complex queries, geometric mean | 263.6 ms | 200.9 ms (−24 %) |
| All 100 WatDiv queries, geometric mean | 3.8 ms | 3.4 ms (−11 %) |

- **WatDiv:** 19 of the 20 templates are 2 to 41 % faster; S5 is 0.4 ms either way.
- **C2** stays at about 110 ms. Its lookups bind objects, so they keep the cell check. Its time is in building the lookups' keys and in RDF4J.
- **Two more changes were tried and dropped,** as no faster:
  - escaping a prefix range's start once for both of its bounds;
  - hashing `KeyRange` without `Objects.hash`.
- **The join benchmark,** two before/after pairs on the same store (`main` and this change, built in turn): within noise. The larger queries moved −3 to −19 % in one pair and −6 to +2 % in the other; the sub-millisecond ones ±30 % either way. Rows were identical.
- **Next:** C2's remaining cost is per lookup (building keys, grouping them) and RDF4J's binding sets. O16 (more threads) is the other half of section 4.14's conclusion.

**After O16** (the same day): a batch whose ranges can't overlap is read on several threads, chunks of at least 256 lookups each, the query thread reading the first (`query.join.lookupthreads`, 4 by default on RocksDB). Each figure compares 1 and 4 threads:

| | 1 thread | 4 threads |
|---|---|---|
| C3-01 in-process, JVM (`QueryProfile`) | 1,344 ms | 548 ms (2 threads 819, 8 threads 402) |
| WatDiv C3, the native executable alone | 2,371 ms | 1,189 ms (−50 %) |
| WatDiv C1 | 34.4 ms | 21.8 ms (−36 %) |
| WatDiv's complex queries, geometric mean | 213.1 ms | 150.9 ms (−29 %) |
| All 100 WatDiv queries, geometric mean | 3.6 ms | 3.6 ms |
| Join benchmark (variants of one run): `star-in`, `class-label`, `class-value-wide` | 71, 237, 266 ms | 40, 132, 158 ms (−41 to −44 %) |
| `star-optional`, `chain` | 18.9, 18.4 ms | 12.0, 15.2 ms |

- **The threads run:** C3's JVM CPU rises from 1,392 to 1,804 ms while its wall time falls, and its trace has the chunks' scans as parts.
- **Only large batches use them.** Batches under 512 lookups stay on the query thread. F4 and F1 never reach that size, and are the same with 1 and 4 threads in-process. The other queries of both suites are within noise, and every query returns the same rows either way.
- **Why 4:** 8 threads gain another 27 % on C3, but one query would then take 8 cores, which a server running several queries can't spare. The setting raises it.
- **C2 doesn't change:** its batches bind objects, so their ranges may overlap and they keep the single scan.
- **QLever** is still 3.4 times faster on C3 (346 ms). The rest is the query thread's own work: building the lookups' keys and RDF4J's joins.
- **Not done:** fetching the next window while the current one is joined (the other half of O16), and the Accumulo and HBase defaults, which stay 1 until measured on a cluster.

**Batches of several shapes** (the same day). C2's batches mix `(s, p, ?)` and `(s, p, Country5)`, so until now they kept the cell check and a single scan.
- A key's range is its bound parts in the table's order, each followed by a delimiter. So two ranges overlap only when one key's parts are a strict prefix of another's, such as `(s, p, ?)` and `(s, p, o)` with the same subject and predicate.
- A group without such a pair is now disjoint, whatever its shapes. It skips the cell check, and its chunks can go to threads. A group keeps one pattern per range, and an empty chunk is dropped. The forced-threads ITs found both cases. A chunk left empty made the batch query throw. And the keys of one range under two graph conditions (any graph, the default graph) gave their cells twice, once from each of two chunks; the same could happen to one key's two patterns created a millisecond apart, since a `RyaStatement` keeps its creation time, so it failed only now and then (once, with every index on).
- Only groups whose keys bind different numbers of parts are checked pattern by pattern.

| | Before (O16) | After |
|---|---|---|
| C2-01 in-process, JVM | 103.6 ms | 70.0 ms (−32 %) |
| L2-01 in-process, JVM | 21.6 ms | 12.6 ms (−42 %) |
| WatDiv C2, the native executable alone | 126.7 ms | 79.1 ms (−38 %) |
| WatDiv L2 and S3 | 19.0, 13.4 ms | 12.5, 7.7 ms |
| WatDiv's complex queries, geometric mean | 150.9 ms | 119.0 ms (QLever 64.8) |
| All 100 WatDiv queries, geometric mean | 3.6 ms | 2.9 ms (Oxigraph 4.0, QLever 10.1) |
| Join benchmark (`main` and this change in turn): `class-value-mid`, `class-value-int`, `class-value` | 193.0, 16.8, 6.8 ms | 94.4, 11.7, 5.2 ms |

- **The small WatDiv templates** are 2 to 27 % faster too. Part of that may be the two runs' noise, which on the native executable has been ±10 to 20 % for queries under 2 ms.
- **The join benchmark's other queries** are within noise, and every row is the same.
- **C1** (19.8 ms) is now ahead of QLever (21.4). QLever stays 2.2 times faster on C2 and 3.1 times on C3.

**Fetching ahead** (the same day, the other half of O16). An entity chain read a window of driver rows, fetched every link's lookups for it, and only then handed the rows on, so the query thread waited for each fetch. From the second window on, the next window's rows are now read as soon as the current one is handed on, and their keys built and lookups fetched on another thread, while the query thread joins the current window (`query.join.prefetchahead`; unset, on where `Capabilities.lookupThreads()` is over 1, that is RocksDB).
- **What still holds:** only the query thread holds a batch, when it moves to that window, so a window's lookups are released when the next is read and at most two windows are in memory. A window over the prefetch limit falls back to the halving, and a `LIMIT` that stops in the first window fetches nothing ahead.
- **Before measuring,** the forced-threads ITs found a duplicate in the batches of several shapes, fixed with them: two keys of one range under different graph conditions could be read by two chunks. A group now keeps one pattern per range. The "off" column below is `main` with that fix, which costs nothing: the complex queries' mean was 119.0 ms before it, 112.2 ms after.

| | Off | On |
|---|---|---|
| C3-01 in-process, JVM (`QueryProfile`) | 547 ms | 496 ms (−9 %); the query thread busy 98 ms of them |
| WatDiv C3, the native executable alone | 987 ms | 893 ms (−10 %) |
| WatDiv C2, C1 | 75.1, 19.1 ms | 62.6, 16.7 ms (−17 %, −13 %) |
| WatDiv's complex queries, geometric mean | 112.2 ms | 97.7 ms (−13 %; QLever 64.8) |
| All 100 WatDiv queries, geometric mean | 2.83 ms | 2.73 ms |
| Join benchmark (variants of one run): `class-value-mid`, `star-optional`, `star-in` | 88.1, 11.4, 39.1 ms | 79.5, 10.3, 37.0 ms (−10, −9, −5 %) |
| Join benchmark, all 25 queries, geometric mean of on/off | | 0.988 |

- **The query thread's CPU** falls the most: on `class-value-mid` from 86.9 to 31.0 ms, on `star-in` from 37.7 to 9.7 ms. What's left of the wall time is the workers reading and decoding the next window's cells, which the query thread now waits for instead of doing itself.
- **The rest** of both suites is within noise, and no query returns different rows: the WatDiv runs agree on every count, the join benchmark's variants on every row.
- **QLever** is now 1.7 times faster on C2 (36.6 ms) and 2.6 times on C3 (345 ms); C1 is ahead of it (16.7 against 21.5 ms).
- **Accumulo and HBase** keep it off: the tablet server was the limit on the test cluster (4.6), so overlapping client work with it is for a cluster to measure (M3).

## 4.15 The native executable: its collector and its build (M13)

The comparison serves Tripatha as the endpoint's native executable. On the JVM, `QueryProfile` had C3-01 at 496 ms; the native executable took 893 to 1,100 ms on the same store. That gap was the native runtime, not the queries.

**The collector.** native-image's Serial collector (the only one on macOS, and GraalVM Community's on Linux) sizes its young generation from the minimum heap.
- **Without `-Xms`,** as the comparison and the Docker image ran it, the young generation stayed at about 100 MB, however large `-Xmx` was.
  - C3 filled it every 30 to 40 ms, and each collection copied 15 to 25 MB of live windows: the entity prefetch holds up to two windows of statements.
  - Over 13 runs of C3-01 that was 458 pauses and 6.4 s of collection in 15 s: about 38 % of the time, on one thread, stopping the others.
- **With `-Xms6g`** the young generation grows to about 1.2 GB: 25 pauses and 0.4 s.
- **What didn't help:** raising the young generation's maximum (`-Xmn`, `-XX:MaxNewSize`), and the other policies (`BySpaceAndTime`, `Proportionate`). The minimum heap is what the policy sizes from.

WatDiv, native executable alone, the same build, settings in turn (p50, ms; geometric means):

| | All 100 | Complex | C3 | Small (S, L, F) | Peak memory |
|---|---|---|---|---|---|
| No `-Xms` (two runs) | 3.19, 2.83 | 116.0, 103.9 | 1,109, 1,001 | 1.42–2.11 | 401, 462 MB |
| `-Xms2g` | 3.34 | 100.4 | 948 | 1.52–2.20 | 968 MB |
| `-Xms6g` | 2.97 | 86.9 | 645 | 1.39–2.12 | 1,691 MB |
| The JVM (G1), the standalone jar on the same path | 3.61 | 73.5 | 507 | 1.80–2.41 | 1,309 MB |

- **The gain:** about 21 % on the complex queries and 38 % on C3. The small queries don't change.
- **The cost:** memory peaks at 1.7 GB instead of about 0.4 GB.
- **What follows:** `compare.sh` now passes `-Xms` equal to `-Xmx` (`TRIPATHA_MIN_HEAP`), as QLever gets `-m 6G`. The README tells servers to do the same. The Docker image keeps no fixed minimum, so small containers still start.
- **The JVM, on the same HTTP path,** was still 10 to 20 % ahead on the complex queries. Its small queries were slower, as the runs don't warm its JIT fully. So the build had room left.

**The build** (all with `-Xms6g`; the default build is `-O2`, which in Oracle GraalVM uses ML-inferred profiles):

| | All 100 | Complex | C3 | C2 | Small |
|---|---|---|---|---|---|
| Default build (two runs) | 2.85, 2.69 | 87.9, 86.8 | 753, 791 | 57.3, 55.2 | 1.24–1.91 |
| `-O3` | 2.55 | 76.4 | 590 | 54.1 | 1.20–1.68 |
| `-march=native` | 2.89 | 91.7 | 786 | 58.6 | 1.30–1.97 |
| Profile-guided, trained at 10M triples | 2.34 | 71.0 | 599 | 49.0 | 1.11–1.57 |
| Profile-guided and `-march=native` | 2.33 | 66.1 | 503 | 47.9 | 1.12–1.56 |

- **Profile-guided optimization** (`--pgo`, which implies `-O3`) is the one that pays everywhere. A second series agreed: all 100 2.88 and 2.79 → 2.50, complex 90.7 and 86.4 → 75.5. In all, 11 to 16 % on all 100 queries and 10 to 19 % on the complex ones; C3 alone varies too much between runs to say more than 8 to 22 %.
- **Its training run:** an instrumented build answers the join benchmark's 25 queries (M8) over a store of its own data, never WatDiv. Trained at 1M triples instead of 10M, the profile does as well (2.48, complex 78.2, C2 49.5), so the build script loads 1M: 15 s.
- **`-march=native`** gains nothing alone. On top of the profile it took C3 to 503 ms, but it builds for the build machine's CPU only, and QLever and Oxigraph run generic builds, so it isn't used.
- **Oracle GraalVM only.** Profile-guided optimization isn't in GraalVM Community, whose `-O3` is its `-O2` and which has no ML-inferred profiles either. CI and the Docker image build with Community, so they keep the default build; the published executable is probably slower than any row here (not measured).
- **How it's built:** `tripatha.standalone/build-optimized.sh` (opt-in, about 5 minutes after the modules) builds the instrumented executable, loads the training store, runs the suite through `CompareBench`, and builds with `--pgo`. Each build's log must show the PGO setting it was given. `compare.sh` uses it when it finds Oracle GraalVM, and its report names the build.

**Where WatDiv stands,** with what `compare.sh` now builds and runs: the 1M-trained executable and `-Xms6g`. The three engines were measured in one run of `compare.sh` with no options (23:10), one after the other, and returned the same rows for every query.

| | Tripatha | QLever 0.6.0 | Oxigraph 0.5.13 |
|---|---|---|---|
| All 100 queries, geometric mean | **2.27 ms** | 9.49 ms | 3.63 ms |
| Complex (C1 to C3) | 67.9 ms | **66.3 ms** | 375.9 ms |
| Small (S, L, F) | **1.25 ms** | 6.73 ms | 1.60 ms |
| C1, C2, C3 | 12.4, 47.3, 533 ms | 21.8, **37.9**, **353** ms | **11.9**, 271.7, 16,383 ms |
| Templates where it's the fastest | **11** | 2 (C2, C3) | 7 |

- **On the complex queries** Tripatha is within 3 % of QLever (67.9 against 66.3 ms), and QLever stays 1.25 times faster on C2 and 1.5 times on C3.
- **Oxigraph's 7 templates** are small queries it answers 0.02 to 1.9 ms sooner.
- **Noise:** this machine was slower in the evening than in the afternoon. The default build without `-Xms` took C3 893 ms at 21:10 (section 4.14) and 1,001 to 1,109 ms an hour later. Only rows of one series compare.

## 4.16 The loader at 1T (O2)

**Before:**
- `--triples 1T` was refused: sizes knew K, M and B.
- `prepare` planned 4 tablets per server, so at 1T with 100 servers about 50 GB each, which Accumulo then split online at its 1 GB default into some 160,000.
- The progress file held one bit per block and was rewritten whole at every commit: 133.6 million blocks, 17 MB.
- HBase added split points one at a time, waiting for each.

**Now, on every backend:**
- **Sizes:** `--triples 1T` and `--entities` take a `T`.
- **Splits by size:** `prepare` plans each table's split points for tablets or regions of `--tablet-size` (10G) on disk. The estimate is the cell bytes a 5,000-entity sample writes, scaled to the dataset, over `--compression` (7, what the statement tables measured in 5.1). There are at least 4 per server, so a small load still spreads; `--splits N` still asks for N.
- **The backend's own threshold:** `TableAdmin.setTargetSize` makes the same size the backend's split threshold. That is Accumulo's `table.split.threshold` and HBase's `MAX_FILESIZE`; RocksDB doesn't split. So tables don't split smaller as they grow.
- **HBase splits in rounds:** each round splits every region holding planned points at its middle one, all at once, so n points take about log2(n) waits. 40 points took 9 s on the test cluster.
  - A region just split from one holding data can't split again before a compaction, so a round where none split compacts the table and retries. Tables are empty before a load, so that doesn't happen there.
- **The progress file:** blocks are claimed in increasing order, so it holds a watermark and the few blocks done above it: a few bytes at any size, and no limit of 2^31 blocks per process.
  - Files of the earlier form are read and rewritten in the new one.
  - The new form uses another key (`load`), so an older loader refuses it instead of loading everything again and adding the counters twice.
- **`dry-run`** also sums the counters of the first `--counter-sample` entities (20K) in full, per family, and reports what a load stores and what a bulk build (O3) would. At 3M triples with a 400K sample it gives section 4.2's table exactly: 5.36 cells per triple, 89.5 % holding 1, 1.97 stored by the loader, 0.56 by a bulk build.

**The plan at 1T** (both indexes, 50 servers, `prepare`'s own estimate):

| Table | On disk, one copy | Splits |
|---|---|---|
| `spo`, `po`, `osp` | 19 TB each (20.4 B per triple) | 1,898 each |
| `eval` | 49 TB (an upper bound: counters of values that repeat are counted once per sampled entity) | 4,911 |
| `text` | 33 TB | 3,293 |
| `geo` | 1 TB | 101 |

That is about section 4.4's 2,100 tablets per statement table. At 1B and 10B every table gets the floor, 199 splits; only `geo` gets half that.

`dry-run --triples 1T` with both indexes: 870.6 B and 8.23 cells sent per triple, as O1 measured, and a 940 Mbit/s link carries about 135,000 triples/s. That is still 85 days for 1T through one link, which is R4's problem and phase 3's next items.

**Tests:**
- `TableAdminIT` on Accumulo, HBase and RocksDB: 40 points at once on an empty table, splitting a table that holds data, and the target size.
- `ProgressTest`: blocks finishing early, a file that stays small, more than 2^31 blocks, and the earlier form.
- `SplitPlannerTest`: splits by size, and 1T's plan against section 4.4.
- `LoaderOptionsTest`, and `LoaderIT` on all three backends.

---

## 4.17 The bulk spikes (M6)

Before building the bulk build (O3, O10), each backend's import was tried on its own. Files were written from this JDK 25 JVM in the store's own cell layout, then imported into live tables that already held online writes. They were read back through the Store SPI, under labels, as a normal account. These are now tests that stay: `AccumuloBulkSpikeIT` and `HBaseBulkSpikeIT` in `tripatha.test`, each on its own backend, and `RocksBulkSpikeTest` in `tripatha.rocksdb`.

| | Accumulo | RocksDB | HBase |
|---|---|---|---|
| Files | the public `RFile` writer, cells from `AccumuloStore.toMutation` | `SstFileWriter`, keys and values from `RocksCodec` | `HFile` writer with tags, cells laid out by `HBaseCodec` |
| Import | `importDirectory(…).load()`, also with a `LoadPlan`, so Accumulo doesn't read the file | `ingestExternalFile` into the table's column family, over data still in the memtable | `BulkLoadHFiles`, as the superuser |
| Labels and timestamps | kept | kept | kept: the visibility tag holds label ordinals, resolved from `hbase:labels` as HBase's import tools do |
| Counters into a table that has them | added by the combiner, whatever the timestamps | added: the file's `merge` entries apply on reads and after a compaction | coprocessor mode: added, if each import has a timestamp of its own; increment mode: hidden or replaced |
| The same counter file imported twice | counted twice | counted twice | coprocessor mode, same timestamp: counted once (the cells collapse) |
| Online deltas after the import, then a compaction | sums kept | sums kept | sums kept, even after a future import timestamp: the coprocessor stamps later deltas after the region files' newest |

**What O3 and O10 take from it:**
- **Imports are recorded per table and file,** since importing again counts again (Accumulo, RocksDB) or not at all (HBase).
- **HBase, coprocessor mode:** each import stamps its counter cells with a timestamp of its own, for example the time the import starts.
- **HBase, increment mode:** counters can only go into a table that doesn't hold them. The build refuses otherwise.
- **HBase's writer needs read access to `hbase:labels`,** to turn labels into ordinals. The account that bulk-loads needs the table rights HBase asks for. Both are what HBase's import tools need too (R10).
- **The SPI of section 6.2 stands:** a sorted-file writer per table that takes cells and counter deltas in key order, an import call, and a `BULK_IMPORT` capability. Each backend writes its native format with the codec it already has.

**Spark** (D5) wasn't tried: D5 (a) was taken with the plan, so the loader sorts. The spike is for if D5 is reopened.

## 4.18 The bulk build, part 1: the pipeline and RocksDB (O3)

**How it works.** `build` writes the dataset as sorted files in the store's own format and imports them, bypassing the write path. It runs in three phases, each resumable, and the pipeline above the storage SPI is written once for every backend.
- **Sort:** each worker writes its blocks with a DAO of its own over a store that captures the bulk tables' writes. So the cells are, by construction, the ones `load` writes: statements, the three indexes, counters and datatype markers.
  - The graph markers and metadata go to the store as `load` writes them.
  - Captured cells are combined in memory: deltas added up, a put's newest version kept. They are spilled as sorted runs per table, in RFiles used here only as a sorted, indexed, compressed format.
  - A run is recorded with the blocks it holds in one write, so a resume repeats no block and loses none. A worker merges its runs of a table once it has 64, which bounds the files the next phase reads.
- **Merge:** for each table and each row range of its split points, fixed when the merge first starts, the runs of every part are merged into one file in the backend's format (the SPI's `BulkImport`).
  - Counter totals of 1 are left out except in family `p` (D6, 6.3). The datatype markers always stay: section 6.2's rule predates them, and a missing marker makes a value range skip that datatype.
- **Import:** each file once, recorded. RocksDB and Accumulo move what they import, so a file that is gone after a failure was imported.

**RocksDB:** SST files from `SstFileWriter` with the store's codec, counters as `merge` entries, ingested with `ingestExternalFile` (M6).

**Measured at 10M triples, with the text, geo and entity indexes,** on this machine's NVMe, with 8 threads:

| | `load` | `build` |
|---|---|---|
| Time | 96 s (105,184 triples/s) | 64 s: sort 41.3 s (242,000 triples/s), merge 20.6 s, import 0.2 s |
| The store afterwards | 8.0 GB, before compaction (about 3.2 GB after) | 2.7 GB, compacted: the files are imported as written |
| The work directory | | 1.6 GB of sorted runs (about 160 B per triple), deletable after the import |

- **At 1T,** the work directory needs about 160 TB, one copy, until the import. That is about the size of the store itself.
- **The sort phase** is the rate that matters: parts on many machines each sort their share, and a tablet's merge reads only its range of every run.
- **The 10B milestone** (phase 3's exit) needs Accumulo's import (next) and a cluster.
- **100M** didn't fit on this machine's disk next to the other stores, so it wasn't run.

**Tests** (`LoaderIT`, on every backend with `bulk()`, now RocksDB):
- **A build against `load`:**
  - the dataset regenerated and compared, the counters with the build's rule;
  - the index verifier, the text and geo queries, and `verify`;
  - every table but `eval` compared cell by cell with a `load` of the same dataset under another prefix.
  - The phases run one at a time, with runs merged by a fan-in of 2, and a rerun imports nothing.
- **A crash between a spill and its record:** the resume deletes that spill and writes its blocks once.
- **Unit tests:** `RocksBulkImportTest`, and `SpillsTest` for combining across runs.

## 4.19 The bulk build, part 2: Accumulo (O3)

**How Accumulo imports:**
- **Files:** RFiles from the public writer, with each table's own configuration (its compression and block sizes). Counter deltas are cells holding the delta as decimal text, which the counter table's combiner adds to what the table holds (M6).
- **The import:** per table, one `importDirectory` with a `LoadPlan` naming each file's first and last rows, so Accumulo places the files without reading them.
- **Ranges:** an Accumulo split point is the last row of its tablet, so the merge cuts `(previous, point]` there and `[previous, point)` elsewhere (`BulkImport.splitPointEndsTablet`). Each file falls in one tablet.

**Measured at 10M triples, with the text, geo and entity indexes,** on the Accumulo test cluster (one tablet server in-process, NVMe). JoinBench ran once with `load` and once with `--bulk`, a fresh cluster each time:

| | `load` | `build` |
|---|---|---|
| Writing | 906 s (11,116 triples/s) | 70 s: sort 40.3 s (247,843 triples/s), merge 20.7 s, import 2.0 s (37 files) |
| Then a full compaction | 76 s | 61 s |
| `eval` on disk | 251 MB | **26 MB** (−90 %: the counters of 1 left out, D6) |
| The other tables | 1,323 MB | the same, to the MB |
| Eight queries of the suite | | the same rows, and p50s within 0 to 10 % |

- **The comparison:** on this cluster the write path runs at 11,000 triples/s, so the build is 13 times faster at writing and 7.5 times with the compaction.
- **The sort** is the same work on any backend: about 245,000 triples/s on 8 threads.
- **On a cluster,** parts on many machines sort their shares and merge their share of the tablets, and one process imports.
- **What the 10B milestone still needs:**
  - a real cluster;
  - a work directory on HDFS. The runs and files are read through Hadoop's `FileSystem`, but the progress files and manifests are still local files, so today the directory must be local or mounted. The Spark build (O28, section 4.28) writes to the store's own volume, S3 or HDFS, instead.

**Tests:** `LoaderIT`'s `aBuild*` tests now also run on Accumulo, against `prepare --splits 7`. Each table then has 8 tablets, so each gets a file per tablet through the load plan.

## 4.20 The bulk build on HBase (O10)

**What it writes:** HFiles in Tripatha's cell layout (`HBaseCodec`), with the column family's compression, block size and encoding. Each cell's visibility is a tag of label ordinals. The files are loaded with `BulkLoadHFiles`, as the store's user, who also writes them, so the user owns them on HDFS.

**Three findings shaped it:**
- **HBase hides label ordinals.** `hbase:labels` is filtered for everyone but superusers: the normal test account read 1 of its rows, on a cluster with `VisibilityController` and no `AccessController`, so no grant helps. And the loader refuses superuser accounts, because they see every cell. So the `labels` command, which already runs as a superuser, now stores each label's ordinal in that label's canary. The build reads the ordinals of the labels its account holds, so a label's ordinal is shown only to its holders. A label without one stops the build: it isn't held, or it was registered before canaries held ordinals.
- **Counters need a timestamp of their own** (M6). The build stamps its counter cells with its start time. A work directory left by another build is refused, so a second build can't reuse the timestamp and collapse its counters into the first one's.
- **Increment mode can't take imported counters.** A counter there is one value, and an imported cell replaces or hides it. `prepare` always writes the reference data's counters first, so even a new store's counter table isn't empty. Decided 2026-11-20: the build refuses in increment mode, and `load` serves those clusters. A cluster at 10^12 would run the coprocessor anyway.

**Dependencies:** the module needs `hbase-server`, for the HFile writer and the bulk load. Unrestricted, that brings the full Hadoop stack next to the shaded client the project uses everywhere, plus Jetty, Tomcat's JSP engine and servlets: 276 entries in the dependency tree. With those excluded there are 120: HBase's own modules on the shaded Hadoop client. Writing and reading an HFile with tags, and creating the bulk-load tool, work on that classpath alone. `tripatha.hbase` stays on `hbase-client` and finds the module with `ServiceLoader`, so the endpoint never loads it.

**Guards** (R11): rows and columns go through `HBaseCodec`, as the write path stores them: the empty row as `00`, rows over 32,767 bytes refused, and column names without `00`. Accumulo's writer now refuses keys over the 1 MB of its default key-size constraint, which imports skip.

**Measured at 3M triples, with every index,** on the HBase test cluster, in coprocessor mode. It ran at 3M, not 10M, because 10M on HBase wouldn't fit in this machine's 23 GB of free disk. JoinBench ran once with `load` and once with `--bulk`, a fresh cluster each time:

| | `load` | `build` |
|---|---|---|
| Writing | 45 s (66,291 triples/s) | 31 s: sort 20.0 s (150,215 triples/s), merge 5.2 s, import 6.1 s (38 files) |
| With `prepare` and a full compaction | 130 s | 104 s |
| Eight queries of the suite | | the same rows, p50s 0 to 41 % lower (`class-large` 21.1 → 12.4 ms) |

- **Why the gap is small here:** this cluster's write path runs in-process, with no network and no compaction backlog at 3M, so the build gains least on it. On a cluster the write path also pays the network, the write-ahead log and the compactions, which the build skips. Its counter table is a tenth of the size there too, as measured on Accumulo.

**Tests:**
- **`HBaseBulkImportIT`,** as the normal test account, in both counter modes: labelled files read back under each label, counters added up in coprocessor mode and refused in increment mode, and an unregistered label stopping the file.
- **`LoaderIT`'s build tests on HBase in coprocessor mode:** the same checks as on Accumulo and RocksDB, a cell-by-cell match with `load` included.
- **In increment mode,** `aBuildIsRefusedWhereCountersCantBeImported`. `LoaderIT` now passes the test cluster's counter mode to the loader, which had been creating coprocessor tables in both modes.

## 4.21 HBase's intersection endpoint (D14)

**What it does:** `IntersectionEndpoint` (`tripatha.hbase.coprocessor`) does in HBase's region servers what Accumulo's `EntityIntersectingIterator` does in its tablet servers. For one shard row of the entity index, it finds the subjects present under every term of a star, with the rows each gives, or only how many. `HBaseStore` attaches it to the index tables it creates and calls it once per shard row (`HBaseIntersection`), with up to `query.numthreads` calls in flight.

**Four findings shaped it:**
- **An endpoint's scanners skip the label filter.** `VisibilityController` adds its filter in `preScannerOpen`. Only `RSRpcServices` calls that hook, for client scans; `HRegion.getScanner` doesn't. So the endpoint calls the region's `preScannerOpen` on each of its scans, on the call's own thread, where HBase finds the caller. The caller's labels then apply as on a client scan, and labels it doesn't hold are dropped.
- **A shard row is too big for one call.** A shard holds about 200M triples' postings, and one call over a whole row would outlast `hbase.rpc.timeout`. So the endpoint answers in pages, and each page says where the next one starts.
  - A page ends after 16,384 matches, 4M cells read or 2 s, always between two subjects.
  - Every cell the scanner examines counts toward that limit, hidden cells included. That is why the endpoint's own filter applies the time range: HBase's would skip cells without the filter seeing them.
  - The first version charged the cell a reopened scanner seeks past, at the row's start. With an allowance of one cell, the tests' setting, the scanner spent it there and never read on: the call looped until its timeout. Now that cell isn't charged, and a guard fails a page that is granted more allowance twice without reading a cell.
- **Seeks inside a row go through a filter.** A region scanner can only seek to a row. A filter whose hint is the target column (`SEEK_NEXT_USING_HINT`, as `ColumnPrefixFilter` uses) moves it within the row. There is one scanner per term.
- **Reading ahead after a seek cost more than it saved.** With 256 cells read after each seek, `class-3` was no faster than the client's intersection: its `a o:Person` list is long and is sought once per candidate. A seek now reads one cell, doubling while a list is read in order.

**Protobuf:** HBase endpoints are protobuf services. HBase 3.0 runs them on its shaded protobuf 4.34.0, which refuses generated code newer than itself, and Homebrew's protoc is 36.2. So the code is generated once with protoc 34.0, moved to the shaded package as HBase does with its own, and checked in. protoc comes from Maven Central, checked by SHA-256 per platform. `generate-protos.sh` regenerates the code, and `--check` says whether the checked-in code is current.

**Measured at 3M triples** on the HBase test cluster, in coprocessor mode, with the entity index (16 shards). The run used `BENCH_HEAP=12g --load-threads 4`, 5 warm-ups and 20 timed runs (report `hbase-20261120-102314`). The variants are section 4.10's:
- **po:** without the index (`query.useindex=false`);
- **client:** the shards intersected on the client (`query.entityindex.servers=false`), HBase's only intersection until now;
- **store:** the shards intersected by the endpoint.

p50, and the cells reaching the client:

| Query (rows) | po / client / store | store vs po | Cells, po / store |
|---|---|---|---|
| `class-2` (115) | 10.8 / 7.1 / **4.3 ms** | −60 % | 14,929 / 117 |
| `class-3` (115) | 12.9 / 9.5 / **4.1 ms** | −68 % | 15,045 / 118 |
| `class-attrs` (135) | 12.9 / 9.9 / **7.9 ms** | −39 % | 15,286 / 359 |
| `class-large` (4,310) | 35.3 / 26.8 / **9.7 ms** | −72 % | 74,264 / 4,313 |
| `class-count` (1) | 17.3 / 11.5 / **5.3 ms** | −69 % | 74,264 / 19 |
| `class-3-count` (1) | 24.8 / 19.1 / **4.6 ms** | −81 % | 15,045 / 19 |
| `count-class` (1) | 15.9 / 8.4 / **6.2 ms** | −61 % | 16,030 / 17 |
| `class-value-mid` (1,548) | 108.3 / 113.9 / 108.7 ms | 0 % | 21,276 / 21,276 |
| `class-value-wide` (15,230) | 264.3 / 253.2 / 249.3 ms | −6 % | 122,399 / 50,272 |

**What it shows:**
- **Stars and counts** are 39 to 81 % faster than without the index, and 1.3 to 4 times faster than the client's intersection. A count returns one cell per shard and page.
- **The JVM's CPU** is lower too, the test cluster's region server included: `class-large` used 74.8 ms against 168.4 for the client.
- **Value stars** are unchanged: their time is the lookups of the matches' values, as on Accumulo and RocksDB (section 4.10).
- Rows were the same in every variant.
- **Noise:** the day's three earlier reports, with 10 timed runs (`hbase-20261120-095106`, before the read-size fix; `…-101049` and `…-101652`, after it), disagreed on `class-2` and `class-3`: from 14 % slower than the client to 73 % faster. The JVM's CPU varied as much, since this cluster runs its compactions and chores in the benchmark's JVM. The 20-run figures above agree with the most favourable of the three. The other queries' gains held in every report.
- **Not compared:** Accumulo and RocksDB, whose figures in section 4.10 come from a 10M store.

**Tests:**
- **`LeapfrogTest`:** 3,000 random rows of 1 to 4 lists with hidden cells, cut into pages of 1 to 4 matches with budgets of 1 to 6 cells, against a plain intersection. Each match comes once, and every page starts past the one before.
- **`HBaseIntersectionIT`,** as the normal test account, in both counter modes:
  - cells under labels the account doesn't hold stay hidden, whatever the request asks for;
  - pages of one match and one cell give the same matches and counts as a single page, with and without a time range;
  - only tables with the endpoint intersect.
- **The shared tests:** `EntityIndexIT.theStoreIntersectsAsTheClientWould` now runs on HBase, and `SubjectMergeIT` expects HBase to intersect and count in the store. Both pass with `-Dtripatha.test.hbaseIntersectPage=1`, which ends every page after one match and one cell.

**Not done:**
- **O26's range counts,** the other half of the endpoint planned in D14 (a): done since, as `RangeCountEndpoint` (section 4.22).
- **Delete markers:** markers not yet compacted away are skipped by HBase before the endpoint's filter sees them, so they don't count toward a page's limits.

## 4.22 Patterns whose object is never used, counted (O26)

**What it does:** a joined pattern whose object variable no other part of the query uses only decides how many times each row comes back. In WatDiv's C3, `SELECT ?v0` over six patterns `?v0 :p ?vN`, five of the six are such patterns. Their lookups now ask the store how many statements match, instead of reading and decoding them, and each row is repeated that many times. The rows are the same.
- `CountedPatternOptimizer`, the last optimizer, marks a plain pattern on the right of a join or OPTIONAL whose object nothing else names. That excludes filters, `BIND`, `VALUES`, projections (`SELECT *` included), grouping, ordering, subqueries and outside bindings. It marks nothing in a query with `COUNT(DISTINCT *)`, `DESCRIBE`, a `SERVICE`, a dataset or `query.limit`.
- A row is counted when its subject, predicate and any graph are bound. An unbound graph (`GRAPH ?g`), the default graph alone and an object bound from outside are looked up as before.
- Batched joins and entity chains fetch counts in the same batches as statements, so a window's counts are held and released like its statements.
- The store counts each range (`Store.countRanges`).
  - The SPI's default batch-scans the ranges and counts their cells, decoding none.
  - RocksDB counts with native iteration, without building cells.
- **Counted apart only where that saves the read** (`Store.countsWithoutReading`): RocksDB, and Accumulo and HBase once their servers count (below). Before they did, a window's counted lookups were read with its others, in the same scans, and counted from their lists; the rows were still repeated, not built per statement.

**Three faults the first measurements showed.** On WatDiv, C3-01 took 1,413 ms, against 536 without counts:
- **RocksDB parsed every key from its start** to find its graph and label: 62 % of the CPU went to `spo`'s long rows. The graph and the label end a key, and no `00` precedes them inside the qualifier or the label, so they are now found from the end.
- **A window's counts ran on one thread,** while the statements they replace were read on four (O16). Large groups are now counted in chunks on the lookup threads, as a disjoint group is read.

On the join benchmark, with every count a call of its own:
- **A count of its own was one more round trip per window** where the store reads to count. `star-unused` (two counted links in an entity chain) was 91 % slower on the Accumulo test cluster and 53 % on HBase's, with the same cells: Accumulo's scans went from 11 to 17. So only a store that counts without reading counts apart.

**Measured on WatDiv C3-01,** in-process on a clone of section 4.13's store (`QueryProfile`, p50 of 10 runs, the same day):

| | Counts off | Counts on |
|---|---|---|
| Reading every row | 536 ms | 337 ms (−37 %) |
| Reading and writing TSV | 556 ms | 283 ms (−49 %) |
| JVM CPU, reading | 1,833 ms | 1,005 ms (−45 %) |
| Rows | 444,070 | 444,070 |

What remains is per lookup (200,505 of them), not per cell: building each key and its range, and RDF4J's rows.

**On the join benchmark,** counts off and on, alternating on the same store (reports `rocksdb-20261120-130543`, `accumulo-20261120-132217` and `hbase-20261120-132411`; Accumulo and HBase built with `--bulk`, HBase at 3M). The queries:
- `star-unused`: an entity chain with two counted links, `o:knows ?f` and `o:email ?e`;
- `chain-unused`: a batched join whose rating is counted;
- `star-in` and `star-optional`: controls with no counted pattern.

p50:

| Query | RocksDB 10M, off / on | Accumulo 10M, off / on | HBase 3M, off / on |
|---|---|---|---|
| `star-unused` | 16.4 / 17.3 ms | 348.7 / 350.4 ms | 24.3 / 23.8 ms |
| `chain-unused` | 13.0 / 12.2 ms | 237.9 / 239.5 ms | 19.5 / 20.1 ms |
| `star-in` | 57.7 / 62.3 ms | 1,079.7 / 1,062.8 ms | 69.4 / 70.7 ms |
| `star-optional` | 17.3 / 16.0 ms | 339.3 / 337.6 ms | 20.5 / 20.4 ms |

**What it shows:**
- **Within noise everywhere.** The benchmark's counted links have one to three statements per lookup (a person's email, a few acquaintances, a review's rating), so there is little decoding to save. C3's have dozens per WatDiv user: the gain grows with the matches per lookup.
- **No loss on Accumulo and HBase**, where the counted lookups are read with the others.
- **The rows** were the same in every variant.

**Tests:**
- **`CountedPatternIT`** runs every query with counts on and off, with and without a label, and checks through the trace which ones counted. The data has subjects a pattern doesn't match (an OPTIONAL keeps them once), the same statement in a named graph and under a label (a row each), and statements outside a time-to-live.
- **`CountedPatternOptimizerTest`** covers the ways a query can name a variable.
- **`CountRangesIT`** compares counts with scans on every backend: under labels held, not held or none, graphs and a time range; for empty ranges and ranges across splits; and with a scan batch of two cells.
- **The whole suite** runs with counts on, its default, on all three backends; on RocksDB also with batches of 2, three lookup threads and fetching ahead.

**In Accumulo's tablet servers** (the second PR, the same day). `RangeCountingIterator`, in the iterator jar, counts each range a scan seeks and returns one entry: the range's last key, valued its count.
- **Why the last key:** when a batch fills, a tablet server resumes after the last key it returned. That key closes the range, so nothing is counted twice; at the first key, the rest of the range would be. `CountRangesIT`'s scan batch of two cells makes the servers resume every second range.
- **A range across tablets** gives one partial count per tablet, added up by the client.
- **The store asks once per table** whether its servers load the class, and counts on the client when they don't.
- **The count runs alongside the window's reads,** on a lookup thread. One after the other, its extra round trip was what made `star-unused` 91 % slower.

On the join benchmark, Accumulo 10M, counts off and on, both in the servers (report `accumulo-20261120-141846`):

| Query | p50, off / on | Cells to the client, off / on | Query thread CPU, off / on |
|---|---|---|---|
| `star-unused` | 354.0 / 359.2 ms (+1 %) | 12,057 / 9,343 (−23 %) | 12.0 / 8.1 ms |
| `chain-unused` | 248.2 / 246.7 ms | 6,058 / 6,058 | 10.7 / 10.9 ms |
| `star-in`, `star-optional` (no count) | within 1 % | the same | |

`chain-unused`'s counted pattern has one statement per lookup (a review's rating), so its count sends as many cells as the read did. At 10^12, a pattern with many statements per subject sends one cell per range instead of all of them.

**WatDiv, the native executable, after O26** (`compare.sh`, report `compare-20261120-144731`, the code at `70ca405`, the three engines one after the other as in 4.13; all 100 queries the same rows on all three). Geometric means of the p50s:

| | Tripatha | QLever | Oxigraph |
|---|---|---|---|
| All 100 | **2.6 ms** | 10.1 ms | 4.0 ms |
| Small (S, L, F) | **1.49 ms** | 7.26 ms | 1.73 ms |
| Complex | **56.1 ms** | 67.3 ms | 417.3 ms |
| C3 (median of 5) | **268.2 ms** | 350.3 ms | 16,976.6 ms |
| C2 (median of 5) | 50.5 ms | **38.6 ms** | 305.1 ms |

- **C3:** 533 ms in section 4.15 (before O26), 268 ms now. Tripatha now leads on it, and on the complex queries.
- **Noise:** every engine was about 10 % slower than in 4.15's run: QLever 9.49 → 10.1 ms overall, Oxigraph 3.63 → 4.0. Only ratios within one run compare.
- The README's [Benchmarks](README.md#benchmarks) section reports this run, with how to make it.

**In HBase's region servers** (the third PR, the same day). `RangeCountEndpoint`, in the coprocessor jar, goes on every statement table and counts each region's ranges in one call:
- **One region scanner per call,** over a `MultiRowRangeFilter` of the ranges, with the graphs as column prefixes and the time range. The caller's labels apply through the region's `preScannerOpen`, as for the intersections (4.21).
- **Region bounds:** a range is counted inside the region only, and the response gives the region's bounds. The client queues every part a region didn't count for the next round: past its end, before its start when its location was stale, after its page. A split or a move costs a round, never a count.
- **Pages** end between two rows, after a number of rows or milliseconds, counted by a filter that sees every row, hidden ones included.
- **The store asks each table once** whether its regions answer the count method: an older jar intersects but doesn't count.

On the join benchmark, HBase at 3M (built with `--bulk`), counts off and on, both in the region servers (report `hbase-20261120-173014`):

| Query | p50, off / on | Cells to the client, off / on |
|---|---|---|
| `star-unused` | 23.9 / 22.1 ms (−8 %) | 4,622 / 4,077 (−12 %) |
| `chain-unused` | 20.7 / 16.6 ms (−20 %) | 2,531 / 2,531 |
| `star-in`, `star-optional` (no count) | within 5 % | the same |

The rows were the same in every variant. `chain-unused` sends as many cells as before (one rating per review), but its region server counts them instead of the client decoding them.

**Next:** phase 5. D8 was decided (c) the same day, which closes O4, so the phase starts with O5's index rebuilds (section 4.23).

## 4.23 Index rebuilds as a batch job (O5)

**The problem (R6).** An index turned on for a store that has data, or redefined, was built by the store's own start-up (`index.rebuild=true`): one client thread reads all of `spo` and writes every index cell as a mutation, and the endpoint isn't up until it ends. At the 3M rate measured below on the Accumulo test cluster (181,000 statements/s), 10^12 statements would take 64 days.

**The job** (`IndexRebuild`, the loader's `rebuild` command; decided 2026-11-20: rebuilds only, `purge` stays as it is). It works on any store, whatever wrote it, and every phase resumes:
- **start:** the store, opened with the job's id (`index.rebuild.job`), marks the indexes that aren't READY as BUILDING for it, clears a redefined one, and records the job in `ns`.
- **sort:** `spo` read by split range (fixed in the work directory the first time) on threads, and processes with `--part`, with every label the account holds. Each statement's index cells go to sorted runs, recorded with their range in one write, with a sample of the range's rows.
- **merge:** the index tables are split from the samples, as `prepare` splits a store (size over `--tablet-size`, at least 4 per server, the entity index at each shard; on RocksDB at least one range per thread, as hints). Then the bulk build's merge and import (`BulkFiles`, shared with `build`).
- **finish,** once every part has imported: the deletes recorded meanwhile are replayed, then the indexes become READY.

**Writes during the job.** Adds need nothing: writers maintain a BUILDING index as they add. A delete does:
- **The race:** the sort read a statement; it is deleted; the import brings its index cells back. RocksDB keeps the last write, and Accumulo and HBase hide the cells only until a major compaction drops the delete marker.
- **The record:** so a writer that sees an index BUILDING records each `spo` cell it deletes in `ns` (`RebuildLog`: the `spo` row in the row, the graph as qualifier, the statement's own label and timestamp), and flushes the record before the delete.
- **The replay,** in two passes:
  1. Every recorded cell no longer in `spo` has its index cells deleted, at its own timestamp. One still there (added again) has them written again from the stored cell: an imported file may have replaced them with an older version.
  2. The cells recorded since the first pass read the record are deleted again if they are gone, and nothing is written. A delete recorded later came after the first pass's writes.
- **Graph drops and `purge` are refused** while a job runs: they delete by column filter, and there is nothing per statement to replay. A store started with `index.rebuild=true` leaves the indexes to a running job.
- **Redefinitions are refused** while a job runs: a store opened with other settings for an index the job builds would clear its table, and the job's files would bring back cells of the old rules. The job itself, resumed with other index options, is refused too.
- **Storage version 1.3.0:** the job's start raises the store to it, as the entity index raises it to 1.2.0. Code from before records nothing and drops graphs freely, so it refuses to write to the store. Writers already open when a job starts must run this version.

**States seen by other processes.** A store re-reads its indexes' states every minute (`index.state.refresh`), on a daemon thread and only while it is used, so it isn't part of any query's trace. Readers then use an index the job made READY without a restart, and a writer whose index another process redefined stops writing it.

**Measured** with `RebuildBench` (loader test sources): two stores built with the text and geo indexes, then the entity index built by the start-up rebuild on one and by the job (8 threads) on the other. Both wrote the same postings.

| Store | Start-up rebuild | Job | Sort / merge / import |
|---|---|---|---|
| Accumulo test cluster, 3M | 16.6 s | 5.8 s (2.9 times faster) | 3.9 s (772,000 statements/s) / 1.0 s, 16 files / 0.3 s |
| RocksDB, 10M | 18.5 s | 9.3 s (2.0 times) | 7.5 s (1.33M statements/s) / 1.3 s, 16 files / 0.2 s |

The first RocksDB run took 17.0 s: an index table created on RocksDB has no split rows, so the merge was one file on one thread (9.1 s). Hints planned from the samples cut it in 16. At 10^12 the gap is in the structure, not these ratios: the start-up rebuild is one client thread doing mutations, and the job's sort and merge spread over the machines given to it, with imports instead of mutations.

**Tests:**
- `LoaderIT.aRebuildWritesTheIndexesALoadWrites`: a store loaded without indexes, rebuilt, holds the text, geo and entity cells a load with them writes, cell by cell with timestamps. Its index tables are split, and a second run has nothing to do.
- `LoaderIT.aRebuildReplaysTheDeletesMadeWhileItRan`: between the sort and the import, a writer deletes statements, adds a third of them back later, and adds one. A graph drop, an in-process rebuild and a store with other index settings are refused, and the store is at version 1.3.0.
  - **The race, on every backend:** the index tables are compacted before the import, so Accumulo and HBase drop the deletes' markers, as a major compaction may on a cluster. The import then brings the deleted postings back everywhere (checked), and the finish removes them.
  - After the finish every index matches `spo` (`IndexVerifier`), the open writer sees the index READY, and a graph drop works.
- `LoaderIT.aRebuildResumesAfterACrash` and `IndexRebuildTest` (the weighted split points).
- All three on RocksDB, Accumulo and HBase in both counter modes: index tables hold no counters, so HBase's increment mode rebuilds too.

**Next:** O8, approximate counts from the counters (section 4.24).

## 4.24 Approximate counts from the counters (O8)

**The problem (R12).** An exact count reads what it counts. Counted in the store, as `COUNT(*)` over a class now is (one-pattern counts, then O26's counts in the servers), a class of 53,000 members costs 12 ms; a class of billions at 10^12 costs a read of billions of keys, however parallel. The counters already hold the answer, approximately, as they do for graph sizes (`stats:approxSize`).

**Two more predicates,** answered from the counters by `ApproxCountOptimizer` (the old `GraphSizeOptimizer`, which answers all three):
- `?class stats:approxInstances ?n`: each class and its approximate number of `rdf:type` statements, from the all-graphs `po` counters of `rdf:type`;
- `?predicate stats:approxTriples ?n`: each predicate and its approximate number of statements, from its all-graphs `p` counter.

**How they read:**
- **A constant subject** (`o:Sensor stats:approxInstances ?n`) reads its own counter row: one cell per label, plus HBase's not yet folded deltas.
- **A variable one** reads every class (one scan of `rdf:type`'s counter rows) or every predicate (the predicate list graph sizes keep, then one row each).
- **Labels:** under the query's labels, like every counter.
- **No counters of 1 left out:** the `po` and `p` families keep them, so the only error is the drift every counter has (section 6.3: a re-inserted statement counts twice). No inference: a subclass's members aren't counted.

**Measured** on the join benchmark, `count-class` (exact, counted in the store) against `approx-class` (the same class, from its counter):

| Store | `count-class` | `approx-class` |
|---|---|---|
| RocksDB, 10M | 12.4 ms, 53,432 cells | 0.4 ms, 1 cell |
| Accumulo test cluster, 3M | 12.7 ms, 16,030 cells | 1.2 ms, 1 cell |
| HBase test cluster, 3M | 14.1 ms, 16,030 cells | 0.9 ms, 1 cell |

The exact count grows with the class, the counter read doesn't: at 10^12 the gap is the size of the class.

**Tests:** `ApproxCountIT` on every backend, both HBase counter modes. Every class's and predicate's approximate count equals the exact `COUNT` of the stored statements, with and without a label, and with a statement in two graphs. Constant subjects, `BIND`, `FILTER` and joins work as for any pattern, a delete counts down, and a store without statistics refuses with an explanation.

**Next:** O6, text planning from per-term posting counts (section 4.25).

## 4.25 Text planning from posting counts (O6)

**The problem (R7).** An `AND` of words is driven by its smallest operand, and an operand's size was estimated by reading up to 1,000 of its entries (`PROBE_LIMIT`). Every word with more looked the same, so an `AND` of a common word and a rarer one, both above 1,000, was driven by whichever came first: at 10^12, a list of billions streamed to check a few thousand.

**The counts** (`TextCounts`), a counter table of their own, `<prefix>textcount`:
- **Keys:** one counter per word, predicate and label (row = word `00` predicate, family `n`), +1 for every posting written and −1 for every one deleted.
- **Writes:** writers sum them per write call (`IndexManager.flushCounts`), and the bulk build and the rebuild job (O5) sum them in their files.
- **Kept apart from the postings:** O5's replay re-applies postings, and counts with them would count twice.
- **Drift,** as every counter's: a statement added twice counts twice, and a graph drop doesn't adjust them (dropping everything clears them). A delete made while a rebuild's sort runs, before the sort reaches its range, leaves its word one low: the job cleared the counts, and its sort never counts the deleted statement. The planner tolerates both.
- **No storage-version bump** (AGENTS.md's VERSION rule, decided 2026-11-21): older code writing to a store with counts leaves them low, but they only steer plans, and the floor at the probe's limit bounds the harm.
- **Accounts:** readers and writers need the new table like the others; Accumulo grants each table. A reader that may not read it still searches, and plans as without the counts.
- **Where counters can't be imported** (HBase's increment mode), the rebuild job builds the text index without them.

**Complete or not.** The counts are read only when the `ns` record (`tripatha`, `index`, `text.counts`) holds the text index's current definition:
- **Written** with a store created with the index, or by a rebuild that counted.
- **Dropped** when the index is redefined or a rebuild starts.
- **A store from before the counts**, or one rebuilt where counters can't be imported, plans as before. It gets them by rebuilding the text index: delete `<prefix>text`, start the writers with the index on, and run the loader's `rebuild --text-index`.

**The planner** (`IndexSearcher`) still probes first:
- **At most 1,000 entries:** the word is small and kept in memory, as before.
- **More:** it is sized by its count, at least the probe's limit so a stale low count never beats a small operand.

`query.textcounts=false` turns this off.

**Measured** on the join benchmark's new `text-and` query: the most common comment word, then one about 20 times rarer that shares comments with it, both with more than 1,000 postings. Variants of one run, counts on and off, the same rows:

| Store | With counts | Without (the common word drives) |
|---|---|---|
| RocksDB, 10M (7,028 rows) | 112.3 ms, 23,476 cells | 291.5 ms, 161,061 cells |
| Accumulo test cluster, 3M (2,075 rows) | 212.5 ms, 8,337 cells | 270.6 ms, 49,846 cells |

**What it costs to write,** on RocksDB at 10M, the same load from `main` and from this branch, back to back:
- the load took 120 s against 111 s (+8 %, one run each);
- `textcount` holds 10 MB of the store's 2,796 MB (0.4 %), 2 MB at 3M on Accumulo.

**Tests:**
- **`TextPlanningIT`,** on every backend and both HBase counter modes: two words above the probe's limit, the common one first.
  - The rare one streams, through the trace, and the rows match the query without the index.
  - Without complete counts, or with `query.textcounts=false`, the first one drives, as before O6.
  - Counts follow deletes and a drop of everything.
- **`IndexVerifier.verifyTextCounts`:** counts against postings, per word, predicate and label.
  - Every sail test checks that no count is below its postings. Run with `-Dtripatha.test.indexes=true`, the whole suite passed (244 tests).
  - `LoaderIT` checks that `load`, `build` and `rebuild` write the same counts, equal to the postings.

**Next:** phase 6, M14 (the fixed cost per query, section 4.26).

## 4.26 The fixed cost per query (M14), and planning's reads together (O27)

**The question.** Where a small query's time goes between the request and its first scan, on the JVM endpoint and the native executable. The last comparison (section 4.13) had Oxigraph answering 7 small WatDiv templates 0.02 to 1.9 ms sooner.

**The tools:**
- **`FixedCost`** (the loader's test sources): runs query files in-process on a copy of a RocksDB store. It reports the p50 of:
  - opening the connection, parsing, the Sail's preparation;
  - planning (with the scans it makes), the run, closing;
  - the query untraced, and with its rows written as TSV.
- **JoinBench `--fixed-cost`** runs the same over the join benchmark's queries, on any backend.
- **Over HTTP,** CompareBench's `--raw` with 50 warm-up and 500 timed runs per query (the JVM: 200 warm-up), on the WatDiv store: the 85 small queries (S, L, F) and two floors. One floor is a `VALUES` query that reads nothing; the other is a lookup of a subject that doesn't exist.

**In-process** (RocksDB, the JVM, the 85 small WatDiv queries), medians:

| Stage | ms |
|---|---|
| Open, parse, prepare | 0.009, 0.034, 0.008 |
| Plan (4 counter reads, microseconds each here) | 0.027 |
| Run | 1.12 |
| Close, and writing TSV | about 0 |

Tracing adds 7 % (1.248 against 1.166 ms).

**Over HTTP,** warm (p50 in ms):

| Query | Tripatha native | Tripatha JVM | Oxigraph |
|---|---|---|---|
| `VALUES` floor | 0.138 | 0.142 | 0.079 |
| Lookup floor | 0.131 | 0.143 | 0.078 |
| L3-01 | 0.224 | 0.288 | 0.197 |
| S7-01 | 0.156 | 0.165 | 0.093 |
| S3-01 | 5.16 | 5.30 | 4.12 |
| F3-03 | 1.54 | 1.63 | 0.69 |
| F4-01 | 3.85 | 4.10 | 2.04 |
| **All 87, geometric mean** | **1.0** | **1.1** | **1.2** |

**What it shows:**
- **The fixed cost is small:** about 0.13 ms per request on the native executable and 0.14 on the JVM, against Oxigraph's 0.08. The 0.06 ms is the Java HTTP stack (Tomcat and Spring) against Oxigraph's own server. On the JVM endpoint's request threads (JFR):
  - the store took half the CPU;
  - SPARQL parsing 16 %;
  - Spring and Tomcat 9 %;
  - the optimizers 2.5 %.

  Nothing there is worth replacing.
- **Measured the same way, Tripatha is the faster on small queries:** a geometric mean of 1.0 against 1.2 ms. Section 4.13's small-query gaps came from its method (3 warm-up and 10 timed runs) and an older build.
- **Where Oxigraph is faster, it is the query's own work:** F3 and F4 about twice, S3 and S6 by a quarter or so. In F4, 579 driver rows make about 2,000 lookups. Building, escaping and hashing their keys takes about 40 % of the CPU (`RocksCodec.escape` 11 %, `BatchLookup.Group.add` 10 %, `KeyRange.hashCode` 6 %), where Oxigraph joins integer IDs. That is the dictionary question (M5, O11), not fixed cost.
- **On Accumulo and HBase, planning is the fixed cost** (JoinBench `--fixed-cost`, 3M on the test clusters):
  - each counter read is a round trip, 0.4 to 0.65 ms on Accumulo and 0.15 to 0.3 on HBase, one per pattern, one after another;
  - the median plan took 1.69 ms on Accumulo and 0.50 on HBase;
  - on Accumulo that is 46 % of `lookup` (0.46 of 1.08 ms) and 54 % of `range-number`.

**O27: the counters read together.** `RdfCloudTripleStoreEvaluationStatistics.prefetch` runs after the rewrites that change patterns (inference, index search) and before the optimizers that estimate them:
- it works out every pattern's counter as the estimate will, through one method both use;
- it reads them in one batch scan (`RyaStoreEvalStatsDAO.getCardinalities`: one row per counter, the counters' columns);
- the estimates then read that per-query map, and a counter it didn't foresee is read when asked for;
- `query.stats.prefetch=false` turns it off.

Plan times, the same queries, batch off and on:

| Store | Median plan | `star-unused` | `star-optional` | `chain` |
|---|---|---|---|---|
| Accumulo test cluster, 3M | 1.69 → 1.23 ms (−27 %) | 2.06 → 1.23 | 2.46 → 1.68 | 2.65 → 2.13 |
| HBase test cluster, 3M | 0.50 → 0.37 ms (−26 %) | 0.86 → 0.58 | 0.89 → 0.61 | 0.80 → 0.55 |

RocksDB is unchanged, and a query with one counter reads it as before. A batch scan costs more than a plain one on the test clusters, so three reads become about one and a half; on a cluster, where each read crosses the network, the batch saves more.

**Tests:** `PlanningReadsIT`, 8 query shapes (stars, a chain, a constant subject and object, `OPTIONAL`, `UNION`, `GRAPH`, a filtered type), with and without a label:
- the rows and the optimized plan are the same with the batch on and off, and the plan's text holds every estimate;
- with it on, one batch and no single read wherever two counters or more are read: 5 of the 8 at least.

**After:** the WatDiv comparison again, at the end of phase 6 (section 4.27).

**Next:** O9, deprecating `tbl.hashprefix` (D7), done the same day: its setter and resolver are `@Deprecated`, a DAO opening a store on it logs a warning, and the stores on it keep working (section 5.3). The plan's remaining items need a cluster.

## 4.27 WatDiv after phases 5 and 6

`compare.sh --build` on 2026-11-21, at commit `c1e1968`: the profile-guided native executable rebuilt, and the stores and queries of section 4.13 reused. The same statements on the three engines, and the same rows for every query. p50, geometric means, in ms:

| Queries | Tripatha | QLever 0.6.0 | Oxigraph 0.5.13 | Tripatha on 2026-11-20 (`70ca405`) |
|---|---|---|---|---|
| All 100 | **2.3** | 9.5 | 3.8 | 2.6 |
| The 85 small (S, L, F) | **1.28** | 6.79 | 1.66 | 1.49 |
| Complex | **54.7** | 65.0 | 398.7 | 56.1 |
| Fastest on (of 20 templates) | **13** (5 tied with Oxigraph) | 3 (L2, S4, C2) | 9 | 10 (2 tied) |

**How to read the comparison with 2026-11-20:**
- QLever and Oxigraph also measured 5 to 6 % faster this run, so part of Tripatha's 12 % is the machine.
- O27 batches planning's counter reads, which are microseconds each on RocksDB.
- C3 measured 290 ms against 268. Between runs on different days that is within this comparison's noise.

Oxigraph's four clear wins (S3, S6, F3, F4) are the per-lookup key work of section 4.26, the dictionary question (M5).

## 4.28 The bulk build as a Spark job (D5 (b), O28)

**Why now:** the user's cluster can't load 1B triples with the write path, and its loads start at 500M. It runs Spark 4.2 on Kubernetes, on Java 25, and Accumulo keeps its volumes on an S3 store (Ceph), with no HDFS. The loader's `build` sorts on one machine's threads; its parts would share the sort only through a local or mounted work directory (section 4.19). D5's condition now holds: Spark 4.2 runs on Hadoop 3.5, whose client works on Java 25 (Hadoop 3.4's fails there with `getSubject is not supported`), and its tasks run Tripatha's code (`SparkJava25SpikeTest`). So D5 is (b) for Accumulo, as `tripatha.spark`.

**The job** (`SparkBuild`; [tripatha.spark/README.md](tripatha.spark/README.md)):
- **The cells are the DAO's.** Each task writes its statements through a `RyaStoreDAO` over a `CapturingStore` of an empty store, which has the real store's tables and connects nowhere. So every cell is the one `load` writes: indexes, counters, datatype markers and graph markers. The executors need no Accumulo connection or credentials.
- **The files are fixed first.** The driver samples the input (20,000 entities, or about 200,000 lines read by the executors). Each table's file boundaries are its tablets' split rows, plus quantiles of the sample within each tablet, so that a file holds about `--file-size` of cells, with at most 50 files per tablet (Accumulo's `table.bulk.max.tablet.files` is 100). The entity index's shard rows are spread over files by the hash of the column. A new store is split first, as `prepare` splits one.
- **The shuffle only sorts.** Each task adds up its counters across its write calls (up to 524,288 keys), and the shuffle sorts each partition in Accumulo's key order. Each file's writer adds up the cells of one key and keeps a put's newest version. Totals of 1 are left out in the six counter families but `p`, by the build's own rule (`BulkBuild.leftOut`), and only where the totals are the store's: a generated dataset, or files into a new store.
- **The files go to Accumulo's own volume.** Executors write the RFiles under `--work-dir`, with each table's configuration. Accumulo's bulk import refuses a directory "not in the same file system as any volume configured for Accumulo" (`PrepBulkImport`), so with Accumulo on S3 the work directory is a prefix of the same bucket. Only the finished files go to S3: the shuffle's spills stay on the executors' disks.
- **Each file is imported once.** The driver imports table by table, with load plans naming each file's rows. The work directory records the job's state:
  - **The write phase** can be run again and writes the same cells: one timestamp per job, files named after their table and range, and speculation refused.
  - **Once an import starts,** nothing is written again: a file that is gone was imported.
  - **An import that started and didn't finish:** Accumulo 2.1.6's client doesn't check whether a directory's import already started (`BulkImport.checkPath` has a TODO for it), so the job refuses to import a directory that still holds Accumulo's `loadmap.json`.
- **Files** (`files`): N-Triples and N-Quads, RDF 1.2 included, read by line in parallel. A blank node's label is prefixed with its file's id (path, length, modification time), so every split of a file agrees on it. RDF4J's parser is called once per 1,000 lines, and line by line only for a chunk that fails. A bad line, or a statement the store refuses, is left out and counted, up to `--max-bad-lines`. `--distinct` drops repeated statements (by their spo cell), with one more shuffle; `--visibility` labels every statement of the job.
- **The jar** holds Tripatha, Accumulo's client and RDF4J; Spark, Scala, Hadoop and the logging are the cluster's. Spark 4.2.1's own jars carry Thrift 0.16 (Accumulo's client needs 0.24), and other Guava and Gson versions, so those are relocated (with Protobuf and Caffeine).

**Measured at 10M triples on one machine** (12 cores, Spark in local mode on 10, the Accumulo test cluster), 2026-11-21:

| | Time | Cells written |
|---|---|---|
| The loader's `build`, 10 threads | 50.8 s: sort 33.3, merge 14.4, import 1.5 | 35,426,762, plus 8 graph markers written online |
| `generate`, first version | 106.4 s: map 31.3, reduce 68.3 | 35,426,770 |
| `generate` | **63.5 s**: map 13.6, reduce 43.8, import 1.5 | 35,426,770 |
| `files`, WatDiv sf100 (10,950,947 triples, 1.55 GB of N-Triples), first version | 123.9 s: map 59.7, reduce 52.6 | 33,943,971 |
| `files` | **52.3 s**: sample 0.7, map 14.2, reduce 28.5 | 33,943,971 |

- **Two changes between the versions:**
  - Spark's own combining hashed every cell before sorting it. Only counters need adding up, so the tasks add them up and the shuffle only sorts: the reduce stage went from 68.3 s to 43.8 s.
  - RDF4J's parser, set up once per line, cost more than the parsing. Calling it once per 1,000 lines took the files' map stage from 59.7 s to 14.2 s.
- **On one machine** the Spark build costs 1.25 times the loader's build for the generated dataset, and it also loads real files, which the loader can't. On a cluster both stages spread over every executor, which one process can't do. The first cluster measurement is the user's 500M triples.
- **The same answers:** `SparkBuildIT` compares the cells table by table.
  - **Generated data:** every cell of the loader's build, on spo, po, osp and the three indexes, with timestamps. `eval`, the text counts and the graph markers are compared by value.
  - **Files:** every cell of a DAO load of the same statements. The load's counters of 1 are left out as the build leaves them out, and its datatype markers compared by presence.
  - The files cover blank nodes across splits and across files, graphs, triple terms, a base direction, a label, a repeated statement, a bad line, `--distinct`, a write that failed and ran again, and a store that holds statements refused without `--append`.
- **The smoke test** (`smoke`) is the check to run on a cluster first: each executor writes an RFile under the work directory, and the driver imports and reads them back. It passed under the Spark 4.2.1 distribution's `spark-submit`.

**Next, on the user's cluster:** `smoke`, then `generate` at 500M (M2's bulk figure, and M1's bytes on disk at that size), then a real dataset through `files` (D4 (b)).

**Open at larger sizes:** files are capped at 50 per tablet, but Accumulo compacts a tablet with more than `table.file.max` files (15 by default). With tablets of 10 GB, as at 1T, every tablet would compact after the import. At 500M the floor of 4 tablets per server keeps them to a few files each. Before 10B, take the cap from each table's own `table.file.max`, which the job already reads with the table's configuration.

---

## 5. Requirements at 1T, and where Tripatha stands

| # | Requirement | Status | Closed by |
|---|---|---|---|
| R1 | Terms stored compactly (a dictionary of fixed-size IDs, or an equivalent) | Missing, and not needed first | M9, O18 (prefix codes); M5, O11 (dictionary) |
| R2 | Joins and scans that don't funnel through one process, and read no more than they need | Partial | O13 to O17, O7, O12 |
| R3 | No hot keys | Present (pre-splitting); the hashed layout doesn't help | O9 |
| R4 | Bulk ingest | Present (O3, O10); HBase in coprocessor mode | O3, O10 |
| R5 | Statistics for join ordering, at a size that scales | Present, oversized | O1, O3 |
| R6 | Maintenance in time proportional to what it changes | Missing | O4, O5 |
| R7 | Search planning that tells common terms from rare ones | Yes: per-word posting counts (O6, 4.25) | O6 |
| R8 | Tablets and regions sized for 1T | Present (O2) | O2 |
| R9 | A loader that runs at 1T | Present (O2); its rate is R4's | O2 |
| R10 | Labels in HBase bulk files | Present (O10): tags of label ordinals, read from the canaries | O10 |
| R11 | Bulk writes checked like online writes | Present: the DAO's checks run in the capture, HBase's codec and row limit and Accumulo's key size in the file writers | O3 |
| R12 | Counts without reading the data | Yes, approximately: graph, class and predicate sizes from the counters (O8, 4.24) | O8 |

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
- **Approximate counts** for more shapes (O8: class and predicate sizes done, section 4.24);
- **parallel reads of one large range** (O7a done for a class on its own; O7b, a whole predicate, to add);
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

Apart from the configuration class that defines it, `RyaTripleContext` and `GraphSizes` are the only code that tests the flag. Hence O9, **done on 2026-11-21:**
- the setting's constant and setter, and `WholeRowHashedTripleResolver`, are `@Deprecated` with these reasons;
- a DAO opening a store on the layout logs a warning;
- the stores on it keep working: their bytes are `EncodingCompatibilityTest`'s.

**Where the hot spots really are:**
- **Large ranges** such as `rdf:type` (about 13 % of `po` in this dataset) are split into many tablets by `prepare`'s quantile split points, so their storage and their writes spread. Read through a single Scanner it was sequential, which is R2: a class on its own is now read in slices (O7a), a whole predicate still isn't (O7b).
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
  | Accumulo | the combiner adds imported cells to existing ones, whatever their timestamps | none (M6) |
  | RocksDB | the file holds `merge` entries, added on read and compaction, like online deltas | none (M6) |
  | HBase, coprocessor mode | the coprocessor sums versions | two files carrying the same counter cell with the same timestamp collapse into one cell, so each import stamps its counters with a timestamp of its own (M6 saw the collapse) |
  | HBase, increment mode | an imported value hides behind an older one or replaces it, it doesn't add (M6) | refuse bulk counters into a non-empty table |

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
- **(c) Accept a full scan per drop.** **Decided on 2026-11-20,** which closes O4.

**`purge` and index rebuilds:**
- **`purge`** keeps reading everything visible. Where the caller can see everything, dropping and recreating the tables is equivalent and constant-time.
- **Index rebuilds** become a bulk build whose input is `spo`. Done as the loader's `rebuild` (O5, section 4.23); `purge` stays as it is (decided 2026-11-20).

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
  - **Constant object (O7a), done:** a star pattern on its own is read as a merge of one list (ARCH.md 13.6, item 7), its subjects only, in slices at `spo`'s split points. Those cut any `po` list of one predicate and object by subject, and keep subject order where lookups follow. Measured in section 4.12.
  - **Variable object (O7b), later:** `?s :p ?o` is sorted by object, so subject boundaries don't cut it. It needs `po`'s own split points, and its statements decoded on the slice threads, since its object is returned.
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

**O14, comparisons (built 2026-11-17).** `<`, `<=`, `>`, `>=` and `=` against a constant number or `xsd:dateTime`, in the conjuncts of a filter, and two of them for a between.
- **Numeric types:** SPARQL compares across them, so the scan is one range per numeric type the predicate uses, each bound encoded by that type's encoder.
- **Types whose stored data sorts like the value:** `xsd:int`, `xsd:short`, `xsd:long`, `xsd:integer`, `xsd:double`, `xsd:float`, and `xsd:dateTime` (UTC milliseconds).
- **Unsafe:**
  - `xsd:decimal` and other custom datatypes store their lexical text, which doesn't sort numerically. A predicate with any such value is never narrowed.
  - `xsd:date` is one of them: it has no resolver of its own. So the generator's dates (`birthDate`, `foundingDate`, `releaseDate`, `installedOn`) are not narrowed, though `YYYY-MM-DD` without a time zone sorts as text. Narrowing them would need the markers to tell plain dates from dates with a zone or a negative year.
  - `xsd:byte` (found while building) is stored as the two hex digits of its unsigned byte, so -1 sorts after 127. Its range is all of it, which narrows nothing.

**What O14 must know:** the datatypes a predicate's objects use, without ever missing one, since a missed type would lose answers. Neither of the obvious sources will do:
- the statistics counters, because they can be turned off (`--stats off`, `tripatha.stats=false`), and a store written partly with them off would undercount;
- the row layout, because the type is the last part of a `po` row, after the subject, so the types of a predicate aren't grouped anywhere.

**The design: datatype markers (`DatatypeMarkers`).**
- **Where:** one cell per predicate, datatype and label in `eval`:
  - row: the predicate;
  - family: `t`;
  - qualifier: the datatype IRI;
  - visibility: the statement's label;
  - value: a counter delta of 1, so it works with every backend's summing.
- **When written:** by every `add`, once per call and key, as graph markers are, and whether or not statistics are on. Each is buffered before the rows it stands for.
  - That doesn't make it land first: a flush that fails can leave rows without their marker, until they are added again (the loader's resume does).
- **What removes them:** only a purge.
  - Deletes don't. A marker for a type with no values left only makes O14 narrow less.
  - `dropGraph` of everything deletes the six counter families, not `t`. A writer adding statements meanwhile may have written its marker before the drop reached it, and deleting it would hide those statements.
- **Cost:** one more `eval` cell per single-statement insert, and nearly nothing for bulk adds. That is about 45 predicates × 1 or 2 datatypes per call on the loader's data.
- **Reading:** one exact row per predicate and query, with the query's labels, so a reader learns only the types of statements it may see. A type is present when any of its visible cells holds more than zero: markers only receive +1 deltas, and HBase's increment mode resets a purged counter to zero instead of deleting it (CI's increment-mode job caught a purged type still showing).
- **Old stores:**
  - Markers are complete only for a store written by code that maintains them. So `init()` writes a flag in `ns` (row `tripatha`, family `datatypes`) and storage version 1.1.0 only when it creates the store. On any other store it writes neither: a store created earlier keeps its version (1.0.0) and stays unflagged.
  - `purge` writes back exactly the version and flag it found. Statements under labels the purger lacks stay, so an unflagged store mustn't come out flagged.
  - Unflagged stores are never narrowed, until a rebuild writes their markers (with O5).
  - A writer older than O14 must not write to a flagged store. So a DAO refuses to write to a store whose version is newer than its own (D12): every write method throws, and reads still work. Versions compare number by number.
    - Builds from before the check aren't stopped. No such writer is deployed: the project is a pre-release snapshot.
- **When a range is used:** only when every datatype the markers report is one of the order-preserving types above, and comparable with the constants (numbers with numbers, `dateTime` with `dateTime`). Otherwise the pattern reads its whole range, as before.
- **Bounds** (`ValueRanges`), computed as a superset in each type's own domain; the filter decides the edges:
  - **Rounding:** RDF4J compares two numbers in a common type, rounding both to a float or a double when either is one. The long 2^60 + 100 equals the double 2^60. So each bound is widened by 2^-20 of the constant (plus 2^-120), then floored or ceiled into the type and clamped to its range. `?x > 60` on `xsd:int` starts at 59.
  - **NaN:** RDF4J orders doubles with `Double.compare`, which puts NaN above every number, so `?v > 60` keeps a NaN. A double or float range without an upper bound runs past `INF` to the type's last data.
  - **Dates:** values are read back in UTC with a `Z`. XSD compares one with a zone-less constant only beyond 14 hours, on the side the constant's UTC reading gives, and the comparisons in between are errors, which the filter rejects. So a zone-less constant is read as UTC, with no 14-hour widening: the earlier plan to widen by 14 hours each way would have read 29 hours for a one-hour window. Bounds are widened by a second.
  - **What bounds nothing:** NaN, infinite or ill-typed constants, date-times outside the years 1 to 9999, and `!=`.
  - **What reads nothing:** contradictory bounds, or a type no value of which matches.
  - **Ranges open at both ends** (a byte's, or `?v > -1E300`) mean no ranges at all. Each range is a scan of its own and also reads, then drops, the other types' rows whose data falls in it, so such a range costs a whole scan of the predicate.
- **The setting:** `query.valueranges` turns it off. The test compares every query with it on and off.

**Where it fits:**
- `ValueRangeOptimizer`, after `IndexSearchOptimizer` and before join ordering, replaces the pattern with a `ValueRangePattern` holding its ranges.
- `RyaStoreQueryEngine` reads a `RyaValueRange` as one scan of `po`, with the type check as a row suffix, and the ranges one after the other.
- **Estimate:** first the predicate's counter times 0.1, a guess without histograms, which made a condition most values pass drive its star (section 4.8). Now (O25) the ranges of a pattern in a join are counted up to the estimate of the shortest other pattern of the join, and at most `query.valueranges.count` (1,000,000) cells: a shorter range is estimated at its count, one that reaches the cap like its whole predicate, since it is at least as long as another pattern and wouldn't drive. The cap was a fixed 10,000 until 2026-11-18, which misplanned at 100M triples (section 4.11). A ceiling of 0 restores the guess.

**Tests:**
- `ValueRangesTest` checks the bounds against RDF4J's own comparisons, strict and standard. It covers every narrowed type, integer, decimal, float and double constants, and the edges: NaN, ±INF, -0.0, the integer limits, rounding at 2^26 and 2^60, zone-less and sub-millisecond date-times, ill-typed constants.
  - Five deliberate bugs make it fail: no margin, NaN left out, a ceiled start, bytes taken as ordered, and an end excluded (through `DatatypeMarkersIT`).
- `ValueRangeIT` compares every query with the setting on and off: 9 query shapes × 6 predicates × 24 conditions, with and without a label and inference, duplicates kept. It also checks a mixed-type predicate, a type only a label reveals, and an unflagged store.
- `DatatypeMarkersIT` checks the markers per label, deletes, `dropGraph`, purge, the flag and version on new and older stores, the refusal to write to a newer store, and both ends of a range.

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
- **Parallelism (O24, built):** the subject space is cut at `spo`'s split points, and the slices are intersected on several threads at once. A star's `(p, o)` lists usually sit in one `po` tablet each, so the slices spread the work over threads and concurrent scans of the same servers, not over more servers.
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
  - **Slices (O24, built):** all of a star's lists are cut at the same subject boundaries, `spo`'s split points cut to their subjects (`TableAdmin.splitRows`: Accumulo's `listSplits`, HBase's region starts, RocksDB's split hints or the first rows of its SST files). Each slice is one ordered scan per pattern, opened on the query thread (so it is traced) and intersected on its own thread, which hands its matches over in chunks of 32 doubling to 1,024 and closes its own scans. When lookups follow the merge, the slices are emitted in subject order, so the lookups read nearby rows; otherwise all slices feed one queue. Emitted in order, a later slice could get only its queue's chunks ahead, and at 100M triples 4 slices were 11 % faster than one (section 4.11).
  - **Subjects only (O24, built):** each cell's subject is cut from the `po` row between the known `p 00 o 00` and the type suffix, and compared as unsigned bytes (code point order). No statement is decoded, and only matches become values.
  - **Scope:** patterns in the default scope or one named graph, constant predicates and objects, one shared subject variable. Patterns with unbound objects stay batched lookups over the merge's output, as entity prefetch does today.
  - **The type check:** each list is a `po` range with its object's row suffix (O20), so a value of another type with the same data is dropped on the server, as in a single lookup.
  - **Value conditions don't merge.** A prefix range (O13) or a value range (O14) of `po` is sorted by value, then by subject, so it can't be intersected as it streams. A star with a value condition is driven by the value range when that is short (O14), or merges its constant patterns and looks the value up per match. A later step could read a short value range into a set of subjects and filter the merge's output with it.
  - **RDF4J's own merge join** (6.1, experimental, `TripleSource.getSupportedOrders`) was considered and not used. It joins two inputs at a time, decides with a fixed global factor (10×) where O22 needs a per-backend ratio, relies on the triple source's value comparator matching the stored order, and marks joins as merge joins, which turns off the batched joins and entity chains above them.


**O23: the same intersection in the servers.**
- **The table** (built, phase 1): optional, `<prefix>entity` (`index.entity`), maintained by `IndexManager` like the text and geo indexes (ARCH.md 13.7).
  - Row: a shard, `murmur3(subject) mod shards`, so a subject's postings are all in one shard and the shards spread over every tablet server.
  - Column family: the term, predicate `01` object, the object as `po` encodes it.
  - Column qualifier: subject `01` graph `01` statement qualifier. The graph and qualifier make one posting per stored statement, as in `spo`: the first design, with the subject alone, would have merged a triple's graphs into one posting and miscounted the rows.
  - Visibility: the statement's label.
- **Its matches are answers,** not candidates checked against `spo` as the text and geo indexes' are. So:
  - `dropGraph` deletes the postings by the graph in their qualifier, since their family is the term;
  - turning the index on raises the store's version to 1.2.0, so older code, which would write statements without postings, refuses to write to it (the mechanism of D12);
  - it is used only when READY, and `IndexVerifier` checks it against `spo`.
- **The shard count** is part of the index's definition (a change means a rebuild). A shard is one row, which no backend splits, so about one per 200 M triples of the expected size, 16 at least: `prepare` chooses it from `--triples` and splits the table at every shard. That is 16 at 10M, about 5,000 at 10^12.
- **The work:** a star is sent to every shard at once, with a batch scan over all shard rows. In each shard a server-side iterator seeks the star's posting lists and intersects them, the way Accumulo's intersecting iterator does for documents. Only matching subjects come back; their attributes are then batched lookups.
- **Cost:**
  - about another copy of `po`, on disk and in writes (roughly 20 B per triple);
  - Tripatha code on the servers: an iterator jar on Accumulo's tablet servers, a second coprocessor on HBase. RocksDB has no servers, but its shards could be intersected on several local threads;
  - value filters need O14's ranges inside a posting list.
- **When:** decided (D13, 2026-11-17): at 10^12 the transfer of the lists is the limit, though at 10M it isn't. Built after O25 (done) and O24 (done), off by default, Accumulo first.
- **Phases:**
  1. The table and its maintenance, and merge joins reading it shard by shard on the client: correct everywhere, not yet faster (done).
  2. The intersection in Accumulo's servers: an iterator in a jar of its own, with the client intersection as the fallback when it isn't installed (done).
  3. RocksDB: the shards intersected with native seeks on local threads (done).
  4. HBase: a coprocessor endpoint doing what the Accumulo iterator does (D14 (a), done: section 4.21). A seeking filter can't do it, and a semi-join moves the shortest list whole.
  5. Counts in the servers, benchmark queries it should win (counts, selective intersections of long lists), and the measurements: query times, and the write and storage cost (done, section 4.10).

---

## 7. Plan

Re-ordered on 2026-11-16: internal optimizations first, load work deferred (phases 0 to 2). Re-ordered again on 2026-11-19: the load work first, then work in the servers of every backend (phases 3 to 6). Each phase ends green on `mvn clean verify`, and on the RocksDB and HBase IT runs. Each step is merged on its own before the next starts.

### Phase 0: counters and the benchmark

- **O1**, the loader's counters: done (PR #15).
- **M8**, the local join benchmark: done (`tripatha.loader/bench.sh`); the baseline is in section 4.6.

**Exit** (both met):
- O1 merged;
- the M8 baseline report: latencies, a profile, and scans and cells per query.

### Phase 1: the join path and filters as key ranges

In this order, each measured on M8 before and after (on RocksDB and on the Accumulo test cluster):
1. **O15**, adaptive windows: done;
2. **O13**, `STRSTARTS` as a prefix range: done;
3. **O21**, one range per subject: built and measured, off by default (no gain warm; to be tried cold, M3);
4. **M8's follow-ups**: CPU apart from blocked threads, and settings compared back to back in one run;
5. **O20**, the type check without a regex, and **O19**, RocksDB key decoding: done;
6. **M10**, class-star queries in the benchmark, then **O22**, merge joins of subject-sorted ranges, and **O24**, their slices intersected in parallel and subjects read without decoding statements: done;
7. **O14**, comparisons as value ranges, with the datatype markers and the storage-version check (D12), and **O25**, their counted estimate: done;
8. **O16** and **O17**, only if the split profile shows where the Accumulo joins' time goes;
9. **O7a**, parallel reads of one large range with a constant object (a class on its own): done; **O7b**, a whole predicate: later;
10. **O23**, stars evaluated in the servers (D13): done on Accumulo and RocksDB, with counts in the servers and benchmark queries it wins (section 4.10); on HBase a coprocessor endpoint (D14 (a), done: section 4.21).

**Exit:**
- every change has an M8 report;
- answers are identical with every optimization on and off;
- every IT is green with `-Dtripatha.test.joinBatchSize=2` and `-Dtripatha.test.indexes=true`.

### Phase 2: IRI prefix codes

M9 ran on 2026-11-16: −4 to −5 % on disk, −25 to −35 % in the cache (section 6.9). D11's recommendation is not now. O18 is deferred until a cluster measurement (M3) shows lookups bound by cache misses.

### Re-ordered on 2026-11-19: every backend, at 1T

Every optimization must hold on Accumulo, HBase and RocksDB at 10^12 triples, not only on the local RocksDB benchmark: designs above the storage SPI, or a server-side path per backend with a client fallback, and their ITs on all three. The load work is no longer deferred, since without it 1T can't be loaded. The decisions taken with this plan are in section 1 (D2, D3, D5, D6, D7, D10, D14); D8 waits for item 7.

### Phase 3: the load at 1T

1. **O2**, the loader at 1T: `--triples 1T`, split points by target tablet or region size, a progress file that isn't rewritten whole at every commit, and `dry-run` reporting stored counter cells. Done (section 4.16).
2. **M6**, the bulk spikes, all local: labelled Accumulo RFiles with counters into a live table, RocksDB SST files with merge entries, HBase HFiles with visibility tags and a counter file imported twice, and Spark on JDK 25 (D5). Done (section 4.17), without Spark.
3. **O3**, the bulk build on Accumulo and RocksDB: sorted files per tablet, every counter of 1 but `p` left out, the DAO's guards in the writer, checked cell by cell against the online path. Done for both (sections 4.18 and 4.19).
4. **O10**, the bulk build on HBase: label ordinals in the files, unique counter timestamps per import. Done (section 4.20).
5. **O28**, the bulk build as a Spark job, for Accumulo (D5 (b), decided 2026-11-21 for the user's cluster: Spark 4.2 on Java 25, Accumulo on S3). Done (section 4.28): from the generator or from N-Triples and N-Quads files.

**Exit:** the 10B bulk build on Accumulo measured, with a time model to 1T; the bulk builds of all three backends equal cell by cell to the online path.

### Phase 4: queries in the servers, on every backend

5. **HBase's coprocessor endpoint** (D14 (a)): one protobuf service on each region that counts ranges under the caller's labels and the graph condition, then intersects the entity index's lists as Accumulo's iterator does. The caller's labels are enforced and tested as `LabelParityIT` tests scans. A cluster without the coprocessor jar falls back to the client. **The intersection is done** (2026-11-20, section 4.21); the range counts come with item 6.
6. **O26**, patterns whose object is never used, counted in the servers: the optimizer and the joins on every backend, the counts in Accumulo's iterators, RocksDB's native iteration and item 5's endpoint. **Done** (2026-11-20, section 4.22): the optimizer, the joins, RocksDB's counts, Accumulo's iterator and HBase's count endpoint.

**Exit:** answers identical with O26 on and off on every backend, including `OPTIONAL`, `DISTINCT`, labels and graphs; the entity index worth turning on for HBase (README; done with item 5). **Reached on 2026-11-20:** `CountedPatternIT` on all three backends, the whole suite with counts on, HBase also in pages of one row.

### Phase 5: whole-table operations and planning at scale

7. ~~**O4**, graph drops per D8~~: closed, D8 (c) (2026-11-20): a drop keeps scanning the tables;
8. **O5**, index rebuilds through the bulk build: a resumable loader job that reads `spo` by split range and imports the index's sorted files, while writes go on (decided 2026-11-20: rebuilds only, purge stays as it is). **Done** (2026-11-20, section 4.23);
9. **O8**, approximate counts. **Done** (2026-11-20, section 4.24);
10. **O6**, text planning from posting counts. **Done** (2026-11-21, section 4.25).

**Exit:**
- an AND of a common and a rare word is driven by the rare one (reached with O6, section 4.25: `TextPlanningIT`);
- an index rebuild at 10^12 is a resumable batch job, not a start-up scan (reached with O5, section 4.23).

### Phase 6: smaller wins

11. **M14**, the fixed cost per query, on the JVM endpoint and the native executable; then what it shows. **Done** (2026-11-21, section 4.26): small apart from planning's counter reads on Accumulo and HBase, now read together (O27).
12. **O9**, deprecating `tbl.hashprefix` (D7). **Done** (2026-11-21): deprecated in the code and the docs, with a warning on open; stores on it keep working.

### On a cluster, and conditional

13. **Measurements on a real cluster** with NVMe disks (D10), Accumulo first (D3): M1, M3 and M4, then M2, and M7 with phase 5. They start at 500M triples on the user's cluster, loaded with O28. They settle the Accumulo and HBase defaults of O16 (lookup threads, fetching ahead), O21 on a cold cache, hash or subject-range shards for the entity index, and O18.
14. **M5**, the dictionary spike on a 1B-triple sample (about 400 GB of disk), then D9 and perhaps O11.
15. **On demand:** O7b when a workload needs it; O12 isn't needed under D1 (a); O18 after M3.

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
