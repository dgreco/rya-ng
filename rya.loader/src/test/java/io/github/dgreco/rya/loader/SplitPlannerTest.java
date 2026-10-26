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
package io.github.dgreco.rya.loader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;

import org.apache.accumulo.core.data.Mutation;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.RyaTableMutationsFactory;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.loader.gen.EntityGenerator;
import io.github.dgreco.rya.loader.gen.GenConfig;

class SplitPlannerTest {

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void separatorIsTheShortestPrefixAfterThePreviousRow() {
        assertArrayEquals(b("ab"), SplitPlanner.separator(b("aa"), b("abc")));
        assertArrayEquals(b("b"), SplitPlanner.separator(b("a"), b("b")));
        assertArrayEquals(b("ab"), SplitPlanner.separator(b("a"), b("ab")));
        assertArrayEquals(b("abc"), SplitPlanner.separator(b("abc"), b("abc")));
    }

    @Test
    void quantilesFallBetweenNeighbouringRows() {
        final Random random = new Random(3);
        for (int round = 0; round < 50; round++) {
            final List<byte[]> rows = new ArrayList<>();
            final int n = 2 + random.nextInt(500);
            for (int i = 0; i < n; i++) {
                final byte[] row = new byte[1 + random.nextInt(6)];
                for (int j = 0; j < row.length; j++) {
                    row[j] = (byte) random.nextInt(4); // few symbols: many shared prefixes and duplicates
                }
                rows.add(row);
            }
            final int wanted = 1 + random.nextInt(20);
            final SortedSet<Text> points = SplitPlanner.quantiles(rows, wanted);
            assertTrue(points.size() <= wanted);
            final byte[][] sorted = rows.toArray(new byte[0][]);
            Arrays.sort(sorted, Arrays::compareUnsigned);
            for (final Text p : points) {
                final byte[] point = Arrays.copyOf(p.getBytes(), p.getLength());
                // Some row sorts before the point and the point is a prefix of (or equal to) a row: a useful split.
                assertTrue(Arrays.compareUnsigned(sorted[0], point) < 0 || Arrays.equals(sorted[0], point));
                assertTrue(Arrays.stream(sorted).anyMatch(r -> Arrays.compareUnsigned(r, point) >= 0
                        && Arrays.equals(Arrays.copyOf(r, Math.min(r.length, point.length)), point)));
            }
        }
    }

    @Test
    void planSplitsEveryTableAndBalancesTheMainOnes() {
        final AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
        conf.setTablePrefix("t_");
        conf.setTextIndex(true);
        conf.setGeoIndex(true);
        final EntityGenerator generator = new EntityGenerator(GenConfig.defaults(42, 1_000_000));
        final int splits = 15;
        final Map<String, SortedSet<Text>> plan = SplitPlanner.plan(generator, conf, splits);
        assertEquals(List.of("t_spo", "t_po", "t_osp", "t_eval", "t_text", "t_geo"), List.copyOf(plan.keySet()));
        assertEquals(splits, plan.get("t_spo").size());
        assertTrue(plan.get("t_geo").size() <= splits / 2);

        // Rows of other entities than the planner sampled spread evenly over the planned tablets.
        final RyaTableMutationsFactory factory = new RyaTableMutationsFactory(RyaTripleContext.getInstance(conf));
        for (final TABLE_LAYOUT layout : List.of(TABLE_LAYOUT.SPO, TABLE_LAYOUT.PO, TABLE_LAYOUT.OSP)) {
            final String table = "t_" + layout.name().toLowerCase(java.util.Locale.ROOT);
            final List<Text> points = List.copyOf(plan.get(table));
            final int[] perTablet = new int[points.size() + 1];
            int total = 0;
            for (long e = 500_000; e < 505_000; e++) {
                for (final RyaStatement st : generator.generate(e)) {
                    final Collection<Mutation> rows;
                    try {
                        rows = factory.serialize(st).get(layout);
                    } catch (final Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                    for (final Mutation m : rows) {
                        perTablet[tablet(points, new Text(m.getRow()))]++;
                        total++;
                    }
                }
            }
            final double fair = (double) total / perTablet.length;
            for (int t = 0; t < perTablet.length; t++) {
                assertTrue(perTablet[t] < 2.5 * fair, table + " tablet " + t + " holds " + perTablet[t] + " of " + total
                        + " rows: " + Arrays.toString(perTablet));
            }
        }
    }

    /** The tablet a row lands in: tablet i holds rows in (split[i-1], split[i]]. */
    private static int tablet(final List<Text> splits, final Text row) {
        int t = 0;
        while (t < splits.size() && row.compareTo(splits.get(t)) > 0) {
            t++;
        }
        return t;
    }
}
