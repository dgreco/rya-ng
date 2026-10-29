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
 * The "Accumulo trace" side panel: what a query does in Accumulo, live. Before each traced
 * query, rya-ui.js calls RyaTrace.begin(tabId), sends the id it returns as trace=, and this file
 * reads the server-sent events of sparql/trace/<id>: notes, plans, scans, progress, flushes, a
 * summary and the end. Everything from the server is written as text, never as HTML.
 *
 * Each query tab has its own trace, which keeps streaming while another tab is selected. The
 * panel shows the selected tab's trace: rya-ui.js calls RyaTrace.show(tabId) when the selected
 * tab changes, and RyaTrace.drop(tabId) when a tab closes.
 *
 * The panel (#rya-trace) sits right of the editor and results. The divider (#rya-trace-resizer)
 * resizes it by dragging or with the arrow keys; it folds into a vertical tab (#rya-trace-tab).
 * The browser remembers its width and whether it is folded.
 */
(function () {
    "use strict";

    const MAX_ROWS = 1000;
    const RANGES_SHOWN = 3;
    const MIN_WIDTH = 300;
    const DEFAULT_WIDTH = 520;
    const STEP = 32;
    const WIDTH_KEY = "rya-ng.trace.width";
    const FOLDED_KEY = "rya-ng.trace.folded";

    const panel = document.getElementById("rya-trace");
    const resizer = document.getElementById("rya-trace-resizer");
    const tab = document.getElementById("rya-trace-tab");
    const bar = document.querySelector(".rya-bar");

    let enabled = false;
    let folded = load(FOLDED_KEY) === "yes";
    let width = Number(load(WIDTH_KEY)) || DEFAULT_WIDTH;

    function load(key) {
        try {
            return window.localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function save(key, value) {
        try {
            window.localStorage.setItem(key, value);
        } catch (e) {
            // Not remembered.
        }
    }

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

    function plural(n, word) {
        return fmt(n) + " " + word + (n === 1 ? "" : "s");
    }

    function entries(n) {
        return fmt(n) + (n === 1 ? " entry" : " entries");
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

    function newId() {
        const bytes = new Uint8Array(16);
        window.crypto.getRandomValues(bytes);
        return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    }

    // ---- one trace: its stream, its state and its own elements ----

    /**
     * A trace owns its status, its totals and its body, and updates them whether or not it is
     * the one in the panel. Showing another trace just swaps those three elements.
     */
    function newTrace() {
        const status = el("span", "rya-trace-status", "idle");
        const totals = el("div", "rya-trace-totals");
        const body = el("div", "rya-trace-body");
        const hint = el("p", "rya-trace-hint", "Run a query: its plan, every Accumulo scan and every write show up here while it runs.");
        const notes = el("div", "rya-trace-notes");
        const plans = el("div", "rya-trace-plans");
        const ops = el("div", "rya-trace-cards");
        const skippedNote = el("div", "rya-trace-skipped");
        const summary = el("div", "rya-trace-summary");
        body.append(hint, notes, plans, ops, skippedNote, summary);

        let source = null;
        let ended = true;
        let clock = null;
        let startedAt = 0;
        let rowsShown = 0;
        let rowsSkipped = 0;
        let rows = new Map();
        let live = { scans: 0, entries: 0, ms: 0, mutations: 0 };

        const trace = {
            status: status,
            totals: totals,
            body: body,
            scrollTop: 0,
            running: () => source !== null,
            begin: begin,
            stop: stop,
        };

        function setStatus(text, kind) {
            status.textContent = text;
            status.className = "rya-trace-status " + (kind || "");
        }

        function showTotals() {
            const parts = [plural(live.scans, "scan"), entries(live.entries), fmt(live.ms, 1) + " ms in Accumulo"];
            if (live.mutations) {
                parts.push(plural(live.mutations, "mutation"));
            }
            totals.textContent = parts.join(" · ");
        }

        function reset() {
            notes.replaceChildren();
            plans.replaceChildren();
            ops.replaceChildren();
            summary.replaceChildren();
            skippedNote.textContent = "";
            hint.hidden = true;
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
            if (clock) {
                window.clearInterval(clock);
                clock = null;
            }
            runningChanged(trace);
        }

        /** While a query runs, the status shows for how long, so the panel never looks stuck. */
        function startClock() {
            startedAt = performance.now();
            clock = window.setInterval(() => {
                setStatus("running · " + fmt((performance.now() - startedAt) / 1000, 1) + " s", "running");
            }, 200);
        }

        // ---- operations: one card each ----

        /** A card: "#id  op  table  +at ms ........ figures", then the details. */
        function addCard(kind, id, op, table, at, details) {
            if (rowsShown >= MAX_ROWS) {
                rowsSkipped++;
                skippedNote.textContent = plural(rowsSkipped, "more operation") + " not listed; the summary counts them all.";
                return null;
            }
            rowsShown++;
            const card = el("div", "rya-trace-card " + kind);
            const top = el("div", "rya-trace-card-head");
            if (id !== null) {
                top.appendChild(el("span", "rya-trace-id", "#" + id));
            }
            top.appendChild(el("span", "rya-trace-op", op));
            top.appendChild(el("span", "rya-trace-table", table));
            top.appendChild(el("span", "rya-trace-at", "+" + fmt(at, 1) + " ms"));
            const figures = el("span", "rya-trace-figures");
            const count = el("span", "rya-trace-count", "…");
            const ms = el("span", "rya-trace-ms", "");
            figures.append(count, ms);
            top.appendChild(figures);
            card.append(top, details);
            ops.appendChild(card);
            return { card: card, details: details, count: count, ms: ms, live: null };
        }

        function details(event) {
            const box = el("div", "rya-trace-what");
            if (event.reason) {
                box.appendChild(el("div", "rya-trace-reason", event.reason));
            }
            const ranges = event.ranges || [];
            const listed = ranges.slice(0, RANGES_SHOWN);
            for (const r of listed) {
                box.appendChild(code("div", "rya-trace-range", r));
            }
            if (event.rangeCount > listed.length) {
                const more = el("details", "rya-trace-more");
                more.appendChild(el("summary", null, plural(event.rangeCount - listed.length, "more range")));
                for (const r of ranges.slice(RANGES_SHOWN)) {
                    more.appendChild(code("div", "rya-trace-range", r));
                }
                if (event.rangeCount > ranges.length) {
                    more.appendChild(el("div", "rya-trace-dim", "(the server lists the first " + ranges.length + " of "
                        + fmt(event.rangeCount) + ")"));
                }
                box.appendChild(more);
            }
            for (const c of event.columns || []) {
                box.appendChild(code("div", "rya-trace-dim", c));
            }
            for (const i of event.iterators || []) {
                box.appendChild(code("div", "rya-trace-dim", "iterator " + i));
            }
            if (event.auths) {
                box.appendChild(el("div", "rya-trace-dim", "authorizations " + event.auths));
            }
            return box;
        }

        function onScan(event) {
            live.scans++;
            showTotals();
            const row = addCard("scan", event.id, event.op, event.table, event.at, details(event));
            if (row) {
                rows.set(event.id, row);
            }
        }

        /** The scans still running: entries so far, time, the last row read, any wait. */
        function onProgress(event) {
            totals.textContent = plural(event.scansSoFar, "scan") + " (" + fmt(event.openScans) + " running) · "
                + fmt(event.entriesSoFar) + " entries so far";
            for (const p of event.scans) {
                const row = rows.get(p.id);
                if (!row) {
                    continue;
                }
                row.card.classList.add("rya-trace-running");
                row.count.textContent = fmt(p.entries) + " so far";
                row.ms.textContent = fmt(p.ms, 1) + " ms";
                if (!row.live) {
                    row.live = el("div", "rya-trace-live");
                    row.details.appendChild(row.live);
                }
                const parts = [];
                if (p.waitingMs >= 1000) {
                    parts.push(el("div", "rya-trace-waiting", "waiting " + fmt(p.waitingMs / 1000, 1) + " s for the tablet servers"));
                }
                if (p.position) {
                    const at = code("div", "rya-trace-position", p.position);
                    at.insertBefore(el("span", "rya-trace-dim", "at "), at.firstChild);
                    parts.push(at);
                } else {
                    parts.push(el("div", "rya-trace-dim", "no entry yet"));
                }
                row.live.replaceChildren(...parts);
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
            row.card.classList.remove("rya-trace-running");
            if (row.live) {
                row.live.remove();
                row.live = null;
            }
            row.count.textContent = event.entries < 0 ? "" : entries(event.entries);
            row.ms.textContent = fmt(event.ms, 1) + " ms";
            if (event.how !== "exhausted" && event.how !== "deleted") {
                row.count.textContent += " (" + event.how + ")";
                row.count.title = "The scan was " + event.how + " before it reached its end";
            }
        }

        function onFlush(event) {
            let mutations = 0;
            const box = el("div", "rya-trace-what");
            box.appendChild(el("div", "rya-trace-reason", "buffered writes sent to the tablet servers"));
            for (const t of event.tables) {
                mutations += t.mutations;
                box.appendChild(el("div", "rya-trace-range", t.table + ": " + plural(t.mutations, "mutation") + ", "
                    + plural(t.cells, "cell") + (t.deletes ? " (" + fmt(t.deletes) + " deletes)" : "") + ", " + fmt(t.bytes) + " bytes"));
            }
            live.mutations += mutations;
            live.ms += event.ms;
            showTotals();
            const row = addCard("flush", null, "flush", plural(event.tables.length, "table"), event.at, box);
            if (row) {
                row.count.textContent = plural(mutations, "mutation");
                row.ms.textContent = fmt(event.ms, 1) + " ms";
            }
        }

        function onSummary(event) {
            live = { scans: event.scans, entries: event.entries, ms: event.scanMs, mutations: event.mutations };
            showTotals();
            const t = el("table", "rya-trace-totals-table");
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
            const scroll = el("div", "rya-trace-scroll");
            scroll.appendChild(t);
            summary.replaceChildren(el("h3", null, "Per table, at " + fmt(event.at, 1) + " ms"), scroll);
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

        /** Starts showing a new trace, replacing this one's previous query, and returns its id. */
        function begin() {
            close();
            reset();
            ended = false;
            setStatus("running", "running");
            startClock();
            const id = newId();
            source = new EventSource(new URL("sparql/trace/" + id, window.location.href).href);
            listen("note", onNote);
            listen("plan", onPlan);
            listen("scan", onScan);
            listen("progress", onProgress);
            listen("scan-end", onScanEnd);
            listen("flush", onFlush);
            listen("summary", onSummary);
            listen("end", (event) => {
                const took = " · " + fmt((performance.now() - startedAt) / 1000, 1) + " s";
                ended = true;
                close();
                setStatus((event.status === "ok" ? "done" : event.status) + took, event.status === "ok" ? "ok" : "failed");
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
            runningChanged(trace);
            return id;
        }

        /** Stops listening. */
        function stop() {
            if (!ended) {
                setStatus("stopped", "failed");
            }
            ended = true;
            close();
        }

        return trace;
    }

    // ---- skeleton: the shared head, then the shown trace's status, totals and body ----

    /** The traces of the query tabs, by YASGUI tab id. */
    const traces = new Map();
    /** What the panel shows for a tab that has no trace yet; never started. */
    const idle = newTrace();
    let shownTabId = null;
    let shown = idle;

    const head = el("div", "rya-trace-head");
    const title = el("h2", "rya-trace-title", "Accumulo trace");
    const fold = el("button", "rya-trace-fold", "⟩");
    fold.type = "button";
    fold.title = "Fold the panel into a tab";
    fold.setAttribute("aria-label", "Fold the trace panel");
    head.append(title, shown.status, fold, shown.totals);
    panel.append(head, shown.body);
    tab.replaceChildren(el("span", null, "Accumulo trace"));

    /** The folded tab blinks while the trace the panel would show is running. */
    function runningChanged(trace) {
        if (trace === shown) {
            tab.classList.toggle("running", trace.running());
        }
    }

    /** Puts another trace in the panel, keeping where each one was scrolled to. */
    function display(trace) {
        if (trace === shown) {
            return;
        }
        if (!panel.hidden) {
            shown.scrollTop = shown.body.scrollTop;
        }
        shown.status.replaceWith(trace.status);
        shown.totals.replaceWith(trace.totals);
        shown.body.replaceWith(trace.body);
        shown = trace;
        trace.body.scrollTop = trace.scrollTop;
        runningChanged(trace);
    }

    // ---- layout ----

    function maxWidth() {
        return Math.max(MIN_WIDTH, Math.floor(window.innerWidth * 0.75));
    }

    function applyWidth(w) {
        width = Math.round(Math.min(Math.max(w, MIN_WIDTH), maxWidth()));
        panel.style.width = width + "px";
        resizer.setAttribute("aria-valuenow", String(width));
        resizer.setAttribute("aria-valuemax", String(maxWidth()));
    }

    let relayoutPending = false;

    /** The editor, the results and the map have to notice their new width. */
    function relayout() {
        if (relayoutPending) {
            return;
        }
        relayoutPending = true;
        window.requestAnimationFrame(() => {
            relayoutPending = false;
            window.dispatchEvent(new Event("resize"));
        });
    }

    function layout() {
        if (!panel.hidden) {
            shown.scrollTop = shown.body.scrollTop;
        }
        panel.hidden = !enabled || folded;
        resizer.hidden = panel.hidden;
        tab.hidden = !enabled || !folded;
        if (!panel.hidden) {
            shown.body.scrollTop = shown.scrollTop;
        }
        relayout();
    }

    function setBarHeight() {
        document.documentElement.style.setProperty("--rya-bar-height", bar.offsetHeight + "px");
    }

    window.addEventListener("resize", () => {
        setBarHeight();
        if (width > maxWidth()) {
            applyWidth(width);
        }
    });
    setBarHeight();
    applyWidth(width);

    fold.addEventListener("click", () => {
        folded = true;
        save(FOLDED_KEY, "yes");
        layout();
        tab.focus();
    });
    tab.addEventListener("click", () => {
        folded = false;
        save(FOLDED_KEY, "no");
        layout();
        fold.focus();
    });

    let drag = null;
    resizer.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        resizer.setPointerCapture(e.pointerId);
        drag = { x: e.clientX, width: width };
        document.body.classList.add("rya-resizing");
    });
    resizer.addEventListener("pointermove", (e) => {
        if (drag) {
            applyWidth(drag.width + drag.x - e.clientX);
        }
    });
    function endDrag() {
        if (!drag) {
            return;
        }
        drag = null;
        document.body.classList.remove("rya-resizing");
        save(WIDTH_KEY, String(width));
        relayout();
    }
    resizer.addEventListener("pointerup", endDrag);
    resizer.addEventListener("pointercancel", endDrag);
    resizer.addEventListener("dblclick", () => {
        applyWidth(DEFAULT_WIDTH);
        save(WIDTH_KEY, String(width));
        relayout();
    });
    resizer.addEventListener("keydown", (e) => {
        const next = { ArrowLeft: width + STEP, ArrowRight: width - STEP, Home: MIN_WIDTH, End: maxWidth() }[e.key];
        if (next !== undefined) {
            e.preventDefault();
            applyWidth(next);
            save(WIDTH_KEY, String(width));
            relayout();
        }
    });

    // ---- API for rya-ui.js ----

    /** Shows the panel (or its tab) while the Trace switch is on; turning it off stops every trace. */
    function setEnabled(on) {
        enabled = on;
        if (!on) {
            traces.forEach((trace) => trace.stop());
        }
        layout();
    }

    /** Starts a new trace for a query tab and returns its id, to send as trace=. */
    function begin(tabId) {
        let trace = traces.get(tabId);
        if (!trace) {
            trace = newTrace();
            traces.set(tabId, trace);
        }
        const id = trace.begin();
        if (tabId === shownTabId) {
            display(trace);
        }
        return id;
    }

    /** Shows the trace of the selected query tab. */
    function show(tabId) {
        shownTabId = tabId;
        display(traces.get(tabId) || idle);
    }

    /** Forgets a tab's trace: the tab closed, or its last query ran without one. */
    function drop(tabId) {
        const trace = traces.get(tabId);
        if (!trace) {
            return;
        }
        trace.stop();
        traces.delete(tabId);
        if (trace === shown) {
            display(idle);
        }
    }

    window.RyaTrace = { begin: begin, show: show, drop: drop, setEnabled: setEnabled };
})();
