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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.data.Mutation;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.EvalStatsMutations;
import io.github.dgreco.rya.accumulo.RyaTableMutationsFactory;
import io.github.dgreco.rya.accumulo.index.GeoIndex;
import io.github.dgreco.rya.accumulo.index.SecondaryIndex;
import io.github.dgreco.rya.accumulo.index.TextIndex;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.loader.gen.EntityGenerator;
import io.github.dgreco.rya.loader.gen.SplitMix64;

/**
 * Pre-splits the tables before a load, so writes spread over every tablet server from the start
 * instead of hitting one tablet per table until Accumulo splits it at about 1 GB.
 * <p>
 * Split points are quantiles of the rows a random sample of entities would write, computed with
 * the same code as the store (so they follow the real key distribution of every table), each
 * shortened to the shortest prefix that still separates it from the row before.
 */
public final class SplitPlanner {
    private static final Logger log = LoggerFactory.getLogger(SplitPlanner.class);
    private static final long SPLIT_SALT = 0x5EED_5917L;

    private SplitPlanner() {
    }

    /** Split points per table name. Tables that get none are left out. */
    public static Map<String, SortedSet<Text>> plan(final EntityGenerator generator, final AccumuloRdfConfiguration conf,
            final int splits) {
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        final RyaTableMutationsFactory factory = new RyaTableMutationsFactory(RyaTripleContext.getInstance(conf));
        final SecondaryIndex text = conf.isTextIndex() ? new TextIndex(conf.getTextIndexPredicates()) : null;
        final SecondaryIndex geo = conf.isGeoIndex() ? new GeoIndex() : null;
        final Map<String, List<byte[]>> rows = new LinkedHashMap<>();
        for (final String t : List.of(tables.getSpo(), tables.getPo(), tables.getOsp(), tables.getEval(), tables.getText(),
                tables.getGeo())) {
            rows.put(t, new ArrayList<>());
        }
        final long n = generator.config().entities();
        final int sample = (int) Math.min(n, Math.max(5_000, Math.min(50_000, 300L * (splits + 1))));
        final long seed = generator.config().seed() ^ SPLIT_SALT;
        try {
            for (int k = 0; k < sample; k++) {
                // Random, not strided: a stride would alias with the class layout (1000 interleaved slots).
                final long entity = sample == n ? k : Long.remainderUnsigned(SplitMix64.mix64(seed ^ k), n);
                final List<RyaStatement> statements = generator.generate(entity);
                final EvalStatsMutations.CounterBatch counts = new EvalStatsMutations.CounterBatch();
                for (final RyaStatement st : statements) {
                    final Map<TABLE_LAYOUT, Collection<Mutation>> m = factory.serialize(st);
                    addRows(rows.get(tables.getSpo()), m.get(TABLE_LAYOUT.SPO));
                    addRows(rows.get(tables.getPo()), m.get(TABLE_LAYOUT.PO));
                    addRows(rows.get(tables.getOsp()), m.get(TABLE_LAYOUT.OSP));
                    if (text != null) {
                        addRows(rows.get(tables.getText()), text.puts(st));
                    }
                    if (geo != null) {
                        addRows(rows.get(tables.getGeo()), geo.puts(st));
                    }
                    counts.add(st, 1);
                }
                if (conf.isMaintainStats()) {
                    addRows(rows.get(tables.getEval()), counts.drain());
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException("could not compute split points", e);
        }
        final Map<String, SortedSet<Text>> plan = new LinkedHashMap<>();
        for (final Map.Entry<String, List<byte[]>> e : rows.entrySet()) {
            final int wanted = e.getKey().equals(tables.getGeo()) ? splits / 2 : splits;
            final SortedSet<Text> points = quantiles(e.getValue(), wanted);
            if (!points.isEmpty()) {
                plan.put(e.getKey(), points);
            }
        }
        return plan;
    }

    private static void addRows(final List<byte[]> out, final Collection<Mutation> mutations) {
        for (final Mutation m : mutations) {
            out.add(m.getRow());
        }
    }

    /** Up to {@code n} distinct split points at the quantiles of the rows. */
    static SortedSet<Text> quantiles(final List<byte[]> rows, final int n) {
        final SortedSet<Text> points = new TreeSet<>();
        if (n <= 0 || rows.size() < 2) {
            return points;
        }
        final byte[][] sorted = rows.toArray(new byte[0][]);
        Arrays.sort(sorted, Arrays::compareUnsigned);
        for (int j = 1; j <= n; j++) {
            final int i = (int) ((long) j * sorted.length / (n + 1));
            if (i <= 0 || i >= sorted.length) {
                continue;
            }
            points.add(new Text(separator(sorted[i - 1], sorted[i])));
        }
        return points;
    }

    /** The shortest prefix of {@code row} that sorts after {@code previous} (the row itself if they are equal). */
    static byte[] separator(final byte[] previous, final byte[] row) {
        final int common = Arrays.mismatch(previous, row);
        if (common < 0 || common >= row.length) {
            return row;
        }
        return Arrays.copyOf(row, common + 1);
    }

    /** Adds the planned splits to tables that have fewer than planned. */
    public static void apply(final AccumuloClient client, final Map<String, SortedSet<Text>> plan) throws Exception {
        for (final Map.Entry<String, SortedSet<Text>> e : plan.entrySet()) {
            final String table = e.getKey();
            if (!client.tableOperations().exists(table)) {
                continue;
            }
            final int existing = client.tableOperations().listSplits(table).size();
            if (existing >= e.getValue().size()) {
                log.info("{}: already has {} splits", table, existing);
                continue;
            }
            client.tableOperations().addSplits(table, e.getValue());
            log.info("{}: added {} splits", table, e.getValue().size());
        }
    }
}
