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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.eclipse.rdf4j.model.vocabulary.GEOF;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;
import org.locationtech.spatial4j.shape.Point;

class GeoRegionTest {

    @Test
    void ancestorsOfTheCoverAreLookedUpExactly() {
        final GeoRegion region = GeoRegion.intersecting(
                GeoSupport.parse("POLYGON((12.4 41.8, 12.6 41.8, 12.6 42.0, 12.4 42.0, 12.4 41.8))").orElseThrow());
        assertTrue(!region.prefixes().isEmpty() && region.prefixes().size() <= GeoCells.K_QUERY);
        for (final String cell : region.prefixes()) {
            for (int len = 1; len < cell.length(); len++) {
                assertTrue(region.exactCells().contains(cell.substring(0, len)));
            }
        }
        for (final String a : region.exactCells()) {
            assertTrue(region.prefixes().stream().anyMatch(c -> c.startsWith(a) && c.length() > a.length()));
        }
    }

    @Test
    void unitsConvertLikeGeofDistance() {
        assertEquals(2.0, GeoRegion.toDegrees(2, GEOF.UOM_DEGREE).orElseThrow(), 1e-12);
        assertEquals(180.0, GeoRegion.toDegrees(Math.PI, GEOF.UOM_RADIAN).orElseThrow(), 1e-9);
        assertEquals(90.0, GeoRegion.toDegrees(0.5, GEOF.UOM_UNITY).orElseThrow(), 1e-12);
        // One degree of arc on the mean-radius sphere is about 111.195 km.
        assertEquals(1.0, GeoRegion.toDegrees(111_195.0, GEOF.UOM_METRE).orElseThrow(), 1e-4);
        assertEquals(Optional.empty(),
                GeoRegion.toDegrees(1, SimpleValueFactory.getInstance().createIRI("urn:furlong")));
    }

    @Test
    void wholeGlobeDistancesAreNotIndexed() {
        final Point p = GeoSupport.context().getShapeFactory().pointXY(0, 0);
        assertTrue(GeoRegion.pointsWithin(p, 180, GEOF.UOM_DEGREE).isEmpty());
        assertTrue(GeoRegion.pointsWithin(p, 5000, GEOF.UOM_METRE).isPresent());
    }
}
