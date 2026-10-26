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
 * The "Map" result tab: draws every geo:wktLiteral of a SELECT result on a Leaflet map. Each
 * shape's popup lists the other values of its row. Offered only when the results hold WKT.
 */
(function () {
    "use strict";

    const WKT_LITERAL = "http://www.opengis.net/ont/geosparql#wktLiteral";
    const MAX_SHAPES = 5000;
    const TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    const ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
    const STYLE = { color: "#0e7490", weight: 2, fillColor: "#22d3ee", fillOpacity: 0.25 };

    function bindings(yasr) {
        const results = yasr.results;
        if (!results || typeof results.getBindings !== "function" || typeof results.getVariables !== "function") {
            return null;
        }
        try {
            return { vars: results.getVariables() || [], rows: results.getBindings() || [] };
        } catch (e) {
            return null;
        }
    }

    function isWkt(value) {
        return value && value.type !== "uri" && value.datatype === WKT_LITERAL;
    }

    /** A popup listing the row's other values, built from text nodes (no HTML injection). */
    function popup(row, vars, shown) {
        const table = document.createElement("table");
        table.className = "rya-map-popup";
        for (const v of vars) {
            const value = row[v];
            if (!value || v === shown) {
                continue;
            }
            const tr = table.insertRow();
            const name = tr.insertCell();
            name.textContent = "?" + v;
            const cell = tr.insertCell();
            cell.textContent = value.value + (value["xml:lang"] ? "@" + value["xml:lang"] : "");
        }
        return table.rows.length ? table : null;
    }

    class RyaMapPlugin {
        constructor(yasr) {
            this.yasr = yasr;
            this.priority = 15;
            this.label = "Map";
            this.map = null;
            this.features = [];
        }

        canHandleResults() {
            const data = bindings(this.yasr);
            return !!data && data.rows.some((row) => data.vars.some((v) => isWkt(row[v])));
        }

        draw() {
            this.destroy();
            const data = bindings(this.yasr);
            const container = document.createElement("div");
            container.className = "rya-map";
            const note = document.createElement("div");
            note.className = "rya-map-note";
            this.yasr.resultsEl.appendChild(container);
            this.yasr.resultsEl.appendChild(note);

            const map = L.map(container, { worldCopyJump: true, preferCanvas: true });
            this.map = map;
            L.tileLayer(TILES, { maxZoom: 19, attribution: ATTRIBUTION }).addTo(map);
            const layer = L.featureGroup().addTo(map);

            let drawn = 0;
            let skipped = 0;
            this.features = [];
            outer:
            for (const row of data ? data.rows : []) {
                for (const v of data.vars) {
                    if (!isWkt(row[v])) {
                        continue;
                    }
                    if (drawn >= MAX_SHAPES) {
                        break outer;
                    }
                    const geometry = RyaWkt.parse(row[v].value);
                    if (!geometry) {
                        skipped++;
                        continue;
                    }
                    const properties = {};
                    for (const name of data.vars) {
                        if (row[name] && name !== v) {
                            properties[name] = row[name].value;
                        }
                    }
                    const feature = { type: "Feature", geometry: geometry, properties: properties };
                    this.features.push(feature);
                    const shape = L.geoJSON(feature, {
                        style: () => STYLE,
                        pointToLayer: (f, latlng) => L.circleMarker(latlng, Object.assign({ radius: 7 }, STYLE, { fillOpacity: 0.8 })),
                    });
                    const content = popup(row, data.vars, v);
                    if (content) {
                        shape.bindPopup(content, { maxWidth: 420 });
                    }
                    shape.addTo(layer);
                    drawn++;
                }
            }

            const parts = [drawn + (drawn === 1 ? " shape" : " shapes")];
            if (skipped) {
                parts.push(skipped + " not drawn (unsupported WKT or coordinate system)");
            }
            if (drawn >= MAX_SHAPES) {
                parts.push("showing the first " + MAX_SHAPES);
            }
            note.textContent = parts.join(" · ");

            if (drawn > 0) {
                map.fitBounds(layer.getBounds(), { padding: [24, 24], maxZoom: 15 });
            } else {
                map.setView([20, 0], 2);
            }
            // The container may only now have its final size.
            setTimeout(() => map.invalidateSize(), 0);
        }

        getIcon() {
            const icon = document.createElement("div");
            icon.className = "rya-map-icon";
            icon.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">'
                + '<path fill="currentColor" d="M12 2a7 7 0 0 0-7 7c0 5.25 7 13 7 13s7-7.75 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>';
            return icon;
        }

        download() {
            if (!this.features.length) {
                return undefined;
            }
            const collection = { type: "FeatureCollection", features: this.features };
            return {
                contentType: "application/geo+json",
                getData: () => JSON.stringify(collection),
                filename: "results.geojson",
                title: "Download as GeoJSON",
            };
        }

        destroy() {
            if (this.map) {
                this.map.remove();
                this.map = null;
            }
        }
    }

    Yasgui.Yasr.registerPlugin("Map", RyaMapPlugin);
})();
