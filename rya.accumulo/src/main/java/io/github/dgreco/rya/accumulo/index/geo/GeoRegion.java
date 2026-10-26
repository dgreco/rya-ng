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

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.GEOF;
import org.locationtech.spatial4j.distance.DistanceUtils;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;

/**
 * Which index cells may hold geometries that intersect a query region.
 *
 * @param prefixes cells whose rows, and the rows of every finer cell inside them, are scanned
 * @param exactCells coarser cells (ancestors of {@code prefixes}) whose own rows are scanned:
 *        a large geometry stored there contains the query cell
 */
public record GeoRegion(List<String> prefixes, List<String> exactCells) {
    /** Mean Earth radius RDF4J's {@code geof:distance} uses for metres. */
    public static final double EARTH_MEAN_RADIUS_KM = 6371.0087714;

    public GeoRegion {
        prefixes = List.copyOf(prefixes);
        exactCells = List.copyOf(exactCells);
    }

    /** Geometries that may intersect {@code region}. */
    public static GeoRegion intersecting(final Shape region) {
        final List<String> cover = GeoCells.cover(region, GeoCells.K_QUERY);
        final TreeSet<String> ancestors = new TreeSet<>();
        for (final String cell : cover) {
            for (int len = 1; len < cell.length(); len++) {
                ancestors.add(cell.substring(0, len));
            }
        }
        return new GeoRegion(cover, List.copyOf(ancestors));
    }

    /**
     * Points that may lie within {@code distance} of {@code center}. Only points are looked up:
     * {@code geof:distance} rejects other geometries. Empty when the circle covers the whole globe.
     */
    public static Optional<GeoRegion> pointsWithin(final Point center, final double distance, final IRI unit) {
        final Optional<Double> degrees = toDegrees(distance, unit);
        if (degrees.isEmpty() || degrees.get() >= 180) {
            return Optional.empty();
        }
        // Slightly larger than asked, so rounding can never drop a point on the circle.
        final double radius = degrees.get() * (1 + 1e-6) + 1e-9;
        final Shape circle = GeoSupport.context().getShapeFactory().circle(center, radius);
        return Optional.of(new GeoRegion(GeoCells.cover(circle, GeoCells.K_QUERY), List.of()));
    }

    /** Converts a distance to degrees exactly as {@code geof:distance} does; empty for other units. */
    public static Optional<Double> toDegrees(final double distance, final IRI unit) {
        if (GEOF.UOM_DEGREE.equals(unit)) {
            return Optional.of(distance);
        }
        if (GEOF.UOM_RADIAN.equals(unit)) {
            return Optional.of(DistanceUtils.toDegrees(distance));
        }
        if (GEOF.UOM_UNITY.equals(unit)) {
            return Optional.of(distance * 180);
        }
        if (GEOF.UOM_METRE.equals(unit)) {
            return Optional.of(DistanceUtils.dist2Degrees(distance / 1000, EARTH_MEAN_RADIUS_KM));
        }
        return Optional.empty();
    }
}
