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
        "PREFIX stats: <urn:rya-ng:stats#>",
    ].join("\n") + "\n\n";

    const SAMPLE_GRAPH = "urn:rya-ng:sample";

    const EXAMPLES = [
        {
            name: "Browse statements",
            query: "SELECT * WHERE {\n  ?s ?p ?o\n}\nLIMIT 100\n",
        },
        {
            name: "Named graphs and their approximate size",
            query: "# Read from the statistics counters: fast on any store, but approximate.\n"
                + "SELECT ?graph ?statements WHERE {\n  ?graph stats:approxSize ?statements\n}\n"
                + "ORDER BY DESC(?statements)\n",
        },
        {
            name: "Named graphs and their exact size (reads every statement)",
            query: "# Counts every statement, so on a large store it needs a long Timeout.\n"
                + "SELECT ?graph (COUNT(*) AS ?statements) WHERE {\n  GRAPH ?graph { ?s ?p ?o }\n}\n"
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
            name: "Geometries that touch a region",
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
            name: "RDF 1.2: load annotated sample data",
            query: "# {| |} annotates a statement: it says something about the statement itself.\n"
                + "# ~ names the annotation (its reifier); without it the reifier is a blank node.\n"
                + "INSERT DATA {\n  GRAPH <" + SAMPLE_GRAPH + "> {\n"
                + "    <urn:ex:colosseum> <urn:ex:builtBy> <urn:ex:vespasian> ~ <urn:ex:claim1>\n"
                + "      {| <urn:ex:source> <urn:ex:suetonius> ; <urn:ex:certainty> 0.9 |} .\n"
                + "    <urn:ex:pantheon> <urn:ex:builtBy> <urn:ex:agrippa>\n"
                + "      {| <urn:ex:source> <urn:ex:inscription> ; <urn:ex:certainty> 0.4 |} .\n"
                + "    <urn:ex:pantheon> <urn:ex:builtBy> <urn:ex:hadrian> {| <urn:ex:certainty> 0.8 |} .\n"
                + "    # A language tag can carry a text direction.\n"
                + "    <urn:ex:colosseum> rdfs:label \"\u0627\u0644\u0643\u0648\u0644\u0648\u0633\u064a\u0648\u0645\"@ar--rtl, \"\u05e7\u05d5\u05dc\u05d5\u05e1\u05d0\u05d5\u05dd\"@he--rtl, \"Colosseum\"@en--ltr .\n"
                + "  }\n}\n",
        },
        {
            name: "RDF 1.2: statements with their annotations",
            query: "SELECT ?monument ?builder ?certainty ?source WHERE {\n"
                + "  ?monument <urn:ex:builtBy> ?builder {| <urn:ex:certainty> ?certainty |} .\n"
                + "  OPTIONAL { << ?monument <urn:ex:builtBy> ?builder >> <urn:ex:source> ?source }\n}\n"
                + "ORDER BY DESC(?certainty)\n",
        },
        {
            name: "RDF 1.2: triple terms and their parts",
            query: "# A triple term is a value: here, what each annotation is about.\n"
                + "SELECT ?reifier ?term (SUBJECT(?term) AS ?s) (PREDICATE(?term) AS ?p) (OBJECT(?term) AS ?o) WHERE {\n"
                + "  ?reifier rdf:reifies ?term .\n  FILTER(isTRIPLE(?term))\n}\n",
        },
        {
            name: "RDF 1.2: labels with a text direction",
            query: "SELECT ?s ?label (LANG(?label) AS ?language) (LANGDIR(?label) AS ?direction) WHERE {\n"
                + "  ?s rdfs:label ?label .\n  FILTER(hasLANGDIR(?label))\n}\n",
        },
        {
            name: "Remove sample data",
            query: "DROP SILENT GRAPH <" + SAMPLE_GRAPH + ">\n",
        },
    ];

    // ---- request settings, remembered by the browser ----
    // Authorizations are sent as auths=, which can only narrow what the server allows.
    // The timeout is sent as timeout= (seconds), which the server caps.

    function rememberedInput(id, key) {
        const input = document.getElementById(id);
        try {
            input.value = window.localStorage.getItem(key) || "";
        } catch (e) {
            // Storage can be unavailable (private windows); the field simply starts empty.
        }
        input.addEventListener("change", () => {
            try {
                window.localStorage.setItem(key, input.value.trim());
            } catch (e) {
                // Not remembered, but still used for this page.
            }
        });
        return input;
    }

    const authsInput = rememberedInput("rya-auths", "rya-ng.auths");
    const timeoutInput = rememberedInput("rya-timeout", "rya-ng.timeout");

    // The Trace switch: when on, the trace panel (or its tab) is shown on the right, and each
    // query streams its storage operations to it.
    const traceInput = document.getElementById("rya-trace-on");
    try {
        traceInput.checked = window.localStorage.getItem("rya-ng.trace") === "on";
    } catch (e) {
        // Starts off.
    }
    window.RyaTrace.setEnabled(traceInput.checked);
    traceInput.addEventListener("change", () => {
        try {
            window.localStorage.setItem("rya-ng.trace", traceInput.checked ? "on" : "off");
        } catch (e) {
            // Not remembered.
        }
        window.RyaTrace.setEnabled(traceInput.checked);
    });

    function requestArgs() {
        const args = [];
        const labels = authsInput.value.split(",").map((s) => s.trim()).filter(Boolean);
        if (labels.length) {
            args.push({ name: "auths", value: labels.join(",") });
        }
        const timeout = timeoutInput.value.trim();
        if (/^[1-9][0-9]*$/.test(timeout)) {
            args.push({ name: "timeout", value: timeout });
        }
        return args;
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

    /** Queries whose graph results turned out to hold RDF 1.2 values. */
    const modernGraphQueries = new Set();

    // Fired synchronously before the request is built: put the current authorizations, timeout
    // and trace id on the tab, keeping any other request arguments set in the tab's settings.
    // A query run without the trace drops the tab's old trace, which no longer matches its results.
    yasgui.on("queryBefore", (instance, tab) => {
        const current = tab.getRequestConfig().args;
        const others = (Array.isArray(current) ? current : [])
            .filter((a) => a.name !== "auths" && a.name !== "timeout" && a.name !== "trace");
        const args = others.concat(requestArgs());
        if (traceInput.checked) {
            args.push({ name: "trace", value: window.RyaTrace.begin(tab.getId()) });
        } else {
            window.RyaTrace.drop(tab.getId());
        }
        // Graph results are asked in Turtle, unless they hold RDF 1.2 values: those the page reads
        // itself, from N-Triples (see rya-sparql12.js). Set on every request, so that tabs
        // remembered from an earlier version follow too.
        const query = tab.getYasqe() ? tab.getYasqe().getValue() : "";
        const modern = modernGraphQueries.has(query) || window.RyaSparql12.scan(query).modern;
        tab.setRequestConfig({ args: args, acceptHeaderGraph: modern ? window.RyaSparql12.GRAPH_RESULTS : "text/turtle" });
    });

    // A SPARQL 1.1 query can still return RDF 1.2 values. Its Turtle can't be shown, so the query
    // is remembered and run once more, in N-Triples. Fired before the tab holds the response.
    yasgui.on("queryResponse", (instance, tab) => {
        window.setTimeout(() => {
            const results = tab.getYasr() && tab.getYasr().results;
            const query = tab.getYasqe() ? tab.getYasqe().getValue() : "";
            if (results && !modernGraphQueries.has(query)
                    && window.RyaSparql12.isModernTurtle(results.getContentType(), results.getData())) {
                modernGraphQueries.add(query);
                tab.query().catch(() => {});
            }
        }, 0);
    });

    // Each tab keeps its own trace, and the panel shows the selected tab's. YASGUI fires
    // "tabSelect" before it records the new selection, and a tab opened by addTab (the Examples
    // menu, the + button) is selected after "tabAdd" without any "tabSelect", so the panel
    // catches up once YASGUI is done.
    function showSelectedTrace() {
        const tab = yasgui.getTab();
        window.RyaTrace.show(tab ? tab.getId() : null);
    }
    function showSelectedTraceSoon() {
        window.setTimeout(showSelectedTrace, 0);
    }
    yasgui.on("tabSelect", showSelectedTraceSoon);
    yasgui.on("tabAdd", showSelectedTraceSoon);
    yasgui.on("tabClose", (instance, tab) => {
        window.RyaTrace.drop(tab.getId());
        showSelectedTraceSoon();
    });
    showSelectedTrace();

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
