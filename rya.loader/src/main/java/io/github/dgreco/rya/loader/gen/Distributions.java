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

import java.util.Arrays;

/** Skewed distributions over ranks 0, 1, 2, …: low ranks are the popular ones. */
public final class Distributions {
    private Distributions() {
    }

    /**
     * A bounded power law over [0, n): rank r is drawn with probability proportional to
     * (r + 1)^-a, by inverting the continuous approximation. It works for any n, even billions,
     * without a table. a = 0 is uniform; a = 1 is the classic "80/20" shape.
     */
    public static long powerLaw(final SplitMix64 random, final long n, final double a) {
        if (n <= 1) {
            return 0;
        }
        final double u = random.nextDouble();
        final double x;
        if (Math.abs(a - 1.0) < 1e-9) {
            x = Math.pow(n + 1.0, u);
        } else {
            final double e = 1.0 - a;
            x = Math.pow(1.0 + u * (Math.pow(n + 1.0, e) - 1.0), 1.0 / e);
        }
        return Math.min(n - 1, Math.max(0, (long) Math.floor(x) - 1));
    }

    /** A Zipf distribution over a fixed number of ranks, sampled by binary search in its CDF. */
    public static final class Zipf {
        private final double[] cdf;

        public Zipf(final int size, final double s) {
            this.cdf = new double[size];
            double total = 0;
            for (int k = 0; k < size; k++) {
                total += 1.0 / Math.pow(k + 1, s);
                cdf[k] = total;
            }
            for (int k = 0; k < size; k++) {
                cdf[k] /= total;
            }
        }

        public int sample(final SplitMix64 random) {
            final int i = Arrays.binarySearch(cdf, random.nextDouble());
            return Math.min(cdf.length - 1, i >= 0 ? i : -i - 1);
        }

        public int size() {
            return cdf.length;
        }
    }
}
