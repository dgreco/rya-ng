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

/*
 * RDF 1.2 and SPARQL 1.2 on the query page. YASGUI 4 knows SPARQL 1.1 only, so this file adds,
 * without changing YASGUI itself:
 *
 *  - the editor: a query that uses SPARQL 1.2 syntax (triple terms, reified triples, annotations,
 *    VERSION, the triple and direction functions, directional language tags) is no longer marked
 *    as invalid, is highlighted by a tokenizer of our own, and is sent as a query or an update
 *    according to our own reading of it. Queries without that syntax are left to YASGUI.
 *  - the results: triple terms and literals with a base direction are shown in the table, and
 *    graph results holding them are read by an N-Triples 1.2 reader, since YASGUI's own can't read either.
 *
 * Load it after yasgui.min.js and before rya-ui.js.
 */
(function () {
    "use strict";

    // ---- a small SPARQL tokenizer ----

    const QUERY_FORMS = ["SELECT", "CONSTRUCT", "ASK", "DESCRIBE"];
    const UPDATE_FORMS = ["INSERT", "DELETE", "LOAD", "CLEAR", "CREATE", "DROP", "COPY", "MOVE", "ADD"];
    // Function names that only SPARQL 1.2 has. A bare word before "(" is never valid SPARQL 1.1
    // unless it is one of its own functions, so these can't be mistaken.
    const FUNCTIONS_12 = ["TRIPLE", "SUBJECT", "PREDICATE", "OBJECT", "ISTRIPLE", "LANGDIR", "HASLANG", "HASLANGDIR",
        "STRLANGDIR"];
    const KEYWORDS = new Set(QUERY_FORMS.concat(UPDATE_FORMS, FUNCTIONS_12, [
        "BASE", "PREFIX", "VERSION", "DISTINCT", "REDUCED", "AS", "FROM", "NAMED", "WHERE", "GROUP", "BY", "HAVING",
        "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "VALUES", "UNDEF", "OPTIONAL", "GRAPH", "SERVICE", "SILENT", "BIND",
        "MINUS", "UNION", "FILTER", "EXISTS", "NOT", "IN", "DATA", "WITH", "USING", "DEFAULT", "ALL", "INTO", "TO",
        "A", "TRUE", "FALSE", "STR", "LANG", "LANGMATCHES", "DATATYPE", "BOUND", "IRI", "URI", "BNODE", "RAND", "ABS",
        "CEIL", "FLOOR", "ROUND", "CONCAT", "STRLEN", "UCASE", "LCASE", "ENCODE_FOR_URI", "CONTAINS", "STRSTARTS",
        "STRENDS", "STRBEFORE", "STRAFTER", "YEAR", "MONTH", "DAY", "HOURS", "MINUTES", "SECONDS", "TIMEZONE", "TZ",
        "NOW", "UUID", "STRUUID", "MD5", "SHA1", "SHA256", "SHA384", "SHA512", "COALESCE", "IF", "STRLANG", "STRDT",
        "SAMETERM", "ISIRI", "ISURI", "ISBLANK", "ISLITERAL", "ISNUMERIC", "REGEX", "SUBSTR", "REPLACE", "COUNT",
        "SUM", "MIN", "MAX", "AVG", "SAMPLE", "GROUP_CONCAT", "SEPARATOR"]));

    const IRI = /^<[^<>"{}|^`\\\s]*>/;
    const LANG = /^@[a-zA-Z]+(?:-[a-zA-Z0-9]+)*(--(?:ltr|rtl))?/;
    const NAME = /^(?:[A-Za-z_][\w.-]*)?:(?:[\w%:.-]|\\.|[^\x00-\x7f])*/;
    const WORD = /^[A-Za-z_]\w*/;
    const VARIABLE = /^[?$]\w+/;
    const NUMBER = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/;

    /**
     * Reads one token of {@code line} at {@code pos}. {@code state.quote} is the delimiter of a
     * long string left open by an earlier line, if any. Returns {end, type, modern}, where
     * {@code modern} marks syntax that only SPARQL 1.2 has.
     */
    function lex(line, pos, state) {
        if (state.quote) {
            const close = line.indexOf(state.quote, pos);
            if (close < 0) {
                return { end: line.length, type: "string" };
            }
            state.quote = null;
            return { end: close + 3, type: "string" };
        }
        const rest = line.slice(pos);
        const c = rest[0];
        let m = /^\s+/.exec(rest);
        if (m) {
            return { end: pos + m[0].length, type: null };
        }
        if (c === "#") {
            return { end: line.length, type: "comment" };
        }
        if (c === "\"" || c === "'") {
            const long = c + c + c;
            if (rest.startsWith(long)) {
                const close = line.indexOf(long, pos + 3);
                if (close < 0) {
                    state.quote = long;
                    return { end: line.length, type: "string" };
                }
                return { end: close + 3, type: "string" };
            }
            let i = pos + 1;
            while (i < line.length && line[i] !== c) {
                i += line[i] === "\\" ? 2 : 1;
            }
            return { end: Math.min(i + 1, line.length), type: "string" };
        }
        if (c === "@" && (m = LANG.exec(rest))) {
            return { end: pos + m[0].length, type: "lang", modern: !!m[1] };
        }
        for (const mark of ["<<(", ")>>", "<<", ">>", "{|", "|}", "~"]) {
            if (rest.startsWith(mark)) {
                return { end: pos + mark.length, type: "mark", modern: true };
            }
        }
        if (c === "<" && (m = IRI.exec(rest))) {
            return { end: pos + m[0].length, type: "iri" };
        }
        if ((m = VARIABLE.exec(rest))) {
            return { end: pos + m[0].length, type: "variable" };
        }
        if ((m = NAME.exec(rest))) {
            // A name can't end with a dot: that dot ends the triple.
            return { end: pos + m[0].replace(/\.+$/, "").length, type: "name" };
        }
        if ((m = WORD.exec(rest))) {
            const word = m[0].toUpperCase();
            const end = pos + m[0].length;
            const called = /^\s*\(/.test(line.slice(end));
            return {
                end: end,
                type: KEYWORDS.has(word) ? "keyword" : "word",
                word: word,
                modern: word === "VERSION" || (called && FUNCTIONS_12.indexOf(word) >= 0),
            };
        }
        if ((m = NUMBER.exec(rest)) && m[0].length) {
            return { end: pos + m[0].length, type: "number" };
        }
        return { end: pos + 1, type: "punctuation" };
    }

    /**
     * What a query is, from its text alone: {modern} when it uses SPARQL 1.2 syntax, and
     * {queryType}, its first query or update keyword.
     */
    function scan(text) {
        const state = { quote: null };
        const result = { modern: false, queryType: undefined };
        for (const line of String(text).split("\n")) {
            let pos = 0;
            while (pos < line.length) {
                const token = lex(line, pos, state);
                pos = token.end;
                if (token.modern) {
                    result.modern = true;
                }
                if (!result.queryType && token.type === "keyword"
                        && (QUERY_FORMS.indexOf(token.word) >= 0 || UPDATE_FORMS.indexOf(token.word) >= 0)) {
                    result.queryType = token.word;
                }
            }
        }
        return result;
    }

    // ---- RDF terms, in the shape of SPARQL JSON bindings ----
    // {type: "uri" | "bnode" | "literal" | "triple", value, "xml:lang", "its:dir", datatype}.
    // A triple term keeps its parts in {term: {subject, predicate, object}}; its value is its text.

    const XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    function direction(binding) {
        const dir = binding["its:dir"];
        return dir === "rtl" || dir === "ltr" ? dir : null;
    }

    /** The language tag of a literal, with its direction: "ar--rtl". */
    function languageTag(binding) {
        const lang = binding["xml:lang"];
        if (!lang) {
            return "";
        }
        return lang + (direction(binding) ? "--" + direction(binding) : "");
    }

    /** A term as N-Triples 1.2 text. */
    function termText(binding) {
        if (!binding) {
            return "";
        }
        switch (binding.type) {
            case "uri":
                return "<" + binding.value + ">";
            case "bnode":
                return "_:" + binding.value;
            case "triple": {
                const t = binding.term;
                return t ? "<<( " + termText(t.subject) + " " + termText(t.predicate) + " " + termText(t.object) + " )>>"
                        : String(binding.value);
            }
            default: {
                const text = "\"" + String(binding.value).replace(/\\/g, "\\\\").replace(/"/g, "\\\"")
                        .replace(/\n/g, "\\n").replace(/\r/g, "\\r") + "\"";
                if (binding["xml:lang"]) {
                    return text + "@" + languageTag(binding);
                }
                return binding.datatype && binding.datatype !== XSD_STRING ? text + "^^<" + binding.datatype + ">" : text;
            }
        }
    }

    /** Turns a triple term of a SPARQL 1.2 JSON result into the shape above, at any depth. */
    function normalise(binding) {
        if (!binding || binding.type !== "triple" || binding.term) {
            return binding;
        }
        const v = binding.value || {};
        const term = { subject: normalise(v.subject), predicate: normalise(v.predicate), object: normalise(v.object) };
        const result = { type: "triple", term: term };
        result.value = termText(result);
        return result;
    }

    function normaliseResults(json) {
        const rows = json && json.results && json.results.bindings;
        if (Array.isArray(rows)) {
            for (const row of rows) {
                for (const name in row) {
                    row[name] = normalise(row[name]);
                }
            }
        }
        return json;
    }

    // ---- an N-Triples 1.2 reader ----

    function unescape(text, line) {
        return text.replace(/\\(u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8}|.)/g, (all, e) => {
            switch (e[0]) {
                case "u":
                case "U":
                    return String.fromCodePoint(parseInt(e.slice(1), 16));
                case "t": return "\t";
                case "b": return "\b";
                case "n": return "\n";
                case "r": return "\r";
                case "f": return "\f";
                case "\"": case "'": case "\\": return e;
                default:
                    throw new Error("N-Triples, line " + line + ": unknown escape \\" + e);
            }
        });
    }

    /** Reads N-Triples 1.2 into rows of {subject, predicate, object} bindings. */
    function parseNTriples(text) {
        const source = String(text);
        let pos = 0;
        let line = 1;

        function fail(what) {
            throw new Error("N-Triples, line " + line + ": " + what);
        }
        function skip() {
            for (;;) {
                const c = source[pos];
                if (c === "\n") {
                    line++;
                    pos++;
                } else if (c === " " || c === "\t" || c === "\r") {
                    pos++;
                } else if (c === "#") {
                    while (pos < source.length && source[pos] !== "\n") {
                        pos++;
                    }
                } else {
                    return;
                }
            }
        }
        function expect(mark) {
            skip();
            if (!source.startsWith(mark, pos)) {
                fail("expected " + mark);
            }
            pos += mark.length;
        }
        function iri() {
            const end = source.indexOf(">", pos);
            if (source[pos] !== "<" || end < 0) {
                fail("expected an IRI");
            }
            const value = unescape(source.slice(pos + 1, end), line);
            pos = end + 1;
            return { type: "uri", value: value };
        }
        function term() {
            skip();
            if (source.startsWith("<<(", pos)) {
                pos += 3;
                const parts = { subject: term(), predicate: term(), object: term() };
                expect(")>>");
                const result = { type: "triple", term: parts };
                result.value = termText(result);
                return result;
            }
            const c = source[pos];
            if (c === "<") {
                return iri();
            }
            if (c === "_" && source[pos + 1] === ":") {
                const m = /^[^\s<>"]+/.exec(source.slice(pos + 2));
                const label = m ? m[0].replace(/\.+$/, "") : "";
                if (!label) {
                    fail("expected a blank node label");
                }
                pos += 2 + label.length;
                return { type: "bnode", value: label };
            }
            if (c === "\"") {
                let end = pos + 1;
                while (end < source.length && source[end] !== "\"") {
                    if (source[end] === "\n") {
                        fail("unterminated string");
                    }
                    end += source[end] === "\\" ? 2 : 1;
                }
                if (end >= source.length) {
                    fail("unterminated string");
                }
                const result = { type: "literal", value: unescape(source.slice(pos + 1, end), line) };
                pos = end + 1;
                const lang = LANG.exec(source.slice(pos, pos + 128));
                if (lang) {
                    pos += lang[0].length;
                    const tag = lang[0].slice(1);
                    if (lang[1]) {
                        result["xml:lang"] = tag.slice(0, tag.length - lang[1].length);
                        result["its:dir"] = lang[1].slice(2);
                    } else {
                        result["xml:lang"] = tag;
                    }
                } else if (source.startsWith("^^", pos)) {
                    pos += 2;
                    const datatype = iri().value;
                    if (datatype !== XSD_STRING) {
                        result.datatype = datatype;
                    }
                }
                return result;
            }
            return fail("expected a term");
        }

        const rows = [];
        for (;;) {
            skip();
            if (pos >= source.length) {
                return rows;
            }
            if (/^VERSION\b/i.test(source.slice(pos, pos + 8))) {
                // A version directive: nothing to read from it.
                while (pos < source.length && source[pos] !== "\n") {
                    pos++;
                }
                continue;
            }
            const row = { subject: term(), predicate: term(), object: term() };
            expect(".");
            rows.push(row);
        }
    }

    // ---- HTML for the results table ----

    function escapeHtml(text) {
        return String(text).replace(/[&<>"']/g, (c) => "&#" + c.charCodeAt(0) + ";");
    }

    // ---- YASGUI ----

    /** The editor: see the top of this file. */
    function installEditor(Yasqe) {
        const STYLES = {
            comment: "comment", string: "string", lang: "meta", iri: "variable-3", variable: "atom", name: "string-2",
            keyword: "keyword", word: "variable", number: "number", punctuation: "punc",
        };
        // Replaces YASGUI's highlighting (which stops understanding the query at its first 1.2
        // token) while a query uses 1.2 syntax. A CodeMirror overlay can't keep state between
        // lines, so a long string that spans lines is only highlighted on its first one.
        const overlay = {
            token: function (stream) {
                const token = lex(stream.string, stream.pos, { quote: null });
                stream.pos = token.end;
                if (token.type === "mark" || (token.modern && token.type !== "lang")) {
                    return "keyword rya-modern";
                }
                return token.modern ? "meta rya-modern" : STYLES[token.type] || null;
            },
        };

        const checkSyntax = Yasqe.prototype.checkSyntax;
        Yasqe.prototype.checkSyntax = function () {
            checkSyntax.call(this);
            const query = scan(this.getValue());
            if (query.modern !== !!this.ryaModern) {
                this.ryaModern = query.modern;
                if (query.modern) {
                    this.addOverlay(overlay, { opaque: true });
                } else {
                    this.removeOverlay(overlay);
                }
            }
            if (query.modern) {
                // YASGUI's SPARQL 1.1 grammar can't judge this query: the server will.
                this.clearGutter("gutterErrorBar");
                this.queryValid = true;
                this.setOption("queryType", query.queryType);
            }
        };
    }

    /** @return whether a response is Turtle holding RDF 1.2 values: RDF4J starts those, and only those, with a version line. */
    function isModernTurtle(contentType, data) {
        return typeof data === "string" && (contentType || "").indexOf("turtle") >= 0 && /^@version\s+"1\.2"/m.test(data);
    }

    /** The results: see the top of this file. */
    function installResults(Yasr) {
        // YASR's response parser isn't exported: take its class from a response set on a stand-in.
        const probe = { draw: function () {}, storeResponse: function () {} };
        Yasr.prototype.setResponse.call(probe, { content: "{}" });
        const parser = probe.results.constructor.prototype;

        const parse = parser.getParserFromContentType;
        parser.getParserFromContentType = function () {
            const type = this.getContentType() || "";
            const data = this.getData();
            if (typeof data === "string") {
                try {
                    if (type.indexOf("sparql-results+json") >= 0 && data.indexOf("\"triple\"") >= 0) {
                        // YASR reads the values of a triple term as text and fails on the whole result.
                        const json = normaliseResults(JSON.parse(data));
                        this.getData = () => json;
                        try {
                            return parse.call(this);
                        } finally {
                            delete this.getData;
                        }
                    }
                    if (isModernTurtle(type, data)) {
                        // YASR's Turtle reader stops at RDF 1.2: show nothing while rya-ui.js asks again in N-Triples.
                        this.json = { head: { vars: ["subject", "predicate", "object"] }, results: { bindings: [] } };
                        this.type = "ttl";
                        return true;
                    }
                    if (type.indexOf("n-triples") >= 0) {
                        this.json = { head: { vars: ["subject", "predicate", "object"] }, results: { bindings: parseNTriples(data) } };
                        this.type = "ttl";
                        return true;
                    }
                } catch (e) {
                    // Fall back to YASR's own reading, which reports what it can't read.
                    window.console.warn(e);
                }
            }
            return parse.call(this);
        };

        const table = Yasr.plugins.table.prototype;
        const formatLiteral = table.formatLiteral;
        table.formatLiteral = function (binding, prefixes) {
            const dir = direction(binding);
            if (!dir || !binding["xml:lang"]) {
                return formatLiteral.call(this, binding, prefixes);
            }
            const text = "<bdi dir='" + dir + "'>" + escapeHtml(binding.value) + "</bdi>";
            return this.persistentConfig.compact ? text : "\"" + text + "\"<sup>@" + escapeHtml(languageTag(binding)) + "</sup>";
        };

        function termHtml(plugin, binding, prefixes) {
            if (!binding) {
                return "";
            }
            if (binding.type === "triple" && binding.term) {
                const t = binding.term;
                return "<span class='rya-term'><span class='rya-term-mark'>&lt;&lt;(</span> " + termHtml(plugin, t.subject, prefixes)
                        + " " + termHtml(plugin, t.predicate, prefixes) + " " + termHtml(plugin, t.object, prefixes)
                        + " <span class='rya-term-mark'>)&gt;&gt;</span></span>";
            }
            if (binding.type === "uri") {
                return plugin.getUriLinkFromBinding(binding, prefixes);
            }
            return (binding.type === "bnode" ? "_:" : "") + plugin.formatLiteral(binding, prefixes);
        }

        const getCellContent = table.getCellContent;
        table.getCellContent = function (binding, prefixes) {
            if (binding.type === "triple" && binding.term) {
                return "<div><span class='nonIri'>" + termHtml(this, binding, prefixes) + "</span></div>";
            }
            return getCellContent.call(this, binding, prefixes);
        };
    }

    window.RyaSparql12 = {
        scan: scan,
        parseNTriples: parseNTriples,
        normaliseResults: normaliseResults,
        termText: termText,
        languageTag: languageTag,
        isModernTurtle: isModernTurtle,
        /** The content type graph results with RDF 1.2 values must be asked in, for the reader above. */
        GRAPH_RESULTS: "application/n-triples",
    };
    if (window.Yasgui) {
        installEditor(window.Yasgui.Yasqe);
        installResults(window.Yasgui.Yasr);
    }
})();
