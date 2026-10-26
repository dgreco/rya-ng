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

import java.util.Optional;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.query.algebra.evaluation.function.geosparql.SpatialSupport;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Shape;

/**
 * Reads WKT the same way RDF4J's {@code geof:} functions do (same spatial context, same reader),
 * so the geo index and the filters that check its results agree on every geometry.
 */
public final class GeoSupport {
    private GeoSupport() {
    }

    /** The spatial context of RDF4J's GeoSPARQL functions (JTS, geodetic, dateline-aware). */
    public static SpatialContext context() {
        return SpatialSupport.getSpatialContext();
    }

    /** Whether a literal datatype is {@code geo:wktLiteral}, the only one the functions accept. */
    public static boolean isWkt(final IRI datatype) {
        return GEO.WKT_LITERAL.equals(datatype);
    }

    /** Parses a WKT label; empty when it is not valid WKT. */
    public static Optional<Shape> parse(final String wkt) {
        try {
            return Optional.ofNullable(context().getFormats().getWktReader().read(wkt));
        } catch (final Exception e) {
            return Optional.empty();
        }
    }
}
