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
package io.github.dgreco.rya.rdftriplestore.evaluation.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Geo filters return the same rows with the index as without it, and use it when they can. */
class GeoSearchIT extends IndexITBase {
    private static final String DATA = """
            INSERT DATA {
              <urn:rome> geo:hasGeometry <urn:rome-g> . <urn:rome-g> geo:asWKT "POINT(12.4964 41.9028)"^^geo:wktLiteral .
              <urn:ostia> geo:hasGeometry <urn:ostia-g> . <urn:ostia-g> geo:asWKT "POINT(12.2858 41.7550)"^^geo:wktLiteral .
              <urn:paris> geo:hasGeometry <urn:paris-g> . <urn:paris-g> geo:asWKT "POINT(2.3522 48.8566)"^^geo:wktLiteral .
              <urn:london> geo:hasGeometry <urn:london-g> . <urn:london-g> geo:asWKT "POINT(-0.1276 51.5072)"^^geo:wktLiteral .
              <urn:lazio> geo:hasGeometry <urn:lazio-g> .
              <urn:lazio-g> geo:asWKT "POLYGON((11.4 40.8, 14.0 40.8, 14.0 42.9, 11.4 42.9, 11.4 40.8))"^^geo:wktLiteral .
              <urn:tiber> geo:hasGeometry <urn:tiber-g> .
              <urn:tiber-g> geo:asWKT "LINESTRING(12.40 41.70, 12.48 41.90, 12.52 42.10)"^^geo:wktLiteral .
              <urn:fiji-e> geo:hasGeometry <urn:fiji-e-g> . <urn:fiji-e-g> geo:asWKT "POINT(179.9 -17.8)"^^geo:wktLiteral .
              <urn:fiji-w> geo:hasGeometry <urn:fiji-w-g> . <urn:fiji-w-g> geo:asWKT "POINT(-179.9 -17.9)"^^geo:wktLiteral .
              <urn:pole> geo:hasGeometry <urn:pole-g> . <urn:pole-g> geo:asWKT "POINT(10 89.99)"^^geo:wktLiteral .
              <urn:bad> geo:hasGeometry <urn:bad-g> . <urn:bad-g> geo:asWKT "POINT(abc)"^^geo:wktLiteral .
              <urn:plain> geo:hasGeometry <urn:plain-g> . <urn:plain-g> geo:asWKT "POINT(12.49 41.89)" .
              GRAPH <urn:g1> {
                <urn:florence> geo:hasGeometry <urn:florence-g> .
                <urn:florence-g> geo:asWKT "POINT(11.2558 43.7696)"^^geo:wktLiteral }
            }""";
    private static final String ROME_BOX = wkt("POLYGON((12.3 41.7, 12.7 41.7, 12.7 42.1, 12.3 42.1, 12.3 41.7))");
    private static final String INSIDE_LAZIO = wkt("POLYGON((13 41, 13.1 41, 13.1 41.1, 13 41.1, 13 41))");
    private static final String DATELINE = wkt("POLYGON((179 -19, -179 -19, -179 -16, 179 -16, 179 -19))");
    private static final String POLAR = wkt("POLYGON((0 85, 20 85, 20 90, 0 90, 0 85))");
    private static final String TUSCANY = wkt("POLYGON((10 42.5, 12.3 42.5, 12.3 44.4, 10 44.4, 10 42.5))");
    private static final String ROME = wkt("POINT(12.4964 41.9028)");

    private SailRepository repo;

    private static String wkt(final String wkt) {
        return "\"" + wkt + "\"^^geo:wktLiteral";
    }

    private static String where(final String filter) {
        return "SELECT ?f ?w WHERE { ?f geo:hasGeometry ?g . ?g geo:asWKT ?w FILTER(" + filter + ") }";
    }

    private static Set<String> features(final Map<Map<String, Value>, Integer> rows) {
        return rows.keySet().stream().map(r -> r.get("f").stringValue()).collect(Collectors.toSet());
    }

    @BeforeEach
    void load() {
        repo = open(indexedConf(), false);
        update(repo, DATA);
    }

    @Test
    void relationsAgreeInBothArgumentOrders() {
        for (final String fn : List.of("sfEquals", "sfIntersects", "sfTouches", "sfCrosses", "sfWithin", "sfContains",
                "sfOverlaps", "ehEquals", "ehMeet", "ehOverlap", "ehCovers", "ehCoveredBy", "ehInside", "ehContains",
                "rcc8eq", "rcc8ec", "rcc8po", "rcc8tpp", "rcc8tppi", "rcc8ntpp", "rcc8ntppi")) {
            assertAgrees(repo, where("geof:" + fn + "(?w, " + ROME_BOX + ")"), true);
            assertAgrees(repo, where("geof:" + fn + "(" + ROME_BOX + ", ?w)"), true);
        }
        for (final String fn : List.of("sfDisjoint", "ehDisjoint", "rcc8dc")) {
            assertAgrees(repo, where("geof:" + fn + "(?w, " + ROME_BOX + ")"), false);
        }
        // Ostia (12.2858 E) is just west of the box.
        assertEquals(Set.of("urn:rome", "urn:tiber", "urn:lazio"),
                features(assertAgrees(repo, where("geof:sfIntersects(?w, " + ROME_BOX + ")"), true)));
        // The Tiber line ends on the box's edges: still within it.
        assertEquals(Set.of("urn:rome", "urn:tiber"),
                features(assertAgrees(repo, where("geof:sfWithin(?w, " + ROME_BOX + ")"), true)));
        assertAgrees(repo, where("geof:relate(?w, " + ROME_BOX + ", \"T********\")"), true);
        assertAgrees(repo, where("geof:relate(?w, " + ROME_BOX + ", \"FF*FF****\")"), false);
    }

    @Test
    void largeShapesContainingASmallRegionAreFound() {
        // The query region is far smaller than the Lazio polygon's cells: found through its coarse cells.
        assertEquals(Set.of("urn:lazio"),
                features(assertAgrees(repo, where("geof:sfIntersects(?w, " + INSIDE_LAZIO + ")"), true)));
        assertEquals(Set.of("urn:lazio"),
                features(assertAgrees(repo, where("geof:sfContains(?w, " + INSIDE_LAZIO + ")"), true)));
    }

    @Test
    void datelinePolesAndGraphs() {
        assertEquals(Set.of("urn:fiji-e", "urn:fiji-w"),
                features(assertAgrees(repo, where("geof:sfWithin(?w, " + DATELINE + ")"), true)));
        assertEquals(Set.of("urn:pole"), features(assertAgrees(repo, where("geof:sfWithin(?w, " + POLAR + ")"), true)));
        assertEquals(Set.of("urn:florence"), features(assertAgrees(repo,
                "SELECT ?f ?w WHERE { GRAPH <urn:g1> { ?f geo:hasGeometry ?g . ?g geo:asWKT ?w } "
                        + "FILTER(geof:sfWithin(?w, " + TUSCANY + ")) }", null)));
    }

    @Test
    void distancesInEveryUnitAndForm() {
        for (final String unit : List.of("uom:metre 30000", "uom:degree 0.3", "uom:radian 0.005", "uom:unity 0.0017")) {
            final String u = unit.split(" ")[0];
            final String d = unit.split(" ")[1];
            final String fn = "geof:distance(?w, " + ROME + ", " + u + ")";
            final Set<String> lt = features(assertAgrees(repo, where(fn + " < " + d), true));
            assertEquals(Set.of("urn:rome", "urn:ostia"), lt, unit);
            assertEquals(lt, features(assertAgrees(repo, where(fn + " <= " + d), true)));
            assertEquals(lt, features(assertAgrees(repo, where(d + " > " + fn), true)));
            assertEquals(lt, features(assertAgrees(repo, where(d + " >= " + fn), true)));
            assertEquals(lt, features(assertAgrees(repo,
                    where("geof:distance(" + ROME + ", ?w, " + u + ") < " + d), true)));
        }
        // Points farther than a distance: every point but two, so no index.
        assertAgrees(repo, where("geof:distance(?w, " + ROME + ", uom:metre) > 30000"), false);
    }
}
