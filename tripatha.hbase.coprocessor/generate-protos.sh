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
# Regenerates the endpoint's protobuf classes from src/main/proto, into src/main/java:
#
#   tripatha.hbase.coprocessor/generate-protos.sh [--check]
#
# The generated code is checked in, so the build needs no protoc. Run this after changing a .proto
# file, and commit what it writes; --check only says whether the checked-in code is current.
#
# HBase 3.0 runs endpoints on its shaded protobuf runtime (hbase-shaded-protobuf 4.1.14, protobuf
# 4.34.0), and protobuf refuses generated code newer than its runtime. So the compiler is protoc
# 34.0, from Maven Central, pinned per platform by SHA-256 and kept in
# ~/.m2/repository/.cache/tripatha-protoc. Its output is moved to the shaded package
# (com.google.protobuf to org.apache.hbase.thirdparty.com.google.protobuf), as HBase does with its
# own. Upgrading HBase may mean another protoc: match the runtime's version (RuntimeVersion in
# hbase-shaded-protobuf), and change PROTOC_VERSION and the digests below.
set -euo pipefail

PROTOC_VERSION=4.34.0

here="$(cd "$(dirname "$0")" && pwd)"
check=false
case "${1:-}" in
  --check) check=true ;;
  "") ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) classifier=osx-aarch_64; digest=853956da0bdeb1f6f0cc14528b49bb9981c85265d6af0af788ea680105fcdcd8 ;;
  Darwin-x86_64) classifier=osx-x86_64; digest=a1103b9fafd41a971fd51494485339775698e23e70a28dfd7187398b4eadf8d7 ;;
  Linux-x86_64) classifier=linux-x86_64; digest=dc98cd430f89c2f17d7311c8de6637fa96369dff13ab54ab77b41f514676d8dc ;;
  Linux-aarch64 | Linux-arm64) classifier=linux-aarch_64; digest=6cc482c9b372b89c3190c9a2318176dfd1c878db1b53d610b6472f437feebe38 ;;
  *) echo "no pinned protoc $PROTOC_VERSION for $(uname -sm)" >&2; exit 2 ;;
esac

sha256_of() { # FILE
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

cache="$HOME/.m2/repository/.cache/tripatha-protoc"
protoc="$cache/protoc-$PROTOC_VERSION-$classifier.exe"
if [ ! -f "$protoc" ]; then
  mkdir -p "$cache"
  url="https://repo1.maven.org/maven2/com/google/protobuf/protoc/$PROTOC_VERSION/protoc-$PROTOC_VERSION-$classifier.exe"
  echo "Downloading $url..." >&2
  curl -sSfL -o "$protoc.part" "$url"
  mv "$protoc.part" "$protoc"
fi
# Checked on every run, not only after the download.
if [ "$(sha256_of "$protoc")" != "$digest" ]; then
  rm -f "$protoc"
  echo "protoc $PROTOC_VERSION ($classifier) doesn't have the expected SHA-256: deleted" >&2
  exit 1
fi
chmod +x "$protoc"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/java" "$work/out"
"$protoc" -I"$here/src/main/proto" --java_out="$work/java" "$here"/src/main/proto/*.proto

header='/*
 * Copyright 2026 David Greco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Written by tripatha.hbase.coprocessor/generate-protos.sh from src/main/proto: the output of protoc,
// moved to HBase'"'"'s shaded protobuf. Edit the .proto file and run the script instead.'

(cd "$work/java" && find . -name '*.java') | while read -r f; do
  mkdir -p "$(dirname "$work/out/$f")"
  {
    printf '%s\n' "$header"
    # The package's own name is preceded by a dot in the shaded one, so a second run changes nothing.
    sed -E 's/(^|[^.A-Za-z0-9_])com\.google\.protobuf\./\1org.apache.hbase.thirdparty.com.google.protobuf./g' "$work/java/$f"
  } > "$work/out/$f"
done

target="$here/src/main/java/org/tripatha/hbase/coprocessor/protobuf"
if $check; then
  if diff -r "$work/out/org/tripatha/hbase/coprocessor/protobuf" "$target" > /dev/null 2>&1; then
    echo "The generated protobuf code is current."
    exit 0
  fi
  echo "The generated protobuf code is out of date: run $0" >&2
  exit 1
fi
rm -rf "$target"
mkdir -p "$target"
cp "$work/out/org/tripatha/hbase/coprocessor/protobuf/"*.java "$target/"
echo "Wrote $(ls "$target" | wc -l | tr -d ' ') file(s) to $target"
