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
# Configuration wizard for the rya-ng SPARQL endpoint: asks for the Accumulo connection and the
# store, security and server settings, then writes a launch script that starts the endpoint with
# them. Works with the bash 3.2 that ships with macOS.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: rya.sparql/configure.sh [--output FILE] [--force] [--defaults]

Asks for the settings of the rya-ng SPARQL endpoint and writes a launch script for it.
The Accumulo password is never asked for here and never written to the script: the script
reads it when it starts, from the terminal, the RYA_ACCUMULO_PASSWORD variable or a file
only you can read.

  --output FILE   where to write the launch script (default: ./rya-sparql.sh)
  --force         overwrite FILE if it exists
  --defaults      accept every default without asking. RYA_ACCUMULO_ZOOKEEPERS,
                  RYA_ACCUMULO_INSTANCE, RYA_ACCUMULO_USER and RYA_ACCUMULO_CLIENT_PROPERTIES
                  preset the connection defaults.

Answers can also be piped in, one per line (an empty line keeps the default).
EOF
}

output="./rya-sparql.sh"
force=false
defaults=false
while [ $# -gt 0 ]; do
  case "$1" in
    --output) output="${2:?--output needs a file name}"; shift 2 ;;
    --output=*) output="${1#--output=}"; shift ;;
    --force) force=true; shift ;;
    --defaults) defaults=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

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

# choose VAR "Question" default option...: a numbered menu; sets VAR to the chosen number.
choose() {
  local _var="$1" _question="$2" _default="$3" _i _answer _option
  shift 3
  if ! $defaults; then
    echo "$_question" >&2
    _i=1
    for _option in "$@"; do
      echo "  $_i) $_option" >&2
      _i=$((_i + 1))
    done
  fi
  while true; do
    if $defaults; then
      _answer="$_default"
    else
      printf 'Choice [%s]: ' "$_default" >&2
      IFS= read -r _answer || _answer=""
      [ -n "$_answer" ] || _answer="$_default"
    fi
    case "$_answer" in
      ''|*[!0-9]*) ;;
      *) if [ "$_answer" -ge 1 ] && [ "$_answer" -le $# ]; then printf -v "$_var" '%s' "$_answer"; return 0; fi ;;
    esac
    echo "  Please enter a number from 1 to $#." >&2
  done
}

section() {
  $defaults || printf '\n== %s ==\n' "$1" >&2
}

note() {
  echo "  $*" >&2
}

# ---- validators: explain and return 1 to ask again ---------------------------------------------------

not_empty() { [ -n "$1" ] || { note "A value is required."; return 1; }; }
port_number() {
  case "$1" in ''|*[!0-9]*) note "Enter a port number."; return 1 ;; esac
  [ "$1" -ge 1 ] && [ "$1" -le 65535 ] || { note "Ports go from 1 to 65535."; return 1; }
}
table_prefix() {
  case "$1" in ''|*[!A-Za-z0-9_]*) note "Use letters, digits and underscores only (Accumulo table names)."; return 1 ;; esac
}
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
heap_size() {
  case "$1" in [0-9]*[gGmM]) case "${1%?}" in *[!0-9]*) ;; *) return 0 ;; esac ;; esac
  note "Use a size such as 2g or 512m."
  return 1
}
duration() {
  case "$1" in [0-9]*ms|[0-9]*s|[0-9]*m|[0-9]*h) case "${1%%[a-z]*}" in *[!0-9]*) ;; *) return 0 ;; esac ;; esac
  note "Use a duration such as 120s, 5m or 1h."
  return 1
}
iri_list() {
  case "$1" in *[[:space:]\<\>\"\'\`\\]*) note "Full IRIs separated by commas, without spaces, quotes or angle brackets."; return 1 ;; esac
}
label_list() {
  case "$1" in *[!A-Za-z0-9_.:/,-]*) note "Labels use letters, digits and _-.:/ and are separated by commas."; return 1 ;; esac
}
visibility_expression() {
  case "$1" in *[!A-Za-z0-9_.:/\&\|\(\)-]*) note "Use a visibility expression such as A&(B|C), or leave it empty."; return 1 ;; esac
}
host_address() {
  case "$1" in *[[:space:]]*) note "No spaces, please."; return 1 ;; esac
}

# ---- Java and the jar -------------------------------------------------------------------------------

if [ -e "$output" ] && ! $force; then
  if $defaults; then
    echo "$output exists: pass --force to overwrite it, or --output to write elsewhere." >&2
    exit 2
  fi
  yesno overwrite "$output exists. Overwrite it" n
  $overwrite || exit 1
fi

$defaults || echo "rya-ng SPARQL endpoint setup. Press Enter to keep the value in brackets." >&2

section "Java and the endpoint jar"

jar_default=""
for candidate in "$HERE"/target/rya.sparql-*.jar; do
  case "$candidate" in *'*'*|*-sources.jar|*-javadoc.jar|*-tests.jar) ;; *) jar_default="$candidate" ;; esac
done
[ -n "$jar_default" ] || jar_default="$HERE/target/rya.sparql-1.0.0-SNAPSHOT.jar"
ask jar "Endpoint jar" "$jar_default" not_empty
[ -f "$jar" ] || note "$jar does not exist yet. Build it with: cd $ROOT && mvn -pl rya.sparql -am package -DskipTests"

java_default=java
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_default="$JAVA_HOME/bin/java"
elif [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 25+ >/dev/null 2>&1; then
  java_default="$(/usr/libexec/java_home -v 25+)/bin/java"
elif command -v java >/dev/null 2>&1; then
  java_default="$(command -v java)"
fi
ask java "Java 25 or newer (the java command)" "$java_default" not_empty
java_version="$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' || true)"
if [ -z "$java_version" ]; then
  note "Warning: could not run $java."
elif [ "${java_version%%.*}" -lt 25 ] 2>/dev/null; then
  note "Warning: $java is Java $java_version; the endpoint needs Java 25 or newer."
fi
ask heap "Maximum Java heap" "2g" heap_size

# ---- Accumulo connection ------------------------------------------------------------------------------

section "Accumulo connection"

connection_default=1
[ -z "${RYA_ACCUMULO_CLIENT_PROPERTIES:-}" ] || connection_default=2
choose connection "How does the endpoint connect to Accumulo?" "$connection_default" \
  "ZooKeeper servers, instance name and user" \
  "An accumulo-client.properties file (written by rya.loader/configure.sh)"

zk="" instance="" user="" client_properties="" password_mode=0 password_file=""
if [ "$connection" = 1 ]; then
  ask zk "ZooKeeper servers (host:port,...)" "${RYA_ACCUMULO_ZOOKEEPERS:-localhost:2181}" zookeepers
  ask instance "Accumulo instance name" "${RYA_ACCUMULO_INSTANCE:-accumulo}" not_empty
  ask user "Accumulo user" "${RYA_ACCUMULO_USER:-root}" not_empty
  choose password_mode "Where should the launch script get the password when it starts? (It is never stored in the script.)" 1 \
    "Ask for it at the terminal" \
    "The RYA_ACCUMULO_PASSWORD environment variable" \
    "A file only you can read (chmod 600)"
  if [ "$password_mode" = 3 ]; then
    ask password_file "Password file (first line)" "$HOME/.rya-ng/accumulo.password" not_empty
    if [ ! -e "$password_file" ]; then
      note "Create it with: mkdir -p \"$(dirname "$password_file")\" && (umask 077 && read -rs -p 'Password: ' p && printf '%s\n' \"\$p\" > \"$password_file\")"
    fi
  fi
  first_zk="${zk%%,*}"
  zk_host="${first_zk%:*}"
  zk_port="${first_zk##*:}"
  if command -v nc >/dev/null 2>&1; then
    if nc -z -w 3 "$zk_host" "$zk_port" >/dev/null 2>&1; then
      $defaults || note "ZooKeeper answers at $zk_host:$zk_port."
    else
      note "Note: nothing answers at $zk_host:$zk_port right now."
    fi
  fi
else
  ask client_properties "accumulo-client.properties file (rya.loader/configure.sh writes one)" "${RYA_ACCUMULO_CLIENT_PROPERTIES:-$HOME/.rya-ng/accumulo-client.properties}" not_empty
  [ -r "$client_properties" ] || note "Note: $client_properties is not readable yet."
fi

ipv4_default=n
if [ "$(uname -s)" = Darwin ]; then
  case "$zk" in *.local*) ipv4_default=y ;; esac
fi
yesno ipv4 "Prefer IPv4 (recommended on macOS with *.local names, which can take seconds to resolve over IPv6)" "$ipv4_default"

# ---- store ----------------------------------------------------------------------------------------------

section "Store"

ask prefix "Table prefix (the tables are created if missing)" "rya_" table_prefix
yesno text_index "Keep the full-text index (text:matches)" n
text_predicates=""
if $text_index; then
  ask text_predicates "Predicates to text-index, full IRIs separated by commas (empty: all)" "" iri_list
fi
yesno geo_index "Keep the GeoSPARQL index" n
rebuild=false
if $text_index || $geo_index; then
  yesno rebuild "Build the indexes over statements already in the store when it starts (needed once if the store has data)" n
fi
yesno inference "Query-time OWL/RDFS inference" n
yesno stats "Keep statistics for join ordering" y

# ---- security -------------------------------------------------------------------------------------------

section "Visibility labels"

ask auths "Labels a request may read with, separated by commas (the Accumulo user must hold them; empty: unlabeled data only)" "" label_list
ask default_visibility "Visibility label written on inserted statements (empty: none)" "" visibility_expression
yesno request_visibility "Let an update choose its own label with visibility=" n

# ---- server ---------------------------------------------------------------------------------------------

section "Server"

ask server_port "HTTP port" "8080" port_number
ask server_address "Listen address (empty: every interface; 127.0.0.1: this machine only)" "" host_address
ask query_timeout "Maximum query or update time" "120s" duration
ask max_query_timeout "Longest time a request may ask for with timeout= (0s: no limit)" "30m" duration
yesno allow_trace "Let requests be traced (shows their Accumulo scans and writes)" y

# ---- the launch script ----------------------------------------------------------------------------------

# Shell words for the generated script: as is when safe, single-quoted otherwise.
sq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
q() {
  case "$1" in
    ''|*[!A-Za-z0-9_./:=,@%+-]*) sq "$1" ;;
    *) printf '%s' "$1" ;;
  esac
}

java_opts="-Xmx$heap"
$ipv4 && java_opts="$java_opts -Djava.net.preferIPv4Stack=true"

args=()
if [ "$connection" = 1 ]; then
  args+=("--rya.accumulo.zookeepers=$zk" "--rya.accumulo.instance=$instance" "--rya.accumulo.user=$user")
else
  args+=("--rya.accumulo.client-properties=$client_properties")
fi
args+=("--rya.table-prefix=$prefix" "--rya.text-index=$text_index" "--rya.geo-index=$geo_index")
[ -z "$text_predicates" ] || args+=("--rya.text-index-predicates=$text_predicates")
$rebuild && args+=("--rya.rebuild-indexes=true")
args+=("--rya.inference=$inference" "--rya.stats=$stats")
[ -z "$auths" ] || args+=("--rya.sparql.authorizations=$auths")
[ -z "$default_visibility" ] || args+=("--rya.sparql.default-visibility=$default_visibility")
args+=("--rya.sparql.allow-request-visibility=$request_visibility" "--rya.sparql.query-timeout=$query_timeout")
args+=("--rya.sparql.max-query-timeout=$max_query_timeout" "--rya.sparql.allow-trace=$allow_trace")
args+=("--server.port=$server_port")
[ -z "$server_address" ] || args+=("--server.address=$server_address")

tmp="$output.tmp.$$"
trap 'rm -f "$tmp"' EXIT
{
  echo '#!/usr/bin/env bash'
  echo "# Starts the rya-ng SPARQL endpoint. Written by rya.sparql/configure.sh on $(date '+%Y-%m-%d %H:%M')."
  echo '# Run the wizard again to change the settings, or edit them below. Arguments given to this'
  echo '# script are passed to the endpoint and win over these, e.g. --server.port=9090.'
  echo 'set -euo pipefail'
  echo
  echo "JAVA=$(q "$java")"
  echo "JAR=$(q "$jar")"
  echo "# The two access options silence JDK warnings about Hadoop's use of Unsafe and native code."
  echo "JAVA_OPTS=($java_opts --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED)"
  echo
  echo 'SETTINGS=('
  for a in "${args[@]}"; do
    echo "  $(q "$a")"
  done
  echo ')'
  echo
  case "$password_mode" in
    1)
      echo '# The password: from RYA_ACCUMULO_PASSWORD if set, otherwise asked for at the terminal.'
      echo 'if [ -z "${RYA_ACCUMULO_PASSWORD:-}" ]; then'
      echo '  if [ ! -t 0 ]; then'
      echo '    echo "Set RYA_ACCUMULO_PASSWORD, or start this script from a terminal." >&2'
      echo '    exit 2'
      echo '  fi'
      echo "  IFS= read -r -s -p $(sq "Accumulo password for $user: ") RYA_ACCUMULO_PASSWORD"
      echo '  echo >&2'
      echo 'fi'
      echo 'export RYA_ACCUMULO_PASSWORD'
      ;;
    2)
      echo '# The password comes from the environment.'
      echo ': "${RYA_ACCUMULO_PASSWORD:?Set RYA_ACCUMULO_PASSWORD to the password of the Accumulo user}"'
      echo 'export RYA_ACCUMULO_PASSWORD'
      ;;
    3)
      echo '# The password: from RYA_ACCUMULO_PASSWORD if set, otherwise the first line of this file.'
      echo "PASSWORD_FILE=$(q "$password_file")"
      echo 'if [ -z "${RYA_ACCUMULO_PASSWORD:-}" ]; then'
      echo '  if [ ! -r "$PASSWORD_FILE" ]; then'
      echo '    echo "Cannot read $PASSWORD_FILE." >&2'
      echo '    exit 2'
      echo '  fi'
      echo '  case "$(ls -ln "$PASSWORD_FILE" | cut -c5-10)" in'
      echo '    ------) ;;'
      echo '    *) echo "$PASSWORD_FILE can be read by others: chmod 600 it." >&2; exit 2 ;;'
      echo '  esac'
      echo '  IFS= read -r RYA_ACCUMULO_PASSWORD < "$PASSWORD_FILE" || true'
      echo 'fi'
      echo 'export RYA_ACCUMULO_PASSWORD'
      ;;
    *)
      echo '# The credentials are in the accumulo-client.properties file.'
      echo 'unset RYA_ACCUMULO_PASSWORD'
      ;;
  esac
  echo
  echo '# A setting given as an argument replaces the one above: Spring would join the two values.'
  echo 'ARGS=()'
  echo 'for setting in "${SETTINGS[@]}"; do'
  echo '  replaced=false'
  echo '  for arg in "$@"; do'
  echo '    case "$arg" in "${setting%%=*}"=*) replaced=true ;; esac'
  echo '  done'
  echo '  $replaced || ARGS+=("$setting")'
  echo 'done'
  echo
  echo 'exec "$JAVA" "${JAVA_OPTS[@]}" -jar "$JAR" ${ARGS[@]+"${ARGS[@]}"} "$@"'
} > "$tmp"
chmod 755 "$tmp"
mv "$tmp" "$output"
trap - EXIT

if ! $defaults; then
  echo >&2
  echo "Wrote $output. Start the endpoint with:" >&2
  echo "  $output" >&2
  echo "Then open http://${server_address:-localhost}:$server_port/ for the query page." >&2
fi
