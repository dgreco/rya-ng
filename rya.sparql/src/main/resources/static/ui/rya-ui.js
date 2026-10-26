/*
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

/* The rya-ng query page: YASGUI on this server's /sparql endpoint, with examples and authorizations. */
(function () {
    "use strict";

    const PREFIXES = [
        "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>",
        "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>",
        "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>",
        "PREFIX text: <urn:rya-ng:text#>",
        "PREFIX geo: <http://www.opengis.net/ont/geosparql#>",
        "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>",
        "PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>",
    ].join("\n") + "\n\n";

    const SAMPLE_GRAPH = "urn:rya-ng:sample";

    const EXAMPLES = [
        {
            name: "Browse statements",
            query: "SELECT * WHERE {\n  ?s ?p ?o\n}\nLIMIT 100\n",
        },
        {
            name: "Named graphs and their size",
            query: "SELECT ?graph (COUNT(*) AS ?statements) WHERE {\n  GRAPH ?graph { ?s ?p ?o }\n}\n"
                + "GROUP BY ?graph\nORDER BY DESC(?statements)\n",
        },
        {
            name: "Statements per type (try the Chart tab)",
            query: "SELECT ?type (COUNT(?s) AS ?count) WHERE {\n  ?s a ?type\n}\nGROUP BY ?type\nORDER BY DESC(?count)\nLIMIT 20\n",
        },
        {
            name: "Text search",
            query: "SELECT ?s ?label WHERE {\n  ?s rdfs:label ?label .\n"
                + "  # Words, \"exact phrases\", prefix* and AND / OR / NOT; case and accents are ignored.\n"
                + "  FILTER(text:matches(?label, \"colosseo OR (roman AND forum*)\"))\n}\nLIMIT 100\n",
        },
        {
            name: "Places within 2 km of a point (try the Map tab)",
            query: "SELECT ?place ?label ?wkt WHERE {\n  ?place geo:hasGeometry ?g .\n  ?g geo:asWKT ?wkt .\n"
                + "  OPTIONAL { ?place rdfs:label ?label }\n"
                + "  FILTER(geof:distance(?wkt, \"POINT(12.4924 41.8902)\"^^geo:wktLiteral, uom:metre) < 2000)\n}\n",
        },
        {
            name: "Geometries inside a region",
            query: "SELECT ?place ?wkt WHERE {\n  ?place geo:hasGeometry ?g .\n  ?g geo:asWKT ?wkt .\n"
                + "  FILTER(geof:sfIntersects(?wkt, \"POLYGON((12.45 41.87, 12.52 41.87, 12.52 41.91, 12.45 41.91, 12.45 41.87))\"^^geo:wktLiteral))\n}\n",
        },
        {
            name: "Load sample data (Rome landmarks)",
            query: "INSERT DATA {\n  GRAPH <" + SAMPLE_GRAPH + "> {\n"
                + "    <urn:ex:colosseum> a <urn:ex:Monument> ; rdfs:label \"Colosseo\"@it, \"Colosseum, the Flavian Amphitheatre\"@en ;\n"
                + "      <urn:ex:visitors> 7600000 ; geo:hasGeometry <urn:ex:colosseum-g> .\n"
                + "    <urn:ex:colosseum-g> geo:asWKT \"POINT(12.4922 41.8902)\"^^geo:wktLiteral .\n"
                + "    <urn:ex:forum> a <urn:ex:Site> ; rdfs:label \"Roman Forum\"@en, \"Foro Romano\"@it ;\n"
                + "      <urn:ex:visitors> 4500000 ; geo:hasGeometry <urn:ex:forum-g> .\n"
                + "    <urn:ex:forum-g> geo:asWKT \"POLYGON((12.4840 41.8916, 12.4874 41.8910, 12.4878 41.8929, 12.4846 41.8935, 12.4840 41.8916))\"^^geo:wktLiteral .\n"
                + "    <urn:ex:pantheon> a <urn:ex:Monument> ; rdfs:label \"Pantheon\" ;\n"
                + "      <urn:ex:visitors> 9000000 ; geo:hasGeometry <urn:ex:pantheon-g> .\n"
                + "    <urn:ex:pantheon-g> geo:asWKT \"POINT(12.4768 41.8986)\"^^geo:wktLiteral .\n"
                + "    <urn:ex:trevi> a <urn:ex:Fountain> ; rdfs:label \"Fontana di Trevi\"@it, \"Trevi Fountain\"@en ;\n"
                + "      <urn:ex:visitors> 3000000 ; geo:hasGeometry <urn:ex:trevi-g> .\n"
                + "    <urn:ex:trevi-g> geo:asWKT \"POINT(12.4833 41.9009)\"^^geo:wktLiteral .\n"
                + "    <urn:ex:appia> a <urn:ex:Road> ; rdfs:label \"Via Appia Antica\" ;\n"
                + "      geo:hasGeometry <urn:ex:appia-g> .\n"
                + "    <urn:ex:appia-g> geo:asWKT \"LINESTRING(12.4995 41.8726, 12.5106 41.8604, 12.5205 41.8501, 12.5340 41.8363)\"^^geo:wktLiteral .\n"
                + "  }\n}\n",
        },
        {
            name: "Remove sample data",
            query: "DROP SILENT GRAPH <" + SAMPLE_GRAPH + ">\n",
        },
    ];

    // ---- authorizations: sent as auths=, which can only narrow what the server allows ----

    const AUTHS_KEY = "rya-ng.auths";
    const authsInput = document.getElementById("rya-auths");
    try {
        authsInput.value = window.localStorage.getItem(AUTHS_KEY) || "";
    } catch (e) {
        // Storage can be unavailable (private windows); the field simply starts empty.
    }
    authsInput.addEventListener("change", () => {
        try {
            window.localStorage.setItem(AUTHS_KEY, authsInput.value.trim());
        } catch (e) {
            // Not remembered, but still used for this page.
        }
    });

    function authsArgs() {
        const labels = authsInput.value.split(",").map((s) => s.trim()).filter(Boolean);
        return labels.length ? [{ name: "auths", value: labels.join(",") }] : [];
    }

    // ---- YASGUI ----

    Yasgui.Yasr.defaults.pluginOrder = ["table", "Map", "Chart", "response"];
    Yasgui.Yasr.defaults.prefixes = {
        rdf: "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        rdfs: "http://www.w3.org/2000/01/rdf-schema#",
        xsd: "http://www.w3.org/2001/XMLSchema#",
        geo: "http://www.opengis.net/ont/geosparql#",
    };

    const endpoint = new URL("sparql", window.location.href).href;
    const yasgui = new Yasgui(document.getElementById("yasgui"), {
        requestConfig: { endpoint: endpoint, method: "POST" },
        copyEndpointOnNewTab: false,
        persistenceId: "rya-ng",
        yasqe: { value: PREFIXES + EXAMPLES[0].query },
    });
    window.ryaYasgui = yasgui;

    // Fired synchronously before the request is built: put the current authorizations on the
    // tab, keeping any other request arguments set in the tab's settings.
    yasgui.on("queryBefore", (instance, tab) => {
        const current = tab.getRequestConfig().args;
        const others = (Array.isArray(current) ? current : []).filter((a) => a.name !== "auths");
        tab.setRequestConfig({ args: others.concat(authsArgs()) });
    });

    // ---- examples ----

    const examples = document.getElementById("rya-examples");
    EXAMPLES.forEach((example, i) => {
        const option = document.createElement("option");
        option.value = String(i);
        option.textContent = example.name;
        examples.appendChild(option);
    });
    examples.addEventListener("change", () => {
        const example = EXAMPLES[Number(examples.value)];
        examples.value = "";
        if (example) {
            yasgui.addTab(true, { name: example.name, yasqe: { value: PREFIXES + example.query } });
        }
    });
})();
