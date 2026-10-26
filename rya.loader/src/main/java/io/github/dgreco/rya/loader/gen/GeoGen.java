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
package io.github.dgreco.rya.loader.gen;

/**
 * Synthetic geography. 200 countries have a centre and a radius (away from the dateline and the
 * poles); cities lie around their country's centre; landmarks, sensors and offices lie close to
 * their city. Polygons are star-shaped around a centre, so they are always simple (valid).
 */
public final class GeoGen {
    public static final int COUNTRIES = 200;

    private final double[] centreLon = new double[COUNTRIES];
    private final double[] centreLat = new double[COUNTRIES];
    private final double[] radius = new double[COUNTRIES];

    public GeoGen(final long seed) {
        for (int c = 0; c < COUNTRIES; c++) {
            final SplitMix64 r = new SplitMix64(SplitMix64.mix64(seed ^ 0xC0C0_0000L ^ c));
            centreLon[c] = -160 + 320 * r.nextDouble();
            centreLat[c] = -55 + 120 * r.nextDouble();
            radius[c] = 1 + 7 * r.nextDouble();
        }
    }

    public double countryLon(final int c) {
        return centreLon[c];
    }

    public double countryLat(final int c) {
        return centreLat[c];
    }

    public double countryRadius(final int c) {
        return radius[c];
    }

    /** A point near (lon, lat): normal jitter with the given spread, kept on the map. */
    public static double[] near(final SplitMix64 r, final double lon, final double lat, final double spread) {
        final double x = clamp(lon + r.nextGaussian() * spread, -179.5, 179.5);
        final double y = clamp(lat + r.nextGaussian() * spread, -84, 84);
        return new double[] {x, y};
    }

    public static String point(final double[] p) {
        return "POINT(" + LiteralForms.coordinate(p[0]) + " " + LiteralForms.coordinate(p[1]) + ")";
    }

    /** A star-shaped polygon of 6–16 vertices around (lon, lat), with radius up to {@code r} degrees. */
    public static String polygon(final SplitMix64 rnd, final double lon, final double lat, final double r) {
        final int n = rnd.between(6, 16);
        final double step = 2 * Math.PI / n;
        final StringBuilder b = new StringBuilder("POLYGON((");
        String first = null;
        for (int i = 0; i < n; i++) {
            final double angle = i * step + (rnd.nextDouble() - 0.5) * 0.6 * step;
            final double rr = r * (0.6 + 0.4 * rnd.nextDouble());
            final String vertex = LiteralForms.coordinate(clamp(lon + rr * Math.cos(angle), -179.9, 179.9)) + " "
                    + LiteralForms.coordinate(clamp(lat + rr * Math.sin(angle), -89.9, 89.9));
            if (first == null) {
                first = vertex;
            } else {
                b.append(", ");
            }
            b.append(vertex);
        }
        return b.append(", ").append(first).append("))").toString();
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
