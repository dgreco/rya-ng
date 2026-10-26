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
# Configuration wizard for the loader's Accumulo connection: asks for the instance, the user and
# the password, and writes an accumulo-client.properties file for --client-properties (the SPARQL
# endpoint accepts the same file). Works with the bash 3.2 that ships with macOS.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
Usage: rya.loader/configure.sh [--output FILE] [--force] [--defaults] [--check | --no-check]

Asks for the Accumulo connection and writes an accumulo-client.properties file for the loader
(run.sh <command> --client-properties FILE). The SPARQL endpoint accepts the same file
(--rya.accumulo.client-properties=FILE).

The password can be stored in the file, which only you can read, or left out: the loader and
the endpoint then read it from RYA_ACCUMULO_PASSWORD when they start.

  --output FILE   where to write the file (default: ~/.rya-ng/accumulo-client.properties)
  --force         overwrite FILE if it exists
  --defaults      accept every default without asking. RYA_ACCUMULO_ZOOKEEPERS,
                  RYA_ACCUMULO_INSTANCE and RYA_ACCUMULO_USER preset the defaults; the
                  password is stored only if RYA_ACCUMULO_PASSWORD is set.
  --check         test the connection afterwards without asking (the loader's check command)
  --no-check      don't test it

Answers can also be piped in, one per line (an empty line keeps the default).
EOF
}

output="$HOME/.rya-ng/accumulo-client.properties"
force=false
defaults=false
check=ask
while [ $# -gt 0 ]; do
  case "$1" in
    --output) output="${2:?--output needs a file name}"; shift 2 ;;
    --output=*) output="${1#--output=}"; shift ;;
    --force) force=true; shift ;;
    --defaults) defaults=true; shift ;;
    --check) check=yes; shift ;;
    --no-check) check=no; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done
$defaults && [ "$check" = ask ] && check=no

# ---- prompting ------------------------------------------------------------------------------------

# ask VAR "Question" "default" [validator]: sets VAR to the answer, or to the default.
ask() {
  local _var="$1" _question="$2" _default="$3" _check="${4:-}" _answer
  while true; do
    if $defaults; then
      _answer="$_default"
    else
      if [ -n "$_default" ]; then printf '%s [%s]: ' "$_question" "$_default" >&2; else printf '%s: ' "$_question" >&2; fi
      IFS= read -r _answer || _answer=""
      [ -n "$_answer" ] || _answer="$_default"
    fi
    if [ -z "$_check" ] || "$_check" "$_answer"; then
      printf -v "$_var" '%s' "$_answer"
      return 0
    fi
    if $defaults; then
      echo "The default for \"$_question\" is not valid." >&2
      exit 2
    fi
  done
}

# yesno VAR "Question" y|n: sets VAR to true or false.
yesno() {
  local _var="$1" _question="$2" _default="$3" _answer
  while true; do
    if $defaults; then
      _answer="$_default"
    else
      if [ "$_default" = y ]; then printf '%s [Y/n]: ' "$_question" >&2; else printf '%s [y/N]: ' "$_question" >&2; fi
      IFS= read -r _answer || _answer=""
      [ -n "$_answer" ] || _answer="$_default"
    fi
    case "$_answer" in
      y|Y|yes|Yes|YES) printf -v "$_var" '%s' true; return 0 ;;
      n|N|no|No|NO) printf -v "$_var" '%s' false; return 0 ;;
    esac
    echo "  Please answer y or n." >&2
  done
}

# secret VAR "Question": reads a password without echoing it (twice at a terminal, to confirm).
secret() {
  local _var="$1" _question="$2" _first _second
  while true; do
    printf '%s: ' "$_question" >&2
    if [ -t 0 ]; then
      IFS= read -r -s _first || _first=""
      echo >&2
      printf 'Again, to confirm: ' >&2
      IFS= read -r -s _second || _second=""
      echo >&2
      if [ "$_first" != "$_second" ]; then
        echo "  The two passwords differ; try again." >&2
        continue
      fi
    else
      IFS= read -r _first || _first=""
      echo >&2
    fi
    if [ -n "$_first" ]; then
      printf -v "$_var" '%s' "$_first"
      return 0
    fi
    echo "  The password can't be empty." >&2
    [ -t 0 ] || exit 2
  done
}

section() {
  $defaults || printf '\n== %s ==\n' "$1" >&2
}

note() {
  echo "  $*" >&2
}

not_empty() { [ -n "$1" ] || { note "A value is required."; return 1; }; }
zookeepers() {
  local hp
  [ -n "$1" ] || { note "A value is required."; return 1; }
  case "$1" in *[[:space:]]*) note "No spaces, please: host:port pairs separated by commas."; return 1 ;; esac
  for hp in $(echo "$1" | tr ',' ' '); do
    case "$hp" in
      :*|*:|*:*[!0-9]*) note "Use host:port pairs separated by commas, such as zk1:2181,zk2:2181."; return 1 ;;
    esac
  done
}
duration() {
  case "$1" in [0-9]*ms|[0-9]*s|[0-9]*m) case "${1%%[a-z]*}" in *[!0-9]*) ;; *) return 0 ;; esac ;; esac
  note "Use a duration such as 30s or 1m."
  return 1
}

# A value as Java properties files store it. Accumulo reads the file as ISO-8859-1, so anything
# outside printable ASCII becomes \uXXXX escapes (UTF-16, as Java expects).
prop_value() {
  local v="$1" out
  if printf '%s' "$v" | LC_ALL=C grep -q '[^ -~]'; then
    out="$(printf '%s' "$v" | iconv -f UTF-8 -t UTF-16BE | od -An -v -tx1 | tr -d ' \n' | sed 's/\(....\)/\\u\1/g')" || {
      echo "A value is not valid UTF-8." >&2
      exit 2
    }
  else
    out="${v//\\/\\\\}"
    case "$out" in ' '*) out="\\$out" ;; esac
  fi
  printf '%s' "$out"
}

# ---- questions ------------------------------------------------------------------------------------

if [ -e "$output" ] && ! $force; then
  if $defaults; then
    echo "$output exists: pass --force to overwrite it, or --output to write elsewhere." >&2
    exit 2
  fi
  yesno overwrite "$output exists. Overwrite it" n
  $overwrite || exit 1
fi

$defaults || echo "rya-ng loader: Accumulo connection setup. Press Enter to keep the value in brackets." >&2

section "Accumulo connection"

ask zk "ZooKeeper servers (host:port,...)" "${RYA_ACCUMULO_ZOOKEEPERS:-localhost:2181}" zookeepers
first_zk="${zk%%,*}"
if command -v nc >/dev/null 2>&1; then
  if nc -z -w 3 "${first_zk%:*}" "${first_zk##*:}" >/dev/null 2>&1; then
    $defaults || note "ZooKeeper answers at $first_zk."
  else
    note "Note: nothing answers at $first_zk right now."
  fi
fi
ask instance "Accumulo instance name" "${RYA_ACCUMULO_INSTANCE:-accumulo}" not_empty
ask zk_timeout "ZooKeeper session timeout" "30s" duration
ask user "Accumulo user" "${RYA_ACCUMULO_USER:-root}" not_empty

section "Password"

password=""
if $defaults; then
  if [ -n "${RYA_ACCUMULO_PASSWORD:-}" ]; then
    store=true
    password="$RYA_ACCUMULO_PASSWORD"
  else
    store=false
  fi
else
  yesno store "Store the password in the file? (Only you can read the file. If not, the loader reads RYA_ACCUMULO_PASSWORD when it starts.)" y
  if $store; then
    secret password "Password of $user"
  fi
fi

# ---- the file -------------------------------------------------------------------------------------

dir="$(dirname "$output")"
if [ ! -d "$dir" ]; then
  (umask 077 && mkdir -p "$dir")
fi
tmp="$output.tmp.$$"
trap 'rm -f "$tmp"' EXIT
(
  umask 077
  {
    echo "# Accumulo client properties for rya-ng, written by rya.loader/configure.sh on $(date '+%Y-%m-%d %H:%M')."
    echo "#   Loader:   rya.loader/run.sh <command> --client-properties $output"
    echo "#             (or export RYA_ACCUMULO_CLIENT_PROPERTIES=$output)"
    echo "#   Endpoint: --rya.accumulo.client-properties=$output"
    echo "# Every setting: https://accumulo.apache.org/docs/2.x/configuration/client-properties"
    echo "instance.name=$(prop_value "$instance")"
    echo "instance.zookeepers=$(prop_value "$zk")"
    echo "instance.zookeepers.timeout=$zk_timeout"
    echo "auth.type=password"
    echo "auth.principal=$(prop_value "$user")"
    if $store; then
      echo "auth.token=$(prop_value "$password")"
    else
      echo "# auth.token is not stored: the loader and the endpoint read the password from RYA_ACCUMULO_PASSWORD."
    fi
    echo
    echo "# Encrypted connections: uncomment and fill in if the cluster uses TLS."
    echo "#ssl.enabled=true"
    echo "#ssl.truststore.path=/path/to/truststore.jks"
    echo "#ssl.truststore.type=jks"
    echo "#ssl.truststore.password="
  } > "$tmp"
)
chmod 600 "$tmp"
mv "$tmp" "$output"
trap - EXIT

$defaults || { echo >&2; echo "Wrote $output (readable only by you)." >&2; }

# ---- connection test -------------------------------------------------------------------------------

if [ "$check" = ask ]; then
  yesno test_now "Test the connection now (the loader's check command; builds the loader first if needed)" y
  $test_now && check=yes || check=no
fi
if [ "$check" = yes ]; then
  if ! $store && [ -z "${RYA_ACCUMULO_PASSWORD:-}" ]; then
    note "Skipping the test: set RYA_ACCUMULO_PASSWORD, then run: $HERE/run.sh check --client-properties $output"
  else
    echo >&2
    "$HERE/run.sh" check --client-properties "$output" || {
      echo "The connection test failed; fix the settings and run the wizard again (--force)." >&2
      exit 1
    }
  fi
fi

if ! $defaults; then
  echo >&2
  echo "Use it with:" >&2
  echo "  $HERE/run.sh <command> --client-properties $output ..." >&2
fi
