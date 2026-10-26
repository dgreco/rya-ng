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
package io.github.dgreco.rya.accumulo.index.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.eclipse.rdf4j.query.algebra.evaluation.function.geosparql.SpatialAlgebra;
import org.eclipse.rdf4j.query.algebra.evaluation.function.geosparql.SpatialSupport;
import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.io.GeohashUtils;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.SpatialRelation;

class GeoCellsTest {
    private static final SpatialAlgebra ALGEBRA = SpatialSupport.getSpatialAlgebra();

    private static Shape wkt(final String wkt) {
        return GeoSupport.parse(wkt).orElseThrow(() -> new AssertionError("bad WKT " + wkt));
    }

    private static boolean related(final String a, final String b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    /** Whether some stored cell is found by the query cover (prefix or ancestor lookup). */
    private static boolean found(final List<String> stored, final GeoRegion region) {
        for (final String s : stored) {
            for (final String q : region.prefixes()) {
                if (s.startsWith(q)) {
                    return true;
                }
            }
            if (region.exactCells().contains(s)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void pointIsOneFineCell() {
        final List<String> cells = GeoCells.store(wkt("POINT(12.4924 41.8902)"));
        assertEquals(List.of(GeohashUtils.encodeLatLon(41.8902, 12.4924, GeoCells.MAX_LEVEL)), cells);
    }

    @Test
    void polygonCoverIsBoundedTouchingAndDeterministic() {
        final Shape rome = wkt("POLYGON((12.4 41.8, 12.6 41.8, 12.6 42.0, 12.4 42.0, 12.4 41.8))");
        final List<String> cells = GeoCells.store(rome);
        assertTrue(cells.size() <= GeoCells.K_STORE && !cells.isEmpty(), cells.toString());
        assertEquals(cells, GeoCells.store(wkt("POLYGON((12.4 41.8, 12.6 41.8, 12.6 42.0, 12.4 42.0, 12.4 41.8))")));
        for (final String c : cells) {
            assertNotEquals(SpatialRelation.DISJOINT,
                    rome.relate(GeohashUtils.decodeBoundary(c, GeoSupport.context())), c);
        }
        // Every point inside the polygon falls in some cell.
        final Random random = new Random(1);
        for (int i = 0; i < 2000; i++) {
            final double x = 12.4 + random.nextDouble() * 0.2;
            final double y = 41.8 + random.nextDouble() * 0.2;
            final String p = GeohashUtils.encodeLatLon(y, x, GeoCells.MAX_LEVEL);
            assertTrue(cells.stream().anyMatch(p::startsWith), p);
        }
    }

    @Test
    void datelineAndPolesAreCovered() {
        final Shape acrossDateline = wkt("POLYGON((170 10, -170 10, -170 20, 170 20, 170 10))");
        final List<String> cells = GeoCells.store(acrossDateline);
        final String east = GeohashUtils.encodeLatLon(15, 175, GeoCells.MAX_LEVEL);
        final String west = GeohashUtils.encodeLatLon(15, -175, GeoCells.MAX_LEVEL);
        assertTrue(cells.stream().anyMatch(east::startsWith), cells.toString());
        assertTrue(cells.stream().anyMatch(west::startsWith), cells.toString());

        // A polygon reaching the pole. (A ring from -180 to 180 is read by spatial4j, and so by the
        // geof: functions too, as a zero-width strip, so it is not a useful polar test.)
        final Shape nearPole = wkt("POLYGON((0 80, 60 80, 60 90, 0 90, 0 80))");
        final List<String> polar = GeoCells.store(nearPole);
        for (final double[] p : new double[][] {{89.9, 33}, {90, 30}, {80, 0}}) {
            final String cell = GeohashUtils.encodeLatLon(p[0], p[1], GeoCells.MAX_LEVEL);
            assertTrue(polar.stream().anyMatch(cell::startsWith), cell + " in " + polar);
        }
    }

    /** If two geometries intersect, the query for one always finds the other. */
    @Test
    void intersectingShapesAlwaysShareCells() {
        final Random random = new Random(7);
        int intersecting = 0;
        for (int i = 0; i < 10000; i++) {
            final Shape stored = randomShape(random);
            final Shape query = randomShape(random);
            if (ALGEBRA.sfIntersects(stored, query)) {
                intersecting++;
                assertTrue(found(GeoCells.store(stored), GeoRegion.intersecting(query)),
                        "missed: " + stored + " vs " + query);
            }
        }
        assertTrue(intersecting > 500, "too few intersecting pairs: " + intersecting);
    }

    /** Shapes that touch along a cell edge (coordinates on a coarse grid) are still found. */
    @Test
    void shapesTouchingOnCellEdgesAreFound() {
        // 45 degrees is a level-1 geohash boundary in both directions.
        final Shape left = wkt("POLYGON((0 0, 45 0, 45 10, 0 10, 0 0))");
        final Shape right = wkt("POLYGON((45 5, 50 5, 50 6, 45 6, 45 5))");
        assertTrue(ALGEBRA.sfIntersects(left, right));
        assertTrue(found(GeoCells.store(left), GeoRegion.intersecting(right)));
        assertTrue(found(GeoCells.store(right), GeoRegion.intersecting(left)));
        final Shape cornerPoint = wkt("POINT(45 10)");
        assertTrue(found(GeoCells.store(cornerPoint), GeoRegion.intersecting(left)));
        assertTrue(found(GeoCells.store(left), GeoRegion.intersecting(cornerPoint)));
    }

    @Test
    void pointsWithinADistanceAreFound() {
        final Random random = new Random(3);
        for (int i = 0; i < 200; i++) {
            final Point center = GeoSupport.context().getShapeFactory().pointXY(
                    -179 + random.nextDouble() * 358, -85 + random.nextDouble() * 170);
            final double radius = Math.pow(10, random.nextDouble() * 4 - 3);
            final GeoRegion region = GeoRegion.pointsWithin(center, radius,
                    org.eclipse.rdf4j.model.vocabulary.GEOF.UOM_DEGREE).orElseThrow();
            for (int j = 0; j < 50; j++) {
                final double x = center.getX() + (random.nextDouble() * 2 - 1) * radius * 3;
                final double y = Math.max(-90, Math.min(90, center.getY() + (random.nextDouble() * 2 - 1) * radius));
                final Point p = GeoSupport.context().getShapeFactory().pointXY(normalizeLon(x), y);
                if (GeoSupport.context().calcDistance(center, p) <= radius) {
                    assertTrue(found(GeoCells.store(p), region), p + " within " + radius + " of " + center);
                }
            }
        }
    }

    private static double normalizeLon(final double x) {
        double v = x;
        while (v > 180) {
            v -= 360;
        }
        while (v < -180) {
            v += 360;
        }
        return v;
    }

    private static Shape randomShape(final Random r) {
        // Coordinates on a 1/8-degree grid, so edges and vertices often sit on cell boundaries.
        final double x = (r.nextInt(32) - 16) / 8.0;
        final double y = (r.nextInt(32) - 16) / 8.0;
        final double w = (1 + r.nextInt(24)) / 8.0;
        final double h = (1 + r.nextInt(24)) / 8.0;
        return switch (r.nextInt(3)) {
            case 0 -> wkt(String.format(Locale.ROOT, "POINT(%s %s)", x, y));
            case 1 -> wkt(String.format(Locale.ROOT, "LINESTRING(%s %s, %s %s)", x, y, x + w, y + h));
            default -> wkt(String.format(Locale.ROOT, "POLYGON((%s %s, %s %s, %s %s, %s %s))",
                    x, y, x + w, y, x + w / 2, y + h, x, y));
        };
    }
}
