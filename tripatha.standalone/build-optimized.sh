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
# Builds the endpoint's native executable with profile-guided optimization, at target/tripatha:
#
#   tripatha.standalone/build-optimized.sh [--triples 1M] [--skip-modules]
#
# It needs Oracle GraalVM for JDK 25 (JAVA_HOME): GraalVM Community, which CI and the Docker image
# use, has no profile-guided optimization, and its -O3 is its -O2. Oracle GraalVM is free to use
# and to redistribute at no charge under the GraalVM Free Terms and Conditions; it isn't open
# source, so this build is opt-in and the default one (mvn -Pnative) is unchanged.
#
# 1. The join benchmark (tripatha.loader's JoinBench) loads a small store of its generated data,
#    --triples of them, into target/pgo/rocksdb (kept and reused), and writes its query suite.
# 2. An instrumented executable is built, serves that store, and is sent every query of the
#    suite (CompareBench: once, then 3 warm-up and 10 timed runs); it writes its profile when it
#    stops. The training never sees WatDiv, which compare.sh measures.
# 3. The optimized executable is built from that profile (--pgo, which implies -O3).
#
# --skip-modules leaves out building the modules and the benchmark classes (compare.sh has just
# done it). target/tripatha.build describes the result, with the executable's SHA-256, so that
# compare.sh reports which build it measured. About 5 minutes in all.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
LOADER="$ROOT/tripatha.loader"
PORT=7109

triples=1M
modules=true
while [ $# -gt 0 ]; do
  case "$1" in
    --triples) triples="$2"; shift ;;
    --triples=*) triples="${1#--triples=}" ;;
    --skip-modules) modules=false ;;
    --help|-h) sed -n '17,35p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/native-image" ]; then
  native_image="$JAVA_HOME/bin/native-image"
elif command -v native-image >/dev/null 2>&1; then
  native_image="$(command -v native-image)"
else
  echo "native-image not found: set JAVA_HOME to Oracle GraalVM for JDK 25 (https://www.graalvm.org/downloads/)" >&2
  exit 2
fi
graalvm="$("$native_image" --version 2>/dev/null | sed -n 2p)"
case "$graalvm" in
  *"Oracle GraalVM"*) ;;
  *) echo "profile-guided optimization needs Oracle GraalVM; JAVA_HOME has: ${graalvm:-an unknown native-image}" >&2
     echo "(mvn -Pnative -pl tripatha.standalone -am package builds the default executable with it)" >&2
     exit 2 ;;
esac
java_bin="$(dirname "$native_image")/java"

work="$HERE/target/pgo"
mkdir -p "$work"
exe="$HERE/target/tripatha"
# Until the optimized build is done there is no executable: a failure must not leave an older or
# the instrumented one there for compare.sh to serve under another build's name.
rm -f "$exe" "$HERE/target/tripatha.build"

native_build() { # NAME NATIVE-IMAGE-OPTIONS EXPECTED-PGO: the module's native build, checked for what it applied
  echo "Building the $1 executable..." >&2
  (cd "$ROOT" && NATIVE_IMAGE_OPTIONS="${NATIVE_IMAGE_OPTIONS:+$NATIVE_IMAGE_OPTIONS }$2" \
    mvn -B -Pnative -pl tripatha.standalone -am package -DskipTests -DskipITs) > "$work/build-$1.log" 2>&1 ||
    { echo "the $1 build failed: see $work/build-$1.log" >&2; exit 1; }
  if ! grep -q "PGO: $3" "$work/build-$1.log"; then
    echo "the $1 build didn't apply its profile setting (PGO: $3): see $work/build-$1.log" >&2
    exit 1
  fi
}

if $modules; then
  "$LOADER/bench.sh" --build --help > /dev/null
fi

# 1. The training store and its queries.
echo "Loading the training store ($triples triples, kept in $work/rocksdb)..." >&2
BENCH_ROCKSDB_PATH="$work/rocksdb" "$LOADER/bench.sh" --triples "$triples" --entity-index --load-only --out "$work/bench" \
  > "$work/load.log" 2>&1 || { echo "loading the training store failed: see $work/load.log" >&2; exit 1; }
rm -rf "$work/queries"
"$LOADER/bench.sh" --triples "$triples" --write-queries "$work/queries" > "$work/queries.log" 2>&1 ||
  { echo "writing the training queries failed: see $work/queries.log" >&2; exit 1; }

# 2. The instrumented executable, trained on them.
native_build instrumented "--pgo-instrument" "sampling+instrument"
cp "$exe" "$work/tripatha-instrumented"
rm -f "$exe"
rm -f "$work/default.iprof"
(cd "$work" && exec ./tripatha-instrumented -Xmx4g -Xms4g --server.address=127.0.0.1 --server.port=$PORT --logging.level.root=warn \
  --tripatha.backend=rocksdb "--tripatha.rocksdb.path=$work/rocksdb" --tripatha.table-prefix=bench_ \
  --tripatha.entity-index=true --tripatha.text-index=true --tripatha.geo-index=true) > "$work/train-server.log" 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null || true' EXIT
ready=false
for _ in $(seq 1 180); do
  if curl -sf -o /dev/null -X POST -H 'Content-Type: application/sparql-query' -H 'Accept: text/tab-separated-values' \
      --data-binary 'SELECT ?x WHERE { BIND(1 AS ?x) }' "http://127.0.0.1:$PORT/sparql"; then
    ready=true
    break
  fi
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done
$ready || { echo "the instrumented executable didn't start: see $work/train-server.log" >&2; exit 1; }
echo "Training it on the join benchmark's queries..." >&2
bench_cp="$LOADER/target/test-classes:$LOADER/target/classes:$(cat "$LOADER/target/bench.classpath")"
"$java_bin" -cp "$bench_cp" org.tripatha.loader.bench.CompareBench --queries "$work/queries" \
  --endpoint "training=http://127.0.0.1:$PORT/sparql" --raw "$work/training.tsv" > "$work/training.log" 2>&1 ||
  { echo "the training run failed: see $work/training.log" >&2; exit 1; }
# The profile is written when the executable stops.
kill -TERM "$pid"
wait "$pid" 2>/dev/null || true
trap - EXIT
[ -s "$work/default.iprof" ] || { echo "the instrumented executable wrote no profile: see $work/train-server.log" >&2; exit 1; }

# 3. The optimized executable.
native_build optimized "--pgo=$work/default.iprof" "user-provided"
if command -v sha256sum >/dev/null 2>&1; then sum="$(sha256sum "$exe" | cut -d' ' -f1)"; else sum="$(shasum -a 256 "$exe" | cut -d' ' -f1)"; fi
printf '%s %s\n' "$sum" "profile-guided (build-optimized.sh, trained on the join benchmark at $triples triples), $graalvm" \
  > "$HERE/target/tripatha.build"
echo "Built $exe: profile-guided, trained on the join benchmark at $triples triples." >&2
