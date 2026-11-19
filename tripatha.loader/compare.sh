#!/usr/bin/env bash
#
# Copyright 2026 David Greco
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Compares Tripatha's endpoint on RocksDB with QLever and Oxigraph (SCALE-STUDY.md M11). See
# tripatha.loader/README.md for the installation on macOS and Linux.
#
#   tripatha.loader/compare.sh [--build] [--suite join|watdiv] [--engines tripatha,qlever,oxigraph]
#                              [--only a,b] [--warmup 3] [--runs 10] [--timeout 120] [--out DIR]
#     --suite join      the join benchmark's queries and data (the default): --triples 10M
#     --suite watdiv    WatDiv's 20 basic templates over its data: --scale 100 (about 10M triples),
#                       --per-template 5 queries from each
#
# Each dataset lives in a directory of its own under tripatha.loader/target/compare (COMPARE_DIR
# names another), with every engine's store; they are built once and reused. The reports go there
# too, unless --out says otherwise. --build installs the modules first, as for bench.sh.
#
# - join: bench.sh loads its RocksDB store (or reuses it), with the entity index, exports it as
#   N-Quads, and runs the suite in-process on it, for reference.
# - watdiv: the WatDiv generator is downloaded from the University of Waterloo, checked against its
#   SHA-256 and built (it needs Boost and a C++ compiler); it writes the data and the queries once.
#   Tripatha loads the data into a RocksDB store of its own (RdfFileLoad).
# - QLever builds its index and Oxigraph loads and optimizes a store, from the same file.
# - Tripatha's endpoint (tripatha.standalone) serves a clone of its store, so nothing it writes
#   reaches the original; QLever and Oxigraph serve theirs read-only. All listen on 127.0.0.1.
# - CompareBench checks that the engines hold the same statements, sends them every query in turn,
#   and writes a Markdown and a CSV report.
#
# QLever: qlever-index and qlever-server on the PATH, or QLEVER_BIN naming their directory.
# Oxigraph: a pinned release, downloaded to ~/.m2/repository/.cache/tripatha-compare (OXIGRAPH names
# another binary). Boost for WatDiv: BOOST_HOME, else Homebrew's on macOS, else the system's.
# Memory: TRIPATHA_HEAP (6g) and QLEVER_MEMORY (6G). QLEVER_SERVER_ARGS adds options to qlever-server.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

absolute() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s' "$PWD/$1" ;;
  esac
}

OXIGRAPH_VERSION=0.5.13
WATDIV_TAR=watdiv_v06.tar
WATDIV_SHA256=fb8d930b74b3fbc8f948101bfaf658a90d2f74002f1fefda465c45ffd33a71d2
WATDIV_TEMPLATES="L1 L2 L3 L4 L5 S1 S2 S3 S4 S5 S6 S7 F1 F2 F3 F4 F5 C1 C2 C3"
PORT_TRIPATHA=7101
PORT_QLEVER=7102
PORT_OXIGRAPH=7103

build=false
suite=join
triples=10M
scale=100
per_template=5
engines=tripatha,qlever,oxigraph
only=""
warmup=3
runs=10
timeout=120
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --build) build=true ;;
    --help|-h)
      awk 'NR > 16 && /^set -euo/ { exit } NR > 16 { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
      exit 0 ;;
    --suite|--triples|--scale|--per-template|--engines|--only|--warmup|--runs|--timeout|--out)
      if [ $# -lt 2 ]; then
        echo "missing value for $1" >&2
        exit 2
      fi
      case "$1" in
        --suite) suite="$2" ;;
        --triples) triples="$2" ;;
        --scale) scale="$2" ;;
        --per-template) per_template="$2" ;;
        --engines) engines="$2" ;;
        --only) only="$2" ;;
        --warmup) warmup="$2" ;;
        --runs) runs="$2" ;;
        --timeout) timeout="$2" ;;
        --out) out="$(absolute "$2")" ;;
      esac
      shift ;;
    *) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

case "$suite" in
  join|watdiv) ;;
  *) echo "unknown suite $suite: join or watdiv" >&2; exit 2 ;;
esac
has() {
  case ",$engines," in
    *",$1,"*) return 0 ;;
    *) return 1 ;;
  esac
}
for e in $(printf '%s' "$engines" | tr ',' ' '); do
  case "$e" in
    tripatha|qlever|oxigraph) ;;
    *) echo "unknown engine $e: tripatha, qlever or oxigraph" >&2; exit 2 ;;
  esac
done
if ! has tripatha; then
  echo "--engines must include tripatha: the others are compared with it" >&2
  exit 2
fi

work="$(absolute "${COMPARE_DIR:-$HERE/target/compare}")"
cache="$HOME/.m2/repository/.cache/tripatha-compare"
out="${out:-$work}"
mkdir -p "$work/logs" "$out"

java_bin="java"
if [ -n "${JAVA_HOME:-}" ]; then
  java_bin="$JAVA_HOME/bin/java"
fi

sha256_of() { # FILE
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

fetch() { # URL FILE SHA256: downloads once, and keeps the file only if its digest is the expected one
  if [ ! -f "$2" ]; then
    mkdir -p "$(dirname "$2")"
    echo "Downloading $1..." >&2
    curl -sSfL -o "$2.part" "$1"
    if [ "$(sha256_of "$2.part")" != "$3" ]; then
      rm -f "$2.part"
      echo "$1 doesn't have the expected SHA-256" >&2
      exit 1
    fi
    mv "$2.part" "$2"
  fi
}

gigabytes() { # DIR
  du -sk "$1" | LC_ALL=C awk '{ printf "%.2f GB", $1 / 1048576 }'
}

# ---- the engines' programs ----

oxigraph_binary() {
  if [ -n "${OXIGRAPH:-}" ]; then
    printf '%s' "$OXIGRAPH"
    return
  fi
  local asset sha file
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) asset=aarch64_apple; sha=531e7052df7a7a1a7b06be6329f1db1fa5d1e0401377e28ccd8d9d580ea6299b ;;
    Darwin-x86_64) asset=x86_64_apple; sha=4389a9f2b15d76403aa3772773a735435c93afc545e23fa814b4b1b11a9f8da6 ;;
    Linux-aarch64) asset=aarch64_linux_gnu; sha=9d3f6ee2e78a3914d563c6109041eab7ecbc6e1aec5b8db285479f7f312ef55d ;;
    Linux-x86_64) asset=x86_64_linux_gnu; sha=05f54af6d7ebd73888a600bcf25017ef304c51726428f6dae10d32e9185618be ;;
    *) echo "no Oxigraph $OXIGRAPH_VERSION release for $(uname -sm): set OXIGRAPH" >&2; exit 2 ;;
  esac
  file="$cache/oxigraph_v${OXIGRAPH_VERSION}_$asset"
  fetch "https://github.com/oxigraph/oxigraph/releases/download/v$OXIGRAPH_VERSION/oxigraph_v${OXIGRAPH_VERSION}_$asset" "$file" "$sha"
  chmod +x "$file"
  printf '%s' "$file"
}

qlever_tool() { # NAME
  if [ -n "${QLEVER_BIN:-}" ]; then
    printf '%s' "$QLEVER_BIN/$1"
  elif command -v "$1" >/dev/null 2>&1; then
    command -v "$1"
  else
    echo "QLever's $1 isn't installed: see tripatha.loader/README.md, set QLEVER_BIN, or leave QLever out" >&2
    echo "with --engines tripatha,oxigraph" >&2
    exit 2
  fi
}

# WatDiv publishes no license, so it is never part of this repository: it is downloaded from its
# publisher and built here. It reads the dictionary /usr/share/dict/words.
watdiv_binary() {
  local tree="$cache/watdiv-v06"
  local bin="$tree/watdiv/bin/Release/watdiv"
  if [ -x "$bin" ]; then
    printf '%s' "$bin"
    return
  fi
  if [ ! -f /usr/share/dict/words ]; then
    echo "WatDiv needs the word list /usr/share/dict/words: see tripatha.loader/README.md" >&2
    exit 2
  fi
  fetch "https://dsg.uwaterloo.ca/watdiv/$WATDIV_TAR" "$cache/$WATDIV_TAR" "$WATDIV_SHA256"
  local boost="${BOOST_HOME:-}"
  if [ -z "$boost" ] && [ "$(uname -s)" = Darwin ] && command -v brew >/dev/null 2>&1; then
    boost="$(brew --prefix boost 2>/dev/null || true)"
  fi
  local flags=()
  if [ -n "$boost" ]; then
    flags=(-I"$boost/include" -L"$boost/lib")
  fi
  echo "Building WatDiv..." >&2
  rm -rf "$tree"
  mkdir -p "$tree"
  tar -xf "$cache/$WATDIV_TAR" -C "$tree"
  # C++14: the code uses std::random_shuffle, which C++17 removed.
  (cd "$tree/watdiv" && mkdir -p bin/Release &&
    "${CXX:-c++}" -std=c++14 -O2 -w -Iinclude ${flags[@]+"${flags[@]}"} src/dictionary.cpp src/model.cpp src/statistics.cpp \
      src/volatility_gen.cpp -lboost_date_time -o bin/Release/watdiv) >&2 ||
    { echo "WatDiv didn't build: is Boost installed? See tripatha.loader/README.md" >&2; exit 2; }
  printf '%s' "$bin"
}

if has qlever; then
  qindex="$(qlever_tool qlever-index)"
  qserver="$(qlever_tool qlever-server)"
fi
if has oxigraph; then
  oxigraph="$(oxigraph_binary)"
fi

# ---- the programs of this repository ----

jar="$ROOT/tripatha.standalone/target/tripatha-1.0.0-SNAPSHOT.jar"
if $build || [ ! -f "$jar" ]; then
  echo "Building the endpoint..." >&2
  (cd "$ROOT" && mvn -B -q -pl tripatha.standalone -am install -DskipTests -DskipITs)
fi
if $build || [ ! -f "$HERE/target/bench.classpath" ]; then
  # bench.sh builds the modules and the benchmark classes; --help then only prints its usage.
  "$HERE/bench.sh" --build --help > /dev/null
fi
bench_cp="$HERE/target/test-classes:$HERE/target/classes:$(cat "$HERE/target/bench.classpath")"

suite_args=(--warmup "$warmup" --runs "$runs" --timeout "$timeout")
if [ -n "$only" ]; then
  suite_args+=(--only "$only")
fi

# ---- the data, and Tripatha's store ----

compare_args=()
info=()
if [ "$suite" = join ]; then
  dir="$work/join-$triples"
  data="$dir/data.nq.gz"
  format=nq
  store="$(absolute "${BENCH_ROCKSDB_PATH:-$HERE/target/bench/rocksdb}")"
  prefix=bench_
  indexes=(--tripatha.text-index=true --tripatha.geo-index=true --tripatha.entity-index=true)
  mkdir -p "$dir"
  if [ ! -f "$data" ]; then
    # A new export makes the engines' stores stale.
    rm -rf "$dir/qlever" "$dir/oxigraph"
    "$HERE/bench.sh" --triples "$triples" --entity-index --export "$data"
  fi
  echo "Running the suite in-process..." >&2
  "$HERE/bench.sh" --triples "$triples" --entity-index --out "$dir/inprocess" "${suite_args[@]}" > "$work/logs/inprocess.log" 2>&1 ||
    { echo "the in-process run failed: see $work/logs/inprocess.log" >&2; exit 1; }
  inprocess_csv="$(ls -t "$dir/inprocess"/rocksdb-*.csv | head -1)"
  compare_args=(--triples "$triples" --inprocess "$inprocess_csv")
  info+=(--info "The in-process column is JoinBench on the same store, its report $(basename "$inprocess_csv" .csv).")
  store_note="the benchmark store with the text, geo and entity indexes"
else
  dir="$work/watdiv-sf$scale"
  data="$dir/data.nt.gz"
  format=nt
  store="$dir/tripatha"
  prefix=watdiv_
  indexes=(--tripatha.entity-index=true)
  if [ ! -f "$dir/ready" ]; then
    watdiv="$(watdiv_binary)"
    tree="$cache/watdiv-v06/watdiv"
    echo "Generating WatDiv's data at scale factor $scale, and $per_template queries per template..." >&2
    rm -rf "$dir"
    mkdir -p "$dir/gen/run" "$dir/queries"
    # WatDiv reads its name lists from ../../files and keeps its model in saved.txt, for the queries.
    ln -s "$tree/files" "$dir/files"
    (cd "$dir/gen/run" && "$watdiv" -d "$tree/model/wsdbm-data-model.txt" "$scale" 2> ../data.log | gzip -1 > "$data.part")
    mv "$data.part" "$data"
    for t in $WATDIV_TEMPLATES; do
      (cd "$dir/gen/run" && "$watdiv" -q "$tree/model/wsdbm-data-model.txt" "$tree/testsuite/$t.txt" "$per_template" 1 \
        2>> ../queries.log) |
        awk -v dir="$dir/queries" -v t="$t" 'BEGIN { RS = "" } { f = sprintf("%s/%s-%02d.rq", dir, t, NR); print > f; close(f) }'
    done
    generated=$(ls "$dir/queries" | wc -l | tr -d ' ')
    if [ "$generated" -ne $(( per_template * 20 )) ]; then
      echo "WatDiv wrote $generated queries, not $(( per_template * 20 )): see $dir/gen" >&2
      exit 1
    fi
    gzip -dc "$data" | wc -l | tr -d ' ' > "$dir/statements"
    sha256_of "$data" > "$dir/data.sha256"
    date -u +%Y-%m-%d > "$dir/generated"
    touch "$dir/ready"
  fi
  if has tripatha; then
    echo "Loading Tripatha's store..." >&2
    start=$(date +%s)
    "$java_bin" --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED "-Xmx${TRIPATHA_HEAP:-6g}" \
      -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$bench_cp" org.tripatha.loader.bench.RdfFileLoad \
      --rocksdb-path "$store" --file "$data" --id "$(cat "$dir/data.sha256")" --table-prefix "$prefix" > "$dir/tripatha-load.log" 2>&1 ||
      { echo "Tripatha's load failed: see $dir/tripatha-load.log" >&2; exit 1; }
    if grep -q "Loaded and compacted" "$dir/tripatha-load.log"; then
      echo $(( $(date +%s) - start )) > "$dir/tripatha-load-seconds"
    fi
  fi
  compare_args=(--queries "$dir/queries")
  info+=(--info "WatDiv v0.6 (G. Aluç, O. Hartig, M. T. Özsu and K. Daudjee, Diversified stress testing of RDF data management \
systems, ISWC 2014), scale factor $scale: $(cat "$dir/statements") statements generated on $(cat "$dir/generated"), and \
$per_template queries from each of its 20 basic templates.")
  store_note="its own store with the entity index, loaded in $(cat "$dir/tripatha-load-seconds" 2>/dev/null || echo "?") s"
fi
qdir="$dir/qlever"
odir="$dir/oxigraph"

# ---- the other engines' stores ----

if has qlever && [ ! -f "$qdir/ready" ]; then
  echo "Building QLever's index..." >&2
  rm -rf "$qdir"
  mkdir -p "$qdir"
  printf '{ "ascii-prefixes-only": false, "num-triples-per-batch": 1000000 }\n' > "$qdir/settings.json"
  start=$(date +%s)
  # -f - reads stdin (without -f, 0.6.0 fails an assertion).
  (cd "$qdir" && gzip -dc "$data" | "$qindex" -i index -F "$format" -f - -p true -s settings.json > index.log 2>&1) ||
    { echo "QLever's index build failed: see $qdir/index.log" >&2; exit 1; }
  echo $(( $(date +%s) - start )) > "$qdir/load-seconds"
  touch "$qdir/ready"
fi

if has oxigraph && [ ! -f "$odir/ready" ]; then
  echo "Loading Oxigraph..." >&2
  rm -rf "$odir"
  mkdir -p "$odir"
  start=$(date +%s)
  gzip -dc "$data" | "$oxigraph" load --location "$odir/store" --format "$format" > "$odir/load.log" 2>&1
  "$oxigraph" optimize --location "$odir/store" >> "$odir/load.log" 2>&1
  echo $(( $(date +%s) - start )) > "$odir/load-seconds"
  touch "$odir/ready"
fi

# ---- the servers ----

clone="$work/tripatha-store"
pids=()
cleanup() {
  for p in ${pids[@]+"${pids[@]}"}; do
    kill "$p" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  rm -rf "$clone"
}
trap cleanup EXIT

for port in $PORT_TRIPATHA $PORT_QLEVER $PORT_OXIGRAPH; do
  if curl -s -o /dev/null "http://127.0.0.1:$port/"; then
    echo "port $port is already in use" >&2
    exit 1
  fi
done

# A clone shares the store's blocks until something writes (APFS, or a reflink elsewhere).
rm -rf "$clone"
if [ "$(uname -s)" = Darwin ]; then
  cp -cR "$store" "$clone"
else
  cp -R --reflink=auto "$store" "$clone"
fi
heap="${TRIPATHA_HEAP:-6g}"
"$java_bin" --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED "-Xmx$heap" -jar "$jar" \
  --server.address=127.0.0.1 --server.port=$PORT_TRIPATHA --logging.level.root=warn \
  --tripatha.backend=rocksdb "--tripatha.rocksdb.path=$clone" "--tripatha.table-prefix=$prefix" "${indexes[@]}" \
  "--tripatha.sparql.query-timeout=${timeout}s" > "$work/logs/tripatha.log" 2>&1 &
pids+=($!)
tripatha_pid=$!
endpoints=(--endpoint "tripatha=http://127.0.0.1:$PORT_TRIPATHA/sparql")
info+=(--info "Tripatha $(git -C "$ROOT" rev-parse --short HEAD): tripatha.standalone on $("$java_bin" -version 2>&1 | head -1 | tr -d '"'), \
-Xmx$heap, over a clone of $store_note ($(gigabytes "$store") on disk, counters and indexes included).")

if has qlever; then
  qmemory="${QLEVER_MEMORY:-6G}"
  qextra=()
  read -r -a qextra <<< "${QLEVER_SERVER_ARGS:-}"
  (cd "$qdir" && exec "$qserver" -i index -p $PORT_QLEVER -m "$qmemory" -s "${timeout}s" ${qextra[@]+"${qextra[@]}"}) \
    > "$work/logs/qlever.log" 2>&1 &
  pids+=($!)
  qlever_pid=$!
  endpoints+=(--endpoint "qlever=http://127.0.0.1:$PORT_QLEVER/" --clear "qlever=http://127.0.0.1:$PORT_QLEVER/?cmd=clear-cache")
  info+=(--info "QLever $("$qserver" --version 2>&1 | head -1 | awk '{ print $NF }'): qlever-server -m $qmemory${QLEVER_SERVER_ARGS:+ $QLEVER_SERVER_ARGS}, its result cache emptied before every \
run; index built in $(cat "$qdir/load-seconds") s ($(gigabytes "$qdir") on disk).")
fi

if has oxigraph; then
  "$oxigraph" serve-read-only --location "$odir/store" --bind "127.0.0.1:$PORT_OXIGRAPH" --union-default-graph \
    --timeout-s "$timeout" > "$work/logs/oxigraph.log" 2>&1 &
  pids+=($!)
  oxigraph_pid=$!
  endpoints+=(--endpoint "oxigraph=http://127.0.0.1:$PORT_OXIGRAPH/query")
  info+=(--info "Oxigraph $OXIGRAPH_VERSION: serve-read-only --union-default-graph; loaded and optimized in \
$(cat "$odir/load-seconds") s ($(gigabytes "$odir/store") on disk).")
fi

wait_ready() { # NAME PID URL
  local i
  for i in $(seq 1 180); do
    if curl -sf -o /dev/null -X POST -H 'Content-Type: application/sparql-query' -H 'Accept: text/tab-separated-values' \
        --data-binary 'SELECT ?x WHERE { BIND(1 AS ?x) }' "$3"; then
      return 0
    fi
    if ! kill -0 "$2" 2>/dev/null; then
      echo "$1 stopped: see $work/logs/$1.log" >&2
      exit 1
    fi
    sleep 1
  done
  echo "$1 didn't answer within 180 s: see $work/logs/$1.log" >&2
  exit 1
}
wait_ready tripatha "$tripatha_pid" "http://127.0.0.1:$PORT_TRIPATHA/sparql"
if has qlever; then
  wait_ready qlever "$qlever_pid" "http://127.0.0.1:$PORT_QLEVER/"
fi
if has oxigraph; then
  wait_ready oxigraph "$oxigraph_pid" "http://127.0.0.1:$PORT_OXIGRAPH/query"
fi

echo "Comparing..." >&2
cd "$HERE"
"$java_bin" -cp "$bench_cp" org.tripatha.loader.bench.CompareBench "${compare_args[@]}" "${endpoints[@]}" "${suite_args[@]}" \
  "${info[@]}" --out "$out"
