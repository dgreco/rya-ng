# Benchmarks

Two tools in this module measure Tripatha's query performance, on a laptop and without a cluster:

- **The join benchmark** (`bench.sh`, `JoinBench`) runs a fixed suite of 25 queries over a generated store of 10M triples, in-process, on RocksDB, Accumulo or HBase. It is the yardstick for Tripatha's own optimizations ([SCALE-STUDY.md](../SCALE-STUDY.md), M8).
- **The engine comparison** (`compare.sh`, `CompareBench`) runs a query suite against Tripatha's SPARQL endpoint on RocksDB and against two other open-source engines loaded with the same data, all over HTTP (SCALE-STUDY.md, M11):
  - [QLever](https://github.com/ad-freiburg/qlever) (Apache 2.0), a fast single-machine engine;
  - [Oxigraph](https://github.com/oxigraph/oxigraph) (MIT or Apache 2.0), an embedded store that, like Tripatha here, stores its data in RocksDB.

The comparison has two suites:

- `--suite join` (the default): the join benchmark's queries and data. It was written to measure Tripatha's optimizations, so it leans towards the queries Tripatha was tuned for.
- `--suite watdiv`: [WatDiv](https://dsg.uwaterloo.ca/watdiv/), the Waterloo SPARQL Diversity Test Suite: its own data generator, and 20 query templates of four shapes (linear, star, snowflake, complex) that no engine here was tuned for.

## Installing what they need

Both platforms need a JDK 25 (with `JAVA_HOME` set), Maven, bash, curl and git, as for the rest of the build. The join benchmark needs nothing else. Oxigraph needs nothing either: `compare.sh` downloads a pinned release from GitHub, checks its SHA-256 and keeps it in `~/.m2/repository/.cache/tripatha-compare`.

### macOS

```bash
# QLever (Apple Silicon only): its official Homebrew tap. Recent Homebrew versions also ask you to trust a third-party tap.
brew tap qlever-dev/qlever
brew trust qlever-dev/qlever
brew install qlever

# WatDiv is built from source: a C++ compiler and Boost. The word list it reads, /usr/share/dict/words, comes with macOS.
xcode-select --install          # if the command line tools aren't installed yet
brew install boost
```

Check with `qlever-server --version`. If Homebrew answers `Refusing to load formula qlever-dev/qlever/qlever from untrusted tap`, the trust step didn't take and nothing was installed: run `brew trust qlever-dev/qlever` (or `brew trust --formula qlever-dev/qlever/qlever`), then `brew install qlever` again.

On an Intel Mac there is no QLever package: leave QLever out with `--engines tripatha,oxigraph`, or build it from source and set `QLEVER_BIN` to the directory holding `qlever-index` and `qlever-server`.

### Linux

These steps follow QLever's and the distributions' documentation. The Linux side of `compare.sh` (the reflink copy, `sha256sum`, these packages) has not been run on a Linux machine yet: report what differs.

On Debian and Ubuntu (amd64), QLever has an official package repository. Its `qlever-bin` package installs `qlever-index` and `qlever-server` in `/usr/bin`:

```bash
sudo apt update && sudo apt install -y wget gpg ca-certificates
wget -qO - https://packages.qlever.dev/pub.asc | gpg --dearmor | sudo tee /usr/share/keyrings/qlever.gpg > /dev/null
echo "deb [arch=amd64 signed-by=/usr/share/keyrings/qlever.gpg] https://packages.qlever.dev/ $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") main" \
  | sudo tee /etc/apt/sources.list.d/qlever.list
sudo apt update
sudo apt install qlever

# WatDiv: a C++ compiler, Boost's date_time library and a word list for /usr/share/dict/words
sudo apt install build-essential libboost-date-time-dev wamerican
```

On Fedora and other RPM-based distributions, WatDiv needs `sudo dnf install gcc-c++ boost-devel words`. QLever has no package there, nor for arm64: build it from source and set `QLEVER_BIN`, or leave it out with `--engines tripatha,oxigraph`.

Check with `qlever-server --version`, and see [QLever's quickstart](https://docs.qlever.dev/quickstart/) for updates.

## Running them

```bash
# the join benchmark, in-process on RocksDB (loads its 10M-triple store the first time)
tripatha.loader/bench.sh --build

# the engine comparison on the join benchmark's suite
tripatha.loader/compare.sh --build

# ... and on WatDiv, at scale factor 100 (about 10M triples), 5 queries per template
tripatha.loader/compare.sh --suite watdiv
```

Use `--build` after changing code, so the benchmarks run the change. The first run of each suite prepares its data and the engines' stores; later runs reuse them:

- **join:** `bench.sh` loads the benchmark's RocksDB store (or reuses it, in `target/bench/rocksdb`) and exports it as N-Quads. It also runs the suite in-process, for a reference column.
- **watdiv:** `compare.sh` downloads WatDiv's source from the University of Waterloo, checks it against its SHA-256 and builds it in `~/.m2/repository/.cache/tripatha-compare`. WatDiv writes the data and the queries, and Tripatha loads the data into a RocksDB store of its own (`RdfFileLoad`).
- QLever builds its index and Oxigraph loads and optimizes its store, from the same file.
- Tripatha's endpoint serves a copy of its store, so nothing it writes reaches the original. On macOS the copy is an APFS clone: instant, and sharing the store's blocks. On Linux it is a reflink copy where the file system has them (Btrfs, XFS), and otherwise a full copy, which needs as much free disk as the store.

Everything goes under `tripatha.loader/target/compare` (`COMPARE_DIR` names another directory): one directory per dataset (`join-10M`, `watdiv-sf100`) holding the data, every engine's store and, for WatDiv, the queries, plus the logs and the reports. Delete a dataset's directory to start it afresh.

| Option | Default | |
|---|---|---|
| `--suite join\|watdiv` | `join` | which queries and data |
| `--triples N` | `10M` | the join suite's dataset size |
| `--scale N` | `100` | WatDiv's scale factor: about 105,000 triples each |
| `--per-template N` | `5` | WatDiv queries generated from each template |
| `--engines a,b` | `tripatha,qlever,oxigraph` | which engines; Tripatha is always one of them |
| `--only a,b` | all | run only these queries |
| `--warmup N`, `--runs N` | 3, 10 | runs per query and engine, before and while timing |
| `--timeout S` | 120 | seconds per query run |
| `--out DIR` | `target/compare` | where the reports go |

| Environment variable | |
|---|---|
| `TRIPATHA_HEAP` | the endpoint's Java heap (`6g`) |
| `QLEVER_MEMORY` | QLever's memory for queries (`6G`) |
| `QLEVER_SERVER_ARGS` | more options for `qlever-server`, for example `--enable-prefilter-on-index-scans false` |
| `QLEVER_BIN` | the directory of `qlever-index` and `qlever-server`, if they aren't on the `PATH` |
| `OXIGRAPH` | another Oxigraph binary than the pinned release |
| `BOOST_HOME` | Boost's prefix, for building WatDiv (on macOS, Homebrew's by default) |
| `BENCH_ROCKSDB_PATH` | the join suite's RocksDB store (`target/bench/rocksdb`) |

The servers listen on 127.0.0.1 only, on ports 7101 (Tripatha), 7102 (QLever) and 7103 (Oxigraph), and stop when the script ends.

## Reading the comparison

The report (Markdown and CSV, `compare-<date>`) starts with each engine's version, settings, load time and size on disk.

- **The engines must hold the same data.** The report first compares their statement counts, overall and per named graph. A query without `GRAPH` reads every graph on all three (Oxigraph runs with `--union-default-graph`).
- **They must return the same rows.** A query whose row count differs between engines, or that fails on one, is marked and left out of the summary. On the join suite, `text` uses Tripatha's own text search function, which the others don't have.
- **How a query is timed:** a SPARQL protocol POST, its whole result read as TSV, uncompressed, to the last byte. Each engine runs each query a few times first, then the engines take turns, so a slow phase of the machine doesn't land on one engine. QLever keeps a cache of results, which would answer the timed runs from the warm-up, so it is emptied before each of its runs.
- **The summary** is the geometric mean of the p50s over the queries every engine answered with the same rows, and for WatDiv one per query shape.
- **Warm cache only:** a cold-cache run needs the operating system's caches dropped (`sudo purge` on macOS), which the script doesn't do.
- Tripatha's store size includes its statistics counters and its secondary indexes (text, geo and entity on the join suite; entity on WatDiv), which the other engines don't build.
- Everything runs on one machine, the engines side by side: keep other heavy programs closed while it runs.

## WatDiv

WatDiv is the work of G. Aluç, O. Hartig, M. T. Özsu and K. Daudjee. When reporting results, cite their paper, as they ask: G. Aluç, O. Hartig, M. T. Özsu, and K. Daudjee. [Diversified stress testing of RDF data management systems](https://doi.org/10.1007/978-3-319-11964-9_13). In *Proc. 13th Int. Semantic Web Conference*, Part I, pages 197–212, 2014.

- **It is not part of this repository.** WatDiv publishes no license, so its source, data model and templates can't be redistributed under this project's license. `compare.sh` downloads version 0.6 from its publisher, checks it, and builds it on your machine.
- **Each generation is a new dataset.** WatDiv seeds its random numbers from the clock, and builds strings from the system's word list, so two generations differ, also between machines. The script generates the data and the queries once per scale factor and keeps them, so every engine and every later run use the same files. Compare numbers only within one generated dataset.
- It is built as C++14: its code uses `std::random_shuffle`, which C++17 removed.
