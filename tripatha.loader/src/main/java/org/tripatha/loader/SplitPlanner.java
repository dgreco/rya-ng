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
package org.tripatha.loader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.EvalStatsMutations;
import org.tripatha.store.RyaTableMutationsFactory;
import org.tripatha.store.spi.Store;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.index.GeoIndex;
import org.tripatha.store.index.SecondaryIndex;
import org.tripatha.store.index.TextIndex;
import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.loader.gen.SplitMix64;

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

    /**
     * Split points per table name, the same number for every table but {@code geo}, which gets
     * half. Tables that get none are left out.
     *
     * @param counters the counters the load will write, so {@code eval}'s split points follow its rows
     */
    public static Map<String, SortedSet<Text>> plan(final EntityGenerator generator, final StoreRdfConfiguration conf,
            final int splits, final EvalStatsMutations.CounterPolicy counters) {
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        final Map<String, Integer> each = new LinkedHashMap<>();
        for (final String t : dataTables(tables)) {
            each.put(t, t.equals(tables.getGeo()) ? splits / 2 : splits);
        }
        return plan(generator, conf, each, counters);
    }

    /**
     * Split points per table name, as many for each table as {@code splits} asks. Tables that get
     * none are left out.
     */
    public static Map<String, SortedSet<Text>> plan(final EntityGenerator generator, final StoreRdfConfiguration conf,
            final Map<String, Integer> splits, final EvalStatsMutations.CounterPolicy counters) {
        final int most = splits.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        final long n = generator.config().entities();
        final int entities = (int) Math.min(n, Math.max(5_000, Math.min(50_000, 300L * (most + 1))));
        final Sample sample = sample(generator, conf, counters, entities);
        final Map<String, SortedSet<Text>> plan = new LinkedHashMap<>();
        for (final Map.Entry<String, List<byte[]>> e : sample.rows().entrySet()) {
            final SortedSet<Text> points = quantiles(e.getValue(), splits.getOrDefault(e.getKey(), 0));
            if (!points.isEmpty()) {
                plan.put(e.getKey(), points);
            }
        }
        return plan;
    }

    /**
     * The rows a sample of entities writes to each table, and the bytes of their cells (row,
     * column, label, timestamp and value, uncompressed).
     *
     * @param entities sampled entities: every one when the dataset has no more, otherwise drawn at random
     */
    public record Sample(Map<String, List<byte[]>> rows, Map<String, Long> bytes, long entities) {
        /** A table's estimated size on disk for the whole dataset: its sampled bytes, scaled, over the compression ratio. */
        public double diskBytes(final String table, final long datasetEntities, final double compression) {
            return bytes.getOrDefault(table, 0L) / (double) entities * datasetEntities / compression;
        }
    }

    public static Sample sample(final EntityGenerator generator, final StoreRdfConfiguration conf,
            final EvalStatsMutations.CounterPolicy counters, final int entities) {
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        final RyaTableMutationsFactory factory = new RyaTableMutationsFactory(RyaTripleContext.getInstance(conf));
        final SecondaryIndex text = conf.isTextIndex() ? new TextIndex(conf.getTextIndexPredicates()) : null;
        final SecondaryIndex geo = conf.isGeoIndex() ? new GeoIndex() : null;
        final Map<String, List<byte[]>> rows = new LinkedHashMap<>();
        final Map<String, long[]> bytes = new LinkedHashMap<>();
        for (final String t : dataTables(tables)) {
            rows.put(t, new ArrayList<>());
            bytes.put(t, new long[1]);
        }
        final long n = generator.config().entities();
        final int sample = (int) Math.min(n, entities);
        final long seed = generator.config().seed() ^ SPLIT_SALT;
        try {
            for (int k = 0; k < sample; k++) {
                // Random, not strided: a stride would alias with the class layout (1000 interleaved slots).
                final long entity = sample == n ? k : Long.remainderUnsigned(SplitMix64.mix64(seed ^ k), n);
                final List<RyaStatement> statements = generator.generate(entity);
                final EvalStatsMutations.CounterBatch counts = new EvalStatsMutations.CounterBatch();
                for (final RyaStatement st : statements) {
                    final Map<TABLE_LAYOUT, RowMutation> m = factory.serialize(st);
                    add(rows, bytes, tables.getSpo(), List.of(m.get(TABLE_LAYOUT.SPO)));
                    add(rows, bytes, tables.getPo(), List.of(m.get(TABLE_LAYOUT.PO)));
                    add(rows, bytes, tables.getOsp(), List.of(m.get(TABLE_LAYOUT.OSP)));
                    if (text != null) {
                        add(rows, bytes, tables.getText(), text.puts(st));
                    }
                    if (geo != null) {
                        add(rows, bytes, tables.getGeo(), geo.puts(st));
                    }
                    counts.add(st, 1);
                }
                if (conf.isMaintainStats()) {
                    // Per entity, like a load's blocks: the entity's subject counters are final. Counters of
                    // values that repeat across entities are counted once per entity, so eval's size is an
                    // upper bound.
                    add(rows, bytes, tables.getEval(), counts.drain(counters));
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException("could not compute split points", e);
        }
        final Map<String, Long> totals = new LinkedHashMap<>();
        bytes.forEach((t, b) -> totals.put(t, b[0]));
        return new Sample(rows, totals, sample);
    }

    /**
     * Splits per table for tablets or regions of {@code tabletSize} bytes on disk, at least
     * {@code minimum} ({@code geo} half that): each table's estimated size over the target size,
     * minus one.
     */
    public static Map<String, Integer> splitsBySize(final Sample sample, final long datasetEntities, final double compression,
            final long tabletSize, final int minimum, final String geoTable) {
        final Map<String, Integer> splits = new LinkedHashMap<>();
        for (final String t : sample.rows().keySet()) {
            final double disk = sample.diskBytes(t, datasetEntities, compression);
            final long bySize = Math.max(0, (long) Math.ceil(disk / tabletSize) - 1);
            final int floor = t.equals(geoTable) ? minimum / 2 : minimum;
            splits.put(t, (int) Math.min(Integer.MAX_VALUE, Math.max(floor, bySize)));
        }
        return splits;
    }

    private static List<String> dataTables(final TableLayoutStrategy tables) {
        return List.of(tables.getSpo(), tables.getPo(), tables.getOsp(), tables.getEval(), tables.getText(), tables.getGeo());
    }

    private static void add(final Map<String, List<byte[]>> rows, final Map<String, long[]> bytes, final String table,
            final Collection<RowMutation> mutations) {
        addRows(rows.get(table), mutations);
        long b = 0;
        for (final RowMutation m : mutations) {
            for (final RowMutation.Update u : m.updates()) {
                b += m.row().length + length(u.family()) + length(u.qualifier()) + length(u.visibility()) + Long.BYTES
                        + (u.value() == null ? Long.BYTES : u.value().length);
            }
        }
        bytes.get(table)[0] += b;
    }

    private static int length(final byte[] b) {
        return b == null ? 0 : b.length;
    }

    private static void addRows(final List<byte[]> out, final Collection<RowMutation> mutations) {
        for (final RowMutation m : mutations) {
            out.add(m.row());
        }
    }

    /** Up to {@code n} distinct split points at the quantiles of the rows. */
    public static SortedSet<Text> quantiles(final List<byte[]> rows, final int n) {
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
    public static void apply(final Store store, final Map<String, SortedSet<Text>> plan) throws Exception {
        for (final Map.Entry<String, SortedSet<Text>> e : plan.entrySet()) {
            final String table = e.getKey();
            if (!store.tables().exists(table)) {
                continue;
            }
            final int existing = store.tables().splitCount(table);
            if (existing >= e.getValue().size()) {
                log.info("{}: already has {} splits", table, existing);
                continue;
            }
            final SortedSet<byte[]> points = new java.util.TreeSet<>(Arrays::compareUnsigned);
            for (final Text t : e.getValue()) {
                points.add(Arrays.copyOf(t.getBytes(), t.getLength()));
            }
            store.tables().addSplits(table, points);
            log.info("{}: added {} splits", table, e.getValue().size());
        }
    }
}
