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
package org.tripatha.loader.bench;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.api.resolver.RdfToRyaConversions;
import org.tripatha.rocksdb.RocksStore;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.CellWriter;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.TableKind;
import org.tripatha.store.spi.WriterOptions;

/**
 * Loads an N-Triples file into a RocksDB store, for the engine comparison on WatDiv
 * ({@code compare.sh}), gzipped if the name ends with {@code .gz}. The statements go through the DAO, as the endpoint would write them, with their
 * counters and the entity index; then every table is compacted.
 *
 * <p>The file is parsed on one thread and written in batches by several, each with a DAO of its
 * own. Like {@code JoinBench}, it records a finished load in the store (the table
 * {@code <prefix>loaded} holds {@code --id}), and does nothing if the store holds that record. A
 * store without it loses its tables of that prefix first.
 */
public final class RdfFileLoad {
    private static final int BATCH = 10_000;
    private static final List<RyaStatement> END = List.of();
    private static final byte[] LOADED_ROW = "loaded".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ID = "id".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];

    private RdfFileLoad() {
    }

    public static void main(final String[] args) throws Exception {
        Path directory = null;
        Path file = null;
        String id = null;
        String prefix = "watdiv_";
        int threads = 8;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--rocksdb-path" -> directory = Path.of(args[i + 1]);
                case "--file" -> file = Path.of(args[i + 1]);
                case "--id" -> id = args[i + 1];
                case "--table-prefix" -> prefix = args[i + 1];
                case "--threads" -> threads = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (directory == null || file == null || id == null || args.length % 2 != 0) {
            System.err.println("Usage: RdfFileLoad --rocksdb-path DIR --file FILE.nt[.gz] --id ID [--table-prefix watdiv_] [--threads 8]");
            System.exit(2);
        }
        Files.createDirectories(directory);
        try (RocksStore store = RocksStore.open(directory)) {
            load(store, file, id, prefix, threads);
        }
    }

    private static void load(final RocksStore store, final Path file, final String id, final String prefix, final int threads)
            throws Exception {
        final String loaded = prefix + "loaded";
        if (id.equals(loadedId(store, loaded))) {
            System.out.println("Store already loaded with " + id + "; reusing it.");
            return;
        }
        for (final String table : store.tables().list()) {
            if (table.startsWith(prefix)) {
                store.tables().delete(table);
            }
        }
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(prefix);
        conf.setEntityIndex(true);
        conf.setMaintainStats(true);
        conf.setUseStats(true);
        conf.setFlush(false);
        // One init creates the tables, with the index READY; the writers' inits find them.
        final RyaStoreDAO first = new RyaStoreDAO(store, conf);
        first.init();
        first.destroy();

        final long t0 = System.nanoTime();
        final BlockingQueue<List<RyaStatement>> queue = new ArrayBlockingQueue<>(threads * 4);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final Thread w = new Thread(() -> write(store, conf, queue, failure), "writer-" + t);
            w.start();
            writers.add(w);
        }
        final long[] count = {0};
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 20);
                InputStream in = file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw) {
            final RDFParser parser = Rio.createParser(RDFFormat.NTRIPLES);
            parser.setRDFHandler(new AbstractRDFHandler() {
                private List<RyaStatement> batch = new ArrayList<>(BATCH);

                @Override
                public void handleStatement(final Statement st) {
                    batch.add(RdfToRyaConversions.convertStatement(st));
                    if (batch.size() == BATCH) {
                        put(queue, batch, failure);
                        count[0] += batch.size();
                        batch = new ArrayList<>(BATCH);
                    }
                }

                @Override
                public void endRDF() {
                    if (!batch.isEmpty()) {
                        put(queue, batch, failure);
                        count[0] += batch.size();
                    }
                }
            });
            parser.parse(in, "");
        } finally {
            for (int t = 0; t < threads; t++) {
                queue.put(END);
            }
            for (final Thread w : writers) {
                w.join();
            }
        }
        if (failure.get() != null) {
            throw new IllegalStateException("the load failed", failure.get());
        }
        final double loadSeconds = (System.nanoTime() - t0) / 1e9;
        System.out.printf(Locale.ROOT, "Loaded %,d statements in %.0f s (%,.0f statements/s). Compacting…%n", count[0], loadSeconds,
                count[0] / loadSeconds);
        final TableLayoutStrategy t = conf.getTableLayoutStrategy();
        for (final String table : List.of(t.getSpo(), t.getPo(), t.getOsp(), t.getEval(), t.getGraphs(), t.getNs(), t.getEntity())) {
            if (store.tables().exists(table)) {
                store.compact(table);
            }
        }
        store.tables().create(loaded, TableKind.METADATA);
        try (CellWriter writer = store.newWriter(WriterOptions.DEFAULT)) {
            writer.table(loaded).write(new RowMutation(LOADED_ROW).put(ID, EMPTY, EMPTY, id.getBytes(StandardCharsets.UTF_8)));
            writer.flush();
        }
        System.out.printf(Locale.ROOT, "Loaded and compacted in %.0f s.%n", (System.nanoTime() - t0) / 1e9);
    }

    private static void put(final BlockingQueue<List<RyaStatement>> queue, final List<RyaStatement> batch,
            final AtomicReference<Throwable> failure) {
        if (failure.get() != null) {
            throw new IllegalStateException("a writer failed", failure.get());
        }
        try {
            queue.put(batch);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void write(final RocksStore store, final StoreRdfConfiguration conf, final BlockingQueue<List<RyaStatement>> queue,
            final AtomicReference<Throwable> failure) {
        final RyaStoreDAO dao = new RyaStoreDAO(store, new StoreRdfConfiguration(conf));
        try {
            dao.init();
            for (List<RyaStatement> batch; (batch = queue.take()) != END;) {
                if (failure.get() == null) {
                    dao.add(batch.iterator());
                }
            }
            dao.flush();
        } catch (final Throwable e) {
            failure.compareAndSet(null, e);
            // Keep taking, so the parser never blocks on a full queue.
            try {
                while (queue.take() != END) {
                    // drop it
                }
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            dao.destroy();
        }
    }

    private static String loadedId(final RocksStore store, final String table) {
        if (!store.tables().exists(table)) {
            return null;
        }
        final ScanSpec spec = ScanSpec.builder(Auths.NONE).column(ID, EMPTY).build();
        try (CellScanner scanner = store.scan(table, KeyRange.row(LOADED_ROW), spec)) {
            return scanner.hasNext() ? new String(scanner.next().value(), StandardCharsets.UTF_8) : null;
        }
    }
}
