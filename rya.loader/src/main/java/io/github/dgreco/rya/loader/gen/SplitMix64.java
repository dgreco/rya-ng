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
 * SplitMix64 (Steele, Lea and Flood): a small, fast generator whose output depends only on its
 * seed. Implemented here rather than borrowed from the JDK so generated data never changes with
 * the Java version. Not thread-safe: each entity gets its own instance.
 */
public final class SplitMix64 {
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    public SplitMix64(final long seed) {
        this.state = seed;
    }

    /** The SplitMix64 finalizer: a bijection on 64-bit values that mixes all bits. */
    public static long mix64(final long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long nextLong() {
        state += GOLDEN_GAMMA;
        return mix64(state);
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in [0, bound), for bound &gt; 0. */
    public int nextInt(final int bound) {
        return (int) (((nextLong() >>> 32) * bound) >>> 32);
    }

    /** Uniform in [0, bound), for bound &gt; 0. */
    public long nextLong(final long bound) {
        return Long.remainderUnsigned(nextLong(), bound);
    }

    public boolean chance(final double probability) {
        return probability > 0 && nextDouble() < probability;
    }

    /** Standard normal (Box-Muller). */
    public double nextGaussian() {
        final double u = 1.0 - nextDouble();
        final double v = nextDouble();
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    /** {@code lo} plus the number of successes in {@code hi - lo} trials, so the mean is {@code mean}. */
    public int count(final int lo, final int hi, final double mean) {
        if (hi <= lo) {
            return lo;
        }
        final double p = Math.max(0, Math.min(1, (mean - lo) / (hi - lo)));
        int n = lo;
        for (int i = lo; i < hi; i++) {
            if (nextDouble() < p) {
                n++;
            }
        }
        return n;
    }

    /** Uniform integer in [lo, hi]. */
    public int between(final int lo, final int hi) {
        return lo + nextInt(hi - lo + 1);
    }
}
