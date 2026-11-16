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
# Smoke test of a running endpoint: every result format, updates, text and geo search, triple
# terms, tracing, errors and the query page's files. It empties the store first (DROP ALL), so
# point it at a throwaway one:
#
#   tripatha.standalone/smoke-test.sh http://localhost:8080
#
# Prints one line per check and exits 1 if any failed. Used to check the native executable, which
# the Java integration tests can't start.
U=$1/sparql; B=$1; fail=0
chk() { local name=$1 want=$2 got=$3; if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name: wanted $want, got $got"; fail=1; fi; }
code() { curl -s -o /dev/null -m 60 -w "%{http_code}" "$@"; }
P='PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> PREFIX geo: <http://www.opengis.net/ont/geosparql#> PREFIX geof: <http://www.opengis.net/def/function/geosparql/> PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/> PREFIX text: <urn:tripatha:text#> PREFIX stats: <urn:tripatha:stats#> PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> PREFIX owl: <http://www.w3.org/2002/07/owl#> '
chk "drop all" 204 "$(code -X POST $U --data-urlencode 'update=DROP ALL')"
chk "insert" 204 "$(code -X POST $U --data-urlencode "update=$P INSERT DATA { <urn:ex:Monument> rdfs:subClassOf <urn:ex:Place> . <urn:ex:colosseum> a <urn:ex:Monument> ; rdfs:label \"Colosseo\"@it , \"Colosseum, the Flavian Amphitheatre\"@en , \"الكولوسيوم\"@ar--rtl ; <urn:ex:built> \"0080\"^^xsd:gYear ; <urn:ex:height> 48.5 ; <urn:ex:seats> 50000 ; <urn:ex:open> true ; <urn:ex:since> \"2020-01-02T03:04:05Z\"^^xsd:dateTime ; geo:hasGeometry <urn:ex:cg> . <urn:ex:cg> geo:asWKT \"POINT(12.4922 41.8902)\"^^geo:wktLiteral . <urn:ex:colosseum> <urn:ex:builtBy> <urn:ex:vespasian> {| <urn:ex:certainty> 0.9 |} . GRAPH <urn:ex:g1> { <urn:ex:pantheon> a <urn:ex:Monument> ; rdfs:label \"Pantheon\" . _:b <urn:ex:note> \"blank\" } }")"
for f in application/sparql-results+json application/sparql-results+xml text/csv text/tab-separated-values; do chk "select $f" 200 "$(code $U -H "Accept: $f" --data-urlencode "query=$P SELECT * WHERE { ?s ?p ?o } LIMIT 50")"; done
for f in text/turtle application/n-triples application/n-quads application/trig application/rdf+xml application/ld+json; do chk "construct $f" 200 "$(code $U -H "Accept: $f" --data-urlencode "query=CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")"; done
chk "describe" 200 "$(code $U -H "Accept: text/turtle" --data-urlencode "query=DESCRIBE <urn:ex:colosseum>")"
chk "ask json" 200 "$(code $U -H "Accept: application/sparql-results+json" --data-urlencode "query=ASK { ?s ?p ?o }")"
chk "ask xml" 200 "$(code $U -H "Accept: application/sparql-results+xml" --data-urlencode "query=ASK { ?s ?p ?o }")"
chk "get query" 200 "$(code -G $U --data-urlencode "query=SELECT * WHERE { ?s ?p ?o } LIMIT 1")"
chk "direct post" 200 "$(code $U -H "Content-Type: application/sparql-query" --data "SELECT * WHERE { ?s ?p ?o } LIMIT 1")"
chk "direct update" 204 "$(code $U -H "Content-Type: application/sparql-update" --data "INSERT DATA { <urn:ex:x> <urn:ex:p> 1 }")"
chk "count rows" "s" "$(curl -s -m 60 $U -H 'Accept: text/csv' --data-urlencode "query=SELECT ?s WHERE { ?s <urn:ex:p> 1 }" | head -1 | tr -d '\r')"
chk "inference" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE { ?s a <urn:ex:Place> }")"
chk "text search" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=$P SELECT ?s WHERE { ?s rdfs:label ?l FILTER(text:matches(?l, \"flavian\")) }")"
chk "geo" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=$P SELECT ?s WHERE { ?s geo:hasGeometry/geo:asWKT ?w FILTER(geof:distance(?w, \"POINT(12.49 41.89)\"^^geo:wktLiteral, uom:metre) < 2000) }")"
chk "graph sizes" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=$P SELECT ?g ?n WHERE { ?g stats:approxSize ?n }")"
chk "graphs" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")"
chk "triple terms" 200 "$(code $U -H 'Accept: application/sparql-results+json' --data-urlencode "query=$P SELECT ?r ?t (SUBJECT(?t) AS ?s) WHERE { ?r rdf:reifies ?t FILTER(isTRIPLE(?t)) }")"
chk "annotation" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=SELECT ?b ?c WHERE { <urn:ex:colosseum> <urn:ex:builtBy> ?b {| <urn:ex:certainty> ?c |} }")"
chk "aggregates" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=$P SELECT ?p (COUNT(*) AS ?n) (MAX(?o) AS ?m) WHERE { ?s ?p ?o } GROUP BY ?p ORDER BY DESC(?n) LIMIT 5")"
chk "optional/union/filter" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=$P SELECT * WHERE { { ?s a <urn:ex:Monument> OPTIONAL { ?s <urn:ex:height> ?h } } UNION { ?s <urn:ex:p> ?v } FILTER(!BOUND(?h) || ?h > 1) BIND(STRLEN(STR(?s)) AS ?len) } ORDER BY ?s")"
chk "property path" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=$P SELECT ?c WHERE { <urn:ex:colosseum> a/rdfs:subClassOf* ?c }")"
chk "functions" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=$P SELECT (NOW() AS ?n) (UUID() AS ?u) (MD5(\"x\") AS ?m) (YEAR(?d) AS ?y) (REGEX(?l, \"^Col\") AS ?r) (LANG(?l) AS ?lang) (xsd:integer(\"5\") + 1.5 AS ?sum) WHERE { <urn:ex:colosseum> <urn:ex:since> ?d ; rdfs:label ?l }")"
chk "auths param" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=SELECT * WHERE { ?s ?p ?o } LIMIT 1" --data-urlencode "auths=")"
chk "timeout param" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=SELECT * WHERE { ?s ?p ?o } LIMIT 1" --data-urlencode "timeout=5")"
T=exercise-trace-0123456789; (curl -s -N -m 5 $U/trace/$T > /dev/null &) ; sleep 0.5
chk "traced query" 200 "$(code $U -H 'Accept: text/csv' --data-urlencode "query=SELECT * WHERE { ?s <urn:ex:builtBy> ?o }" --data-urlencode "trace=$T")"
chk "malformed query" 400 "$(code $U --data-urlencode "query=SELEKT")"
chk "malformed update" 400 "$(code -X POST $U --data-urlencode "update=INSERT DATA {")"
chk "not acceptable" 406 "$(code $U -H 'Accept: image/png' --data-urlencode "query=SELECT * WHERE { ?s ?p ?o }")"
chk "delete where" 204 "$(code -X POST $U --data-urlencode "update=DELETE WHERE { <urn:ex:x> ?p ?o }")"
chk "delete/insert" 204 "$(code -X POST $U --data-urlencode "update=DELETE { ?s <urn:ex:seats> ?o } INSERT { ?s <urn:ex:seats> 60000 } WHERE { ?s <urn:ex:seats> ?o }")"
chk "clear graph" 204 "$(code -X POST $U --data-urlencode "update=CLEAR GRAPH <urn:ex:g1>")"
for a in / /ui/tripatha-ui.js /ui/tripatha-sparql12.js /ui/tripatha-map.js /ui/tripatha-trace.js /ui/wkt.js /ui/tripatha-ui.css /ui/favicon.svg /ui/tripatha-mark.svg /vendor/yasgui/yasgui.min.js /vendor/yasgui/yasgui.min.css /vendor/leaflet/leaflet.js /vendor/leaflet/leaflet.css /vendor/THIRD-PARTY-NOTICES.txt; do chk "asset $a" 200 "$(code $B$a)"; done
exit $fail
