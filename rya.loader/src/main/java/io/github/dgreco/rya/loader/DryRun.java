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

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.accumulo.core.data.Mutation;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.EvalStatsMutations;
import io.github.dgreco.rya.accumulo.RyaTableMutationsFactory;
import io.github.dgreco.rya.accumulo.index.GeoIndex;
import io.github.dgreco.rya.accumulo.index.TextIndex;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.loader.gen.EntityGenerator;

/**
 * Generates without a cluster and reports what a load would cost: generator speed (one thread and
 * all threads), bytes and cells per triple for each table (from the store's own serializers, with
 * counters summed per block as the store does), the ceiling the network link puts on the load
 * rate, and the raw size of the whole dataset.
 */
public final class DryRun {
    /** Entities measured at most; the whole dataset is extrapolated from them. */
    private static final long MAX_MEASURED = 1_000_000;
    private static final String[] TABLES = {"spo", "po", "osp", "eval", "text", "geo"};

    private DryRun() {
    }

    public static void run(final LoaderOptions o, final PrintStream out) throws Exception {
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final AccumuloRdfConfiguration conf = o.rdfConf();
        final long[] range = o.partRange();
        final long measured = Math.min(range[1] - range[0], MAX_MEASURED);

        if (o.print() > 0) {
            int printed = 0;
            for (long e = range[0]; e < range[1] && printed < o.print(); e++) {
                for (final RyaStatement st : generator.generate(e)) {
                    if (printed++ >= o.print()) {
                        break;
                    }
                    out.println(nquad(st));
                }
            }
            out.println();
        }

        // Generator alone, one thread.
        final long single = Math.min(measured, 20_000);
        long t0 = System.nanoTime();
        long singleTriples = 0;
        for (long e = range[0]; e < range[0] + single; e++) {
            singleTriples += generator.generate(e).size();
        }
        final double singleRate = singleTriples / ((System.nanoTime() - t0) / 1e9);

        // All threads, generating and serializing like a load.
        final Map<String, AtomicLong> bytes = new LinkedHashMap<>();
        final Map<String, AtomicLong> cells = new LinkedHashMap<>();
        for (final String t : TABLES) {
            bytes.put(t, new AtomicLong());
            cells.put(t, new AtomicLong());
        }
        final AtomicLong triples = new AtomicLong();
        final AtomicLong cursor = new AtomicLong(range[0]);
        final long end = range[0] + measured;
        t0 = System.nanoTime();
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < o.threads(); i++) {
            final Thread th = new Thread(() -> measure(generator, conf, o.blockSize(), cursor, end, triples, bytes, cells));
            threads.add(th);
            th.start();
        }
        for (final Thread th : threads) {
            th.join();
        }
        final double seconds = (System.nanoTime() - t0) / 1e9;
        final long n = triples.get();

        final double perEntity = (double) n / measured;
        final long datasetTriples = Math.round(perEntity * o.genConfig().entities());
        out.printf(Locale.ROOT, "Dataset: %,d entities, about %,d triples (%.2f per entity, measured on %,d entities).%n",
                o.genConfig().entities(), datasetTriples, perEntity, measured);
        out.printf(Locale.ROOT, "Generator: %,.0f triples/s on 1 thread; %,.0f triples/s generating and serializing on %d threads.%n",
                singleRate, n / seconds, o.threads());
        out.println();
        out.printf(Locale.ROOT, "%-8s %14s %14s%n", "table", "bytes/triple", "cells/triple");
        double totalBytes = 0;
        double totalCells = 0;
        for (final String t : TABLES) {
            final double b = (double) bytes.get(t).get() / n;
            final double c = (double) cells.get(t).get() / n;
            if (c == 0) {
                continue;
            }
            totalBytes += b;
            totalCells += c;
            out.printf(Locale.ROOT, "%-8s %14.1f %14.2f%n", t, b, c);
        }
        out.printf(Locale.ROOT, "%-8s %14.1f %14.2f%n", "total", totalBytes, totalCells);
        out.println();
        final double ceiling = o.linkMbps() * 1e6 / 8 / totalBytes;
        out.printf(Locale.ROOT, "A %.0f Mbit/s link carries at most about %,.0f triples/s of this data (before protocol overhead).%n",
                o.linkMbps(), ceiling);
        final double raw = totalBytes * datasetTriples;
        out.printf(Locale.ROOT, "Key data for the whole dataset: %.1f GB before compression (the size on disk is smaller: "
                + "measure it with a small load; storage that keeps 3 replicas uses 3 times that).%n", raw / 1e9);
    }

    private static void measure(final EntityGenerator generator, final AccumuloRdfConfiguration conf, final int blockSize,
            final AtomicLong cursor, final long end, final AtomicLong triples, final Map<String, AtomicLong> bytes,
            final Map<String, AtomicLong> cells) {
        final RyaTableMutationsFactory factory = new RyaTableMutationsFactory(RyaTripleContext.getInstance(conf));
        final TextIndex text = conf.isTextIndex() ? new TextIndex(conf.getTextIndexPredicates()) : null;
        final GeoIndex geo = conf.isGeoIndex() ? new GeoIndex() : null;
        final long[] b = new long[TABLES.length];
        final long[] c = new long[TABLES.length];
        long n = 0;
        try {
            while (true) {
                final long from = cursor.getAndAdd(blockSize);
                if (from >= end) {
                    break;
                }
                final EvalStatsMutations.CounterBatch counts = new EvalStatsMutations.CounterBatch();
                for (long e = from; e < Math.min(from + blockSize, end); e++) {
                    for (final RyaStatement st : generator.generate(e)) {
                        n++;
                        final Map<TABLE_LAYOUT, Collection<Mutation>> rows = factory.serialize(st);
                        add(b, c, 0, rows.get(TABLE_LAYOUT.SPO));
                        add(b, c, 1, rows.get(TABLE_LAYOUT.PO));
                        add(b, c, 2, rows.get(TABLE_LAYOUT.OSP));
                        if (text != null) {
                            add(b, c, 4, text.puts(st));
                        }
                        if (geo != null) {
                            add(b, c, 5, geo.puts(st));
                        }
                        if (conf.isMaintainStats()) {
                            counts.add(st, 1);
                        }
                    }
                }
                add(b, c, 3, counts.drain());
            }
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        triples.addAndGet(n);
        for (int i = 0; i < TABLES.length; i++) {
            bytes.get(TABLES[i]).addAndGet(b[i]);
            cells.get(TABLES[i]).addAndGet(c[i]);
        }
    }

    private static void add(final long[] bytes, final long[] cells, final int table, final Collection<Mutation> mutations) {
        for (final Mutation m : mutations) {
            bytes[table] += m.numBytes();
            cells[table] += m.size();
        }
    }

    /** A statement in N-Quads form (for --print). */
    static String nquad(final RyaStatement st) {
        final StringBuilder b = new StringBuilder();
        b.append('<').append(st.getSubject().getData()).append("> <").append(st.getPredicate().getData()).append("> ");
        if (st.getObject() instanceof RyaIRI) {
            b.append('<').append(st.getObject().getData()).append('>');
        } else {
            b.append('"').append(st.getObject().getData().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            if (st.getObject().getLanguage() != null) {
                b.append('@').append(st.getObject().getLanguage());
            } else {
                b.append("^^<").append(st.getObject().getDataType()).append('>');
            }
        }
        if (st.getContext() != null) {
            b.append(" <").append(st.getContext().getData()).append('>');
        }
        b.append(" .");
        if (st.getColumnVisibility() != null) {
            b.append("  # visibility ").append(new String(st.getColumnVisibility(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }
}
