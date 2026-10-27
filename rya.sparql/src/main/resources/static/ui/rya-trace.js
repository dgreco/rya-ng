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
 * The "Accumulo trace" panel: what a query does in Accumulo, live. Before each traced query,
 * rya-ui.js calls RyaTrace.begin(), sends the id it returns as trace=, and this file reads the
 * server-sent events of sparql/trace/<id>: notes, plans, scans, flushes, a summary and the end.
 * Everything from the server is written as text (textContent), never as HTML.
 */
(function () {
    "use strict";

    const MAX_ROWS = 1000;
    const RANGES_SHOWN = 3;

    const panel = document.getElementById("rya-trace");
    let source = null;
    let ended = true;
    let rowsShown = 0;
    let rowsSkipped = 0;
    let rows = new Map();
    let live = { scans: 0, entries: 0, ms: 0, mutations: 0 };

    function el(tag, className, text) {
        const e = document.createElement(tag);
        if (className) {
            e.className = className;
        }
        if (text !== undefined && text !== null) {
            e.textContent = String(text);
        }
        return e;
    }

    function fmt(n, digits) {
        return Number(n).toLocaleString(undefined, { maximumFractionDigits: digits === undefined ? 0 : digits });
    }

    /** Text with each ⟨xx⟩ byte shown as a small badge; every piece is a text node. */
    function code(tag, className, text) {
        const e = el(tag, className);
        const s = String(text);
        const re = /⟨([0-9a-f]{2})⟩/g;
        let last = 0;
        let m;
        while ((m = re.exec(s)) !== null) {
            if (m.index > last) {
                e.appendChild(document.createTextNode(s.slice(last, m.index)));
            }
            const b = el("span", "rya-trace-byte", m[1]);
            b.title = "byte 0x" + m[1];
            e.appendChild(b);
            last = re.lastIndex;
        }
        if (last < s.length) {
            e.appendChild(document.createTextNode(s.slice(last)));
        }
        return e;
    }

    function plural(n, word) {
        return fmt(n) + " " + word + (n === 1 ? "" : "s");
    }

    // ---- skeleton ----

    const head = el("div", "rya-trace-head");
    const toggle = el("button", "rya-trace-toggle", "▾");
    toggle.type = "button";
    toggle.setAttribute("aria-expanded", "true");
    toggle.title = "Show or hide the trace";
    const title = el("h2", "rya-trace-title", "Accumulo trace");
    const status = el("span", "rya-trace-status");
    const totals = el("span", "rya-trace-totals");
    head.append(toggle, title, status, totals);

    const body = el("div", "rya-trace-body");
    const notes = el("div", "rya-trace-notes");
    const plans = el("div", "rya-trace-plans");
    const table = el("table", "rya-trace-ops");
    const thead = el("thead");
    const headRow = el("tr");
    for (const h of ["#", "+ms", "Operation", "Table", "What", "Entries", "ms"]) {
        headRow.appendChild(el("th", null, h));
    }
    thead.appendChild(headRow);
    const tbody = el("tbody");
    table.append(thead, tbody);
    const skippedNote = el("div", "rya-trace-skipped");
    const summary = el("div", "rya-trace-summary");
    body.append(notes, plans, table, skippedNote, summary);
    panel.append(head, body);

    toggle.addEventListener("click", () => {
        const open = body.hidden;
        body.hidden = !open;
        toggle.textContent = open ? "▾" : "▸";
        toggle.setAttribute("aria-expanded", String(open));
    });

    // ---- state ----

    function setStatus(text, kind) {
        status.textContent = text;
        status.className = "rya-trace-status " + (kind || "");
    }

    function showTotals() {
        const parts = [plural(live.scans, "scan"), plural(live.entries, "entry").replace("entrys", "entries"),
            fmt(live.ms, 1) + " ms in Accumulo"];
        if (live.mutations) {
            parts.push(plural(live.mutations, "mutation"));
        }
        totals.textContent = parts.join(" · ");
    }

    function reset() {
        notes.replaceChildren();
        plans.replaceChildren();
        tbody.replaceChildren();
        summary.replaceChildren();
        skippedNote.textContent = "";
        rows = new Map();
        rowsShown = 0;
        rowsSkipped = 0;
        live = { scans: 0, entries: 0, ms: 0, mutations: 0 };
        showTotals();
    }

    function close() {
        if (source) {
            source.close();
            source = null;
        }
    }

    function newId() {
        const bytes = new Uint8Array(16);
        window.crypto.getRandomValues(bytes);
        return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    }

    // ---- rows ----

    function addRow(cells) {
        if (rowsShown >= MAX_ROWS) {
            rowsSkipped++;
            skippedNote.textContent = plural(rowsSkipped, "more operation") + " not listed; the summary counts them all.";
            return null;
        }
        rowsShown++;
        const tr = el("tr");
        for (const c of cells) {
            tr.appendChild(c);
        }
        tbody.appendChild(tr);
        return tr;
    }

    function what(event) {
        const td = el("td", "rya-trace-what");
        if (event.reason) {
            td.appendChild(el("div", "rya-trace-reason", event.reason));
        }
        const ranges = event.ranges || [];
        const listed = ranges.slice(0, RANGES_SHOWN);
        for (const r of listed) {
            td.appendChild(code("div", "rya-trace-range", r));
        }
        if (event.rangeCount > listed.length) {
            const more = el("details", "rya-trace-more");
            more.appendChild(el("summary", null, (event.rangeCount - listed.length) + " more range"
                + (event.rangeCount - listed.length === 1 ? "" : "s")));
            for (const r of ranges.slice(RANGES_SHOWN)) {
                more.appendChild(code("div", "rya-trace-range", r));
            }
            if (event.rangeCount > ranges.length) {
                more.appendChild(el("div", "rya-trace-dim", "(the server lists the first " + ranges.length + " of "
                    + fmt(event.rangeCount) + ")"));
            }
            td.appendChild(more);
        }
        const extra = [];
        for (const c of event.columns || []) {
            extra.push(c);
        }
        for (const i of event.iterators || []) {
            extra.push("iterator " + i);
        }
        if (event.auths) {
            extra.push("authorizations " + event.auths);
        }
        for (const x of extra) {
            td.appendChild(code("div", "rya-trace-dim", x));
        }
        return td;
    }

    function onScan(event) {
        live.scans++;
        showTotals();
        const entries = el("td", "rya-trace-num", "…");
        const ms = el("td", "rya-trace-num", "…");
        const tr = addRow([el("td", "rya-trace-num", event.id), el("td", "rya-trace-num", fmt(event.at, 1)),
            el("td", "rya-trace-op", event.op), el("td", "rya-trace-table", event.table), what(event), entries, ms]);
        if (tr) {
            rows.set(event.id, { entries: entries, ms: ms });
        }
    }

    function onScanEnd(event) {
        if (event.entries > 0) {
            live.entries += event.entries;
        }
        live.ms += event.ms;
        showTotals();
        const row = rows.get(event.id);
        if (!row) {
            return;
        }
        row.entries.textContent = event.entries < 0 ? "—" : fmt(event.entries);
        row.ms.textContent = fmt(event.ms, 1);
        if (event.how !== "exhausted" && event.how !== "deleted") {
            row.entries.title = "The scan was " + event.how + " before it reached its end";
            row.entries.textContent += " (" + event.how + ")";
        }
    }

    function onFlush(event) {
        let mutations = 0;
        const td = el("td", "rya-trace-what");
        td.appendChild(el("div", "rya-trace-reason", "buffered writes sent to the tablet servers"));
        for (const t of event.tables) {
            mutations += t.mutations;
            td.appendChild(el("div", "rya-trace-range", t.table + ": " + plural(t.mutations, "mutation") + ", "
                + plural(t.cells, "cell") + (t.deletes ? " (" + fmt(t.deletes) + " deletes)" : "") + ", " + fmt(t.bytes) + " bytes"));
        }
        live.mutations += mutations;
        live.ms += event.ms;
        showTotals();
        addRow([el("td", "rya-trace-num", ""), el("td", "rya-trace-num", fmt(event.at, 1)), el("td", "rya-trace-op", "flush"),
            el("td", "rya-trace-table", event.tables.length + " tables"), td, el("td", "rya-trace-num", fmt(mutations)),
            el("td", "rya-trace-num", fmt(event.ms, 1))]);
    }

    function onSummary(event) {
        live = { scans: event.scans, entries: event.entries, ms: event.scanMs, mutations: event.mutations };
        showTotals();
        const t = el("table", "rya-trace-ops rya-trace-totals-table");
        const h = el("tr");
        for (const name of ["Table", "Scans", "Ranges", "Entries", "Scan ms", "Mutations", "Cells", "Deletes", "Bytes"]) {
            h.appendChild(el("th", null, name));
        }
        const th = el("thead");
        th.appendChild(h);
        const tb = el("tbody");
        for (const x of event.tables) {
            const r = el("tr");
            r.appendChild(el("td", "rya-trace-table", x.table));
            for (const v of [x.scans, x.ranges, x.entries, x.ms, x.mutations, x.cells, x.deletes, x.bytes]) {
                r.appendChild(el("td", "rya-trace-num", fmt(v, 1)));
            }
            tb.appendChild(r);
        }
        t.append(th, tb);
        summary.replaceChildren(el("h3", null, "Per table, at " + fmt(event.at, 1) + " ms"), t);
        if (event.omitted > 0) {
            summary.appendChild(el("div", "rya-trace-dim", plural(event.omitted, "operation")
                + " were counted but not reported one by one."));
        }
    }

    function onNote(event) {
        const line = el("div", "rya-trace-note");
        line.appendChild(el("span", "rya-trace-note-kind", event.kind));
        line.appendChild(el(event.kind === "query" || event.kind === "update" ? "pre" : "span", "rya-trace-note-text", event.text));
        notes.appendChild(line);
    }

    function onPlan(event) {
        const d = el("details", "rya-trace-plan");
        d.open = event.stage === "optimized";
        d.appendChild(el("summary", null, "Plan (" + event.stage + ")"));
        d.appendChild(el("pre", null, event.tree));
        plans.appendChild(d);
    }

    function listen(name, handler) {
        source.addEventListener(name, (e) => {
            try {
                handler(JSON.parse(e.data));
            } catch (err) {
                console.error("rya-ng trace: bad " + name + " event", err);
            }
        });
    }

    /** Starts showing a new trace and returns its id, to send as trace=. */
    function begin() {
        close();
        reset();
        panel.hidden = false;
        ended = false;
        setStatus("running", "running");
        const id = newId();
        source = new EventSource(new URL("sparql/trace/" + id, window.location.href).href);
        listen("note", onNote);
        listen("plan", onPlan);
        listen("scan", onScan);
        listen("scan-end", onScanEnd);
        listen("flush", onFlush);
        listen("summary", onSummary);
        listen("end", (event) => {
            ended = true;
            close();
            setStatus(event.status === "ok" ? "done" : event.status, event.status === "ok" ? "ok" : "failed");
            if (event.dropped > 0) {
                skippedNote.textContent = plural(event.dropped, "event") + " were dropped before this page connected.";
            }
        });
        source.onerror = () => {
            if (!ended) {
                ended = true;
                close();
                setStatus("stream lost", "failed");
            }
        };
        return id;
    }

    /** Stops listening (tracing switched off). */
    function stop() {
        close();
        ended = true;
    }

    window.RyaTrace = { begin: begin, stop: stop };
})();
