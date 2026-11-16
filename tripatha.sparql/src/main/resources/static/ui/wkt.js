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
 * GeoSPARQL WKT literals to GeoJSON geometries, for drawing on a map.
 *
 * Handles POINT, LINESTRING, POLYGON, their MULTI forms and GEOMETRYCOLLECTION, with or
 * without Z/M ordinates (which are dropped). An optional leading CRS IRI is honoured: CRS84
 * (the GeoSPARQL default) is longitude/latitude, EPSG:4326 is latitude/longitude and is
 * swapped; any other CRS can't be drawn on a web map, so the literal is rejected.
 *
 * RyaWkt.parse(text) returns a GeoJSON geometry, or null when the text can't be drawn.
 */
(function (root) {
    "use strict";

    const LON_LAT = new Set([
        "http://www.opengis.net/def/crs/OGC/1.3/CRS84",
        "http://www.opengis.net/def/crs/OGC/1.3/CRS84/",
        "http://www.opengis.net/def/crs/OGC/0/CRS84",
    ]);
    const LAT_LON = new Set([
        "http://www.opengis.net/def/crs/EPSG/0/4326",
        "http://www.opengis.net/def/crs/EPSG/0/4326/",
    ]);

    function tokenize(text) {
        const tokens = [];
        const re = /\s*([A-Za-z]+|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?|[(),])/y;
        let m;
        re.lastIndex = 0;
        while (re.lastIndex < text.length) {
            const start = re.lastIndex;
            m = re.exec(text);
            if (!m) {
                if (/^\s*$/.test(text.slice(start))) {
                    break;
                }
                throw new Error("unexpected text at " + start);
            }
            tokens.push(m[1]);
        }
        return tokens;
    }

    function Parser(tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    Parser.prototype.peek = function () {
        return this.tokens[this.pos];
    };

    Parser.prototype.next = function () {
        if (this.pos >= this.tokens.length) {
            throw new Error("unexpected end");
        }
        return this.tokens[this.pos++];
    };

    Parser.prototype.expect = function (token) {
        const t = this.next();
        if (t !== token) {
            throw new Error("expected " + token + ", got " + t);
        }
    };

    Parser.prototype.isNumber = function (t) {
        return t !== undefined && /^[-+.\d]/.test(t);
    };

    /** One position: the first two ordinates; Z/M values are skipped. */
    Parser.prototype.coord = function () {
        const values = [];
        while (this.isNumber(this.peek())) {
            values.push(Number(this.next()));
        }
        if (values.length < 2 || values.length > 4 || values.some(Number.isNaN)) {
            throw new Error("bad coordinate");
        }
        return [values[0], values[1]];
    };

    Parser.prototype.list = function (item) {
        const items = [];
        this.expect("(");
        items.push(item.call(this));
        while (this.peek() === ",") {
            this.next();
            items.push(item.call(this));
        }
        this.expect(")");
        return items;
    };

    Parser.prototype.coords = function () {
        return this.list(this.coord);
    };

    Parser.prototype.rings = function () {
        return this.list(this.coords);
    };

    /** MULTIPOINT ((1 2), (3 4)) and MULTIPOINT (1 2, 3 4) are both valid. */
    Parser.prototype.multiPointItem = function () {
        if (this.peek() === "(") {
            this.next();
            const c = this.coord();
            this.expect(")");
            return c;
        }
        return this.coord();
    };

    Parser.prototype.geometry = function () {
        let type = this.next().toUpperCase();
        let dims = "";
        const suffix = /^(POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON|GEOMETRYCOLLECTION)(ZM|Z|M)$/.exec(type);
        if (suffix) {
            type = suffix[1];
            dims = suffix[2];
        }
        const modifier = (this.peek() || "").toUpperCase();
        if (!dims && (modifier === "Z" || modifier === "M" || modifier === "ZM")) {
            this.next();
        }
        if ((this.peek() || "").toUpperCase() === "EMPTY") {
            this.next();
            return null;
        }
        switch (type) {
            case "POINT": {
                this.expect("(");
                const c = this.coord();
                this.expect(")");
                return { type: "Point", coordinates: c };
            }
            case "LINESTRING":
                return { type: "LineString", coordinates: this.coords() };
            case "POLYGON":
                return { type: "Polygon", coordinates: this.rings() };
            case "MULTIPOINT":
                return { type: "MultiPoint", coordinates: this.list(this.multiPointItem) };
            case "MULTILINESTRING":
                return { type: "MultiLineString", coordinates: this.rings() };
            case "MULTIPOLYGON":
                return { type: "MultiPolygon", coordinates: this.list(this.rings) };
            case "GEOMETRYCOLLECTION": {
                const parts = this.list(this.geometry).filter(Boolean);
                return { type: "GeometryCollection", geometries: parts };
            }
            default:
                throw new Error("unsupported geometry " + type);
        }
    };

    function swap(geometry) {
        const flip = (c) => (typeof c[0] === "number" ? [c[1], c[0]] : c.map(flip));
        if (geometry.type === "GeometryCollection") {
            return { type: geometry.type, geometries: geometry.geometries.map(swap) };
        }
        return { type: geometry.type, coordinates: flip(geometry.coordinates) };
    }

    function parse(text) {
        if (typeof text !== "string") {
            return null;
        }
        let body = text.trim();
        let latLon = false;
        if (body.startsWith("<")) {
            const end = body.indexOf(">");
            if (end < 0) {
                return null;
            }
            const crs = body.slice(1, end).trim();
            if (LAT_LON.has(crs)) {
                latLon = true;
            } else if (!LON_LAT.has(crs)) {
                return null;
            }
            body = body.slice(end + 1);
        }
        try {
            const parser = new Parser(tokenize(body));
            const geometry = parser.geometry();
            if (parser.pos !== parser.tokens.length || !geometry) {
                return null;
            }
            return latLon ? swap(geometry) : geometry;
        } catch (e) {
            return null;
        }
    }

    const api = { parse: parse };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else {
        root.RyaWkt = api;
    }
})(this);
