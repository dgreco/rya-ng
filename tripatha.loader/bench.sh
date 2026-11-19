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
# Runs the local join benchmark (SCALE-STUDY.md, M8) on a test backend, without a cluster:
#
#   tripatha.loader/bench.sh [--build] [--backend rocksdb|accumulo|hbase] [options]   (--help for the options)
#
# --build installs the modules first: do it after changing code, so the benchmark runs the change.
# On RocksDB the store is kept in tripatha.loader/target/bench/rocksdb (BENCH_ROCKSDB_PATH names
# another directory) and reused while it holds the whole dataset: it records that itself, so
# deleting the directory is all it takes to start afresh. The other backends start a test cluster
# in the JVM and load the dataset each time. Relative paths (--out, --baseline, BENCH_ROCKSDB_PATH)
# are read from the directory this is run in; reports go to tripatha.loader/target/bench by default.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CLASSPATH_FILE="$HERE/target/bench.classpath"

# The benchmark runs in tripatha.loader, so a path given relative to the caller's directory is made absolute.
absolute() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s' "$PWD/$1" ;;
  esac
}

build=false
backend=rocksdb
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --build) build=true ;;
    --backend) backend="$2"; shift ;;
    --backend=*) backend="${1#--backend=}" ;;
    --out|--baseline)
      if [ $# -lt 2 ]; then
        echo "missing value for $1" >&2
        exit 2
      fi
      args+=("$1" "$(absolute "$2")"); shift ;;
    --help|-h) args+=("--help") ;;
    *) args+=("$1") ;;
  esac
  shift
done

if $build || [ ! -f "$CLASSPATH_FILE" ] || [ ! -d "$HERE/target/test-classes/org/tripatha/loader/bench" ]; then
  echo "Building the modules and the benchmark..." >&2
  (cd "$ROOT" && mvn -B -q -pl tripatha.loader -am install -DskipTests -DskipITs)
  (cd "$ROOT" && mvn -B -q -pl tripatha.loader test-compile dependency:build-classpath \
      -Dmdep.includeScope=test -Dmdep.outputFile="$CLASSPATH_FILE")
fi

java_bin="java"
if [ -n "${JAVA_HOME:-}" ]; then
  java_bin="$JAVA_HOME/bin/java"
fi

java_opts=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED "-Xmx${BENCH_HEAP:-6g}"
  "-Dtripatha.test.backend=$backend" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn)
if [ "$(uname -s)" = Darwin ]; then
  java_opts+=(-Djava.net.preferIPv4Stack=true)
fi
if [ "$backend" = rocksdb ]; then
  rocks="$(absolute "${BENCH_ROCKSDB_PATH:-$HERE/target/bench/rocksdb}")"
  mkdir -p "$rocks"
  java_opts+=("-Dtripatha.test.rocksdbPath=$rocks")
fi

cd "$HERE"
# shellcheck disable=SC2086 # BENCH_JAVA_OPTS is a list of options
exec "$java_bin" "${java_opts[@]}" ${BENCH_JAVA_OPTS:-} \
  -cp "$HERE/target/test-classes:$HERE/target/classes:$(cat "$CLASSPATH_FILE")" \
  org.tripatha.loader.bench.JoinBench ${args[@]+"${args[@]}"}
