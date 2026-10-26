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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.io.GeohashUtils;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Rectangle;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.SpatialRelation;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;

/**
 * Covers a shape with geohash cells. A point gets one cell at {@value #MAX_LEVEL} characters
 * (a few centimetres); a line or polygon gets up to K cells, as fine as K allows. The union of
 * the cells always contains the shape, so two shapes that intersect have covers with a cell in
 * common or one cell inside another (one geohash a prefix of the other).
 * <p>
 * Cells are compared with the shape after growing them by {@value #EPS} degrees, so shapes that
 * only touch on a cell edge still share a cell. The rules are part of the stored index definition
 * ({@link #DEFINITION}): changing them requires rebuilding the index.
 */
public final class GeoCells {
    /** Finest geohash length. Points are stored at this level. */
    public static final int MAX_LEVEL = 12;
    /** Most cells stored for one geometry. */
    public static final int K_STORE = 32;
    /** Most cells used to look up one query region. */
    public static final int K_QUERY = 64;
    /** How much each cell is grown, in degrees, before it is compared with a shape. */
    public static final double EPS = 1e-9;
    /** Part of the stored index definition. */
    public static final String DEFINITION = "geo/1;k=" + K_STORE + ";lvl=" + MAX_LEVEL + ";eps=" + EPS;

    private GeoCells() {
    }

    /** The cells a stored geometry is indexed under. */
    public static List<String> store(final Shape shape) {
        return cover(shape, K_STORE);
    }

    /**
     * Covers {@code shape} with at most {@code maxCells} geohash cells (more only if the shape
     * needs more top-level cells than that). The result is deterministic and sorted.
     */
    public static List<String> cover(final Shape shape, final int maxCells) {
        if (shape.isEmpty()) {
            return List.of();
        }
        if (shape instanceof Point p) {
            return List.of(GeohashUtils.encodeLatLon(p.getY(), p.getX(), MAX_LEVEL));
        }
        if (shape instanceof JtsGeometry g) {
            g.index();
        }
        final Cover cover = new Cover(shape);
        final Rectangle box = shape.getBoundingBox();
        final List<String> cells = new ArrayList<>();
        final String start = box.getCrossesDateLine() ? ""
                : commonPrefix(GeohashUtils.encodeLatLon(box.getMinY(), box.getMinX(), MAX_LEVEL),
                        GeohashUtils.encodeLatLon(box.getMaxY(), box.getMaxX(), MAX_LEVEL));
        if (start.isEmpty()) {
            cells.addAll(cover.children(""));
        } else {
            cells.add(start);
        }
        final Comparator<String> coarseFirst = Comparator.comparingInt(String::length)
                .thenComparing(Comparator.naturalOrder());
        boolean refined = true;
        while (refined) {
            refined = false;
            cells.sort(coarseFirst);
            for (int i = 0; i < cells.size(); i++) {
                final String cell = cells.get(i);
                if (cell.length() >= MAX_LEVEL || cover.relation(cell) == SpatialRelation.CONTAINS) {
                    continue;
                }
                final List<String> children = cover.children(cell);
                if (cells.size() - 1 + children.size() <= maxCells) {
                    cells.remove(i);
                    cells.addAll(children);
                    refined = true;
                    break;
                }
            }
        }
        cells.sort(Comparator.naturalOrder());
        return List.copyOf(cells);
    }

    /** The cell's rectangle grown by {@link #EPS} on every side (clamped to the world). */
    static Rectangle grownBoundary(final String cell, final SpatialContext ctx) {
        final Rectangle r = GeohashUtils.decodeBoundary(cell, ctx);
        return ctx.makeRectangle(Math.max(-180, r.getMinX() - EPS), Math.min(180, r.getMaxX() + EPS),
                Math.max(-90, r.getMinY() - EPS), Math.min(90, r.getMaxY() + EPS));
    }

    private static String commonPrefix(final String a, final String b) {
        int n = 0;
        while (n < a.length() && n < b.length() && a.charAt(n) == b.charAt(n)) {
            n++;
        }
        return a.substring(0, n);
    }

    /** Relations of one shape to cells, cached while one cover is computed. */
    private static final class Cover {
        private final Shape shape;
        private final SpatialContext ctx = GeoSupport.context();
        private final Map<String, SpatialRelation> relations = new HashMap<>();
        private final Map<String, List<String>> children = new HashMap<>();

        Cover(final Shape shape) {
            this.shape = shape;
        }

        /** How the shape relates to the grown cell: CONTAINS means the cell is fully inside the shape. */
        SpatialRelation relation(final String cell) {
            return relations.computeIfAbsent(cell, c -> shape.relate(grownBoundary(c, ctx)));
        }

        /** The sub-cells the shape touches. */
        List<String> children(final String cell) {
            return children.computeIfAbsent(cell, c -> {
                final List<String> touching = new ArrayList<>();
                for (final String child : GeohashUtils.getSubGeohashes(c)) {
                    if (relation(child) != SpatialRelation.DISJOINT) {
                        touching.add(child);
                    }
                }
                return touching;
            });
        }
    }
}
