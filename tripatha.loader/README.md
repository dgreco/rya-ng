# Benchmarks

Two tools in this module measure Tripatha's query performance, on a laptop and without a cluster:

- **The join benchmark** (`bench.sh`, `JoinBench`) runs a fixed suite of 27 queries over a generated store of 10M triples, in-process, on RocksDB, Accumulo or HBase. It was written to measure Tripatha's own optimizations ([SCALE-STUDY.md](../SCALE-STUDY.md), M8), so it is not used to compare Tripatha with other engines.
- **The engine comparison** (`compare.sh`, `CompareBench`) runs a standard benchmark, [WatDiv](https://dsg.uwaterloo.ca/watdiv/) (the Waterloo SPARQL Diversity Test Suite), against Tripatha's SPARQL endpoint, as its native executable with RocksDB inside, and against two other open-source engines loaded with the same data, one after the other, over HTTP (SCALE-STUDY.md, M11):
  - [QLever](https://github.com/ad-freiburg/qlever) (Apache 2.0), a fast single-machine engine;
  - [Oxigraph](https://github.com/oxigraph/oxigraph) (MIT or Apache 2.0), an embedded store that, like Tripatha here, stores its data in RocksDB.

WatDiv has its own data generator and 20 query templates of four shapes (linear, star, snowflake, complex), designed by others and tuned for by no engine here.

## Quick start: the engine comparison

1. Install what it needs on your system (next section). `tripatha.loader/compare.sh --check` lists whatever is still missing, with the command that installs it.
2. Run `tripatha.loader/compare.sh --build`, from the repository's root.
3. Read the report it prints at the end, also saved as `tripatha.loader/target/compare/compare-<date>.md`.

The first run, at the default scale (about 10M triples), takes 35 to 40 minutes:
- building Tripatha and its native executable: 5 to 10 minutes;
- generating WatDiv's data and loading the three engines: about 3 minutes;
- measuring them one after the other: about 25 minutes, most of it Oxigraph's runs of the largest query (C3, 17 s each).

It needs about 3 GB of disk, plus Maven's downloads on a first build, and 8 GB of free memory. Later runs reuse the data and the stores; leave out `--build` unless the code changed.

## Installing what they need

Both platforms need **GraalVM for JDK 25** ([Oracle GraalVM or GraalVM Community](https://www.graalvm.org/downloads/)) with `JAVA_HOME` pointing at it. It is the JDK of the build, and its `native-image` builds Tripatha's native executable.
- **With Oracle GraalVM,** `compare.sh` builds the profile-guided executable (`tripatha.standalone/build-optimized.sh`, about 5 minutes). It trains on the join benchmark's queries over a small store of its own, never on WatDiv.
- **With GraalVM Community,** which has no profile-guided optimization, it builds the default one.
- The report says which it measured. Oracle GraalVM is free to use and to redistribute at no charge under the [GraalVM Free Terms and Conditions](https://www.oracle.com/downloads/licenses/graal-free-license.html), but it isn't open source. They also need Maven, bash, curl and git, as for the rest of the build. The join benchmark needs nothing else. Oxigraph needs nothing either: `compare.sh` downloads a pinned release from GitHub, checks its SHA-256 and keeps it in `~/.m2/repository/.cache/tripatha-compare`.

### macOS

```bash
# QLever (Apple Silicon only): its official Homebrew tap. Recent Homebrew versions also ask you to trust a third-party tap.
brew tap qlever-dev/qlever
brew trust qlever-dev/qlever
brew install qlever

# A C compiler for native-image, and a C++ compiler and Boost for WatDiv, which is built from source.
# The word list WatDiv reads, /usr/share/dict/words, comes with macOS.
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

# native-image: a C compiler and zlib's headers. WatDiv: a C++ compiler, Boost's date_time library and a word
# list for /usr/share/dict/words.
sudo apt install build-essential zlib1g-dev libboost-date-time-dev wamerican
```

On Fedora and other RPM-based distributions: `sudo dnf install gcc gcc-c++ glibc-devel zlib-devel boost-devel words`. QLever has no package there, nor for arm64: build it from source and set `QLEVER_BIN`, or leave it out with `--engines tripatha,oxigraph`.

Check with `qlever-server --version`, and see [QLever's quickstart](https://docs.qlever.dev/quickstart/) for updates.

## Running them

```bash
# the join benchmark, in-process on RocksDB (loads its 10M-triple store the first time)
tripatha.loader/bench.sh --build

# the engine comparison on WatDiv, at scale factor 100 (about 10M triples), 5 queries per template
tripatha.loader/compare.sh --build

# only check that everything it needs is installed
tripatha.loader/compare.sh --check
```

Use `--build` after changing code, so the benchmarks run the change. For the comparison it rebuilds the modules and Tripatha's native executable, `tripatha.standalone/target/tripatha` (the `-Pnative` build, as in CI); without it, the executable is built only if it is missing. The first comparison run at a scale prepares its data and the engines' stores; later runs reuse them:

- `compare.sh` downloads WatDiv's source from the University of Waterloo, checks it against its SHA-256 and builds it in `~/.m2/repository/.cache/tripatha-compare`. WatDiv writes the data and the queries.
- Tripatha loads the data into a RocksDB store of its own (`RdfFileLoad`, through the same DAO as a live store, with its statistics counters and the entity index). QLever builds its index and Oxigraph loads and optimizes its store, from the same file.
- Tripatha's endpoint serves a copy of its store, so nothing it writes reaches the original. On macOS the copy is an APFS clone: instant, and sharing the store's blocks. On Linux it is a reflink copy where the file system has them (Btrfs, XFS), and otherwise a full copy, which needs as much free disk as the store.

Then the engines are measured one at a time: each one's server is started, measured alone on the machine, and stopped before the next starts. A last step compares their results and writes the report.

Everything goes under `tripatha.loader/target/compare` (`COMPARE_DIR` names another directory): one directory per scale factor (`watdiv-sf100`) holding the data, the queries and every engine's store, each engine's results of a run (`runs/<date>`), the logs and the reports. Delete a scale's directory to start it afresh.

| Option | Default | |
|---|---|---|
| `--scale N` | `100` | WatDiv's scale factor: about 105,000 triples each |
| `--per-template N` | `5` | queries generated from each of the 20 templates |
| `--engines a,b` | `tripatha,qlever,oxigraph` | which engines; Tripatha is always one of them |
| `--only a,b` | all | run only these queries (`S1-01,C3-02`) |
| `--warmup N`, `--runs N` | 3, 10 | runs per query and engine, before and while timing |
| `--timeout S` | 120 | seconds per query run |
| `--out DIR` | `target/compare` | where the reports go |

| Environment variable | |
|---|---|
| `TRIPATHA_HEAP` | the heap of Tripatha's native executable (`-Xmx`, `6g`) |
| `TRIPATHA_MIN_HEAP` | its minimum heap (`-Xms`), the heap by default: its collector sizes the young generation from it. Set it empty to leave `-Xms` out |
| `TRIPATHA_PGO` | `false` builds the default executable even with Oracle GraalVM |
| `TRIPATHA_NATIVE` | another executable to serve instead of the one built here, never built itself: for comparing builds |
| `TRIPATHA_ARGS` | more endpoint settings for Tripatha, for example `--tripatha.join-lookup-threads=1` |
| `QLEVER_MEMORY` | QLever's memory for queries (`6G`) |
| `QLEVER_SERVER_ARGS` | more options for `qlever-server`, for example `--enable-prefilter-on-index-scans false` |
| `QLEVER_BIN` | the directory of `qlever-index` and `qlever-server`, if they aren't on the `PATH` |
| `OXIGRAPH` | another Oxigraph binary than the pinned release |
| `BOOST_HOME` | Boost's prefix, for building WatDiv (on macOS, Homebrew's by default) |

The servers listen on 127.0.0.1 only, on ports 7101 (Tripatha), 7102 (QLever) and 7103 (Oxigraph), and only one runs at a time.

## Reading the comparison

The report (Markdown and CSV, `compare-<date>`) starts with each engine's version, settings, load time and size on disk.

- **The engines must hold the same data.** The report first compares their statement counts.
- **They must return the same rows.** A query whose row count differs between engines, or that fails on one, is marked and left out of the summary.
- **How a query is timed:** a SPARQL protocol POST, its whole result read as TSV, uncompressed, to the last byte. QLever keeps a cache of results, which would answer the timed runs from the warm-up, so it is emptied before each of its runs.
- **One engine at a time:** the engine being measured is the only one running, so the others take none of its processors, memory or disk. It first runs every query once, so it starts warm (a JVM's compiler, say), then each query's warm-up and timed runs.
- **The summary** is the geometric mean of the p50s over the queries every engine answered with the same rows, overall and per query shape.
- **Warm cache only:** a cold-cache run needs the operating system's caches dropped (`sudo purge` on macOS), which the script doesn't do.
- Tripatha runs as its native executable, which has no JIT compiler and, as built, the Serial garbage collector: it starts at full speed but is slower than on the JVM on queries with large results.
- Tripatha's store size includes its statistics counters and its entity index, which the other engines don't build.
- The engines run one after the other, so anything else busy on the machine moves one engine's figures and not the others': keep other heavy programs closed while it runs.

## WatDiv

WatDiv is the work of G. Aluç, O. Hartig, M. T. Özsu and K. Daudjee. When reporting results, cite their paper, as they ask: G. Aluç, O. Hartig, M. T. Özsu, and K. Daudjee. [Diversified stress testing of RDF data management systems](https://doi.org/10.1007/978-3-319-11964-9_13). In *Proc. 13th Int. Semantic Web Conference*, Part I, pages 197–212, 2014.

- **It is not part of this repository.** WatDiv publishes no license, so its source, data model and templates can't be redistributed under this project's license. `compare.sh` downloads version 0.6 from its publisher, checks it, and builds it on your machine.
- **Each generation is a new dataset.** WatDiv seeds its random numbers from the clock, and builds strings from the system's word list, so two generations differ, also between machines. The script generates the data and the queries once per scale factor and keeps them, so every engine and every later run use the same files. Compare numbers only within one generated dataset.
- **Some queries find nothing.** WatDiv picks a query's constants from its model's distributions, so some combinations don't occur in the data: at scale factor 100, every query of S3, S5 and S7 came back empty, on every engine.
- It is built as C++14: its code uses `std::random_shuffle`, which C++17 removed.
