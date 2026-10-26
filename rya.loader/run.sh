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
# Runs the loader, building it first if needed:
#
#   rya.loader/run.sh [--build] <command> [options]      (run.sh --help for the options)
#
# Connection: --client-properties FILE, or these environment variables:
#   RYA_ACCUMULO_ZOOKEEPERS, RYA_ACCUMULO_INSTANCE, RYA_ACCUMULO_USER, RYA_ACCUMULO_PASSWORD
# (or --zookeepers, --instance and --user; the password is only read from the environment).
#
# Other environment variables:
#   LOADER_HEAP        Java heap (default 4g)
#   LOADER_JAVA_OPTS   extra JVM options
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
JAR="$HERE/target/rya.loader-1.0.0-SNAPSHOT.jar"

build=false
if [ "${1:-}" = "--build" ]; then
  build=true
  shift
fi

if $build || [ ! -f "$JAR" ]; then
  echo "Building the loader..." >&2
  (cd "$ROOT" && mvn -B -q -pl rya.loader -am package -DskipTests)
fi

java_bin="java"
if [ -n "${JAVA_HOME:-}" ]; then
  java_bin="$JAVA_HOME/bin/java"
fi

java_opts=()
if [ "$(uname -s)" = Darwin ]; then
  # macOS can take seconds to resolve *.local names over IPv6.
  java_opts+=(-Djava.net.preferIPv4Stack=true)
  # Keep the Mac awake while the loader runs: exec keeps this PID, so caffeinate follows the JVM.
  if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -i -w $$ &
  fi
fi
# These two silence JDK warnings about Hadoop's use of Unsafe and native code.
java_opts+=(--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED "-Xmx${LOADER_HEAP:-4g}")

# shellcheck disable=SC2086 # LOADER_JAVA_OPTS is a list of options
exec "$java_bin" "${java_opts[@]}" ${LOADER_JAVA_OPTS:-} -jar "$JAR" "$@"
