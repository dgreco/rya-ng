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

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import org.apache.hadoop.io.Text;

import org.tripatha.hbase.HBaseLabels;
import org.tripatha.hbase.HBaseStore;
import org.tripatha.rocksdb.RocksStore;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Store;
import org.tripatha.store.RyaStoreDAO;
import org.tripatha.store.index.IndexType;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.loader.gen.ReferenceData;

/**
 * Prepares a store for a load, once per dataset, before any load process starts: creates the
 * tables through a single DAO (so the indexes start READY without a race between processes),
 * pre-splits them, and writes the ontology, the reference data and the dataset description.
 */
public final class Prepare {
    private Prepare() {
    }

    public static void run(final Store store, final LoaderOptions o, final PrintStream out) throws Exception {
        StoreCheck.forPrepare(store, o);
        final StoreRdfConfiguration conf = o.rdfConf();
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final RyaStoreDAO dao = new RyaStoreDAO(store, conf);
        dao.init();
        try {
            for (final IndexType type : IndexType.values()) {
                if (type.isEnabled(conf) && !dao.getIndexCatalog().isReady(type)) {
                    throw new StoreCheck.Mismatch("the " + type.id() + " index of " + o.tablePrefix() + " is not ready "
                            + "(the store had data before the index was added)");
                }
            }
            final String stored = StoreCheck.storedFingerprint(store, conf, o.genConfig());
            if (stored != null && !stored.equals(o.genConfig().fingerprint())) {
                throw new StoreCheck.Mismatch("store " + o.tablePrefix() + " was prepared for a different dataset");
            }

            final int servers = Math.max(1, store.tables().serverCount());
            if (store instanceof RocksStore) {
                out.println("No splits: a RocksDB store is one local database.");
            } else {
                final Map<String, Integer> splits = plannedSplits(store, o, generator, conf, servers, out);
                if (splits.values().stream().anyMatch(n -> n > 0)) {
                    final Map<String, SortedSet<Text>> plan = SplitPlanner.plan(generator, conf, splits, o.statsOnes());
                    SplitPlanner.apply(store, plan);
                }
                // So the backend doesn't split them smaller as they grow (Accumulo's default is 1 GB).
                for (final String table : splits.keySet()) {
                    if (store.tables().exists(table)) {
                        store.tables().setTargetSize(table, o.tabletSize());
                    }
                }
                final String entity = conf.getTableLayoutStrategy().getEntity();
                final List<byte[]> rows = conf.isEntityIndex() ? dao.getIndexCatalog().entityIndex().shardRows() : List.of();
                // A shard is one row: split at every shard but the first, so shards spread over the servers
                // (unless --splits 0). Once is enough, and a second prepare asks for none.
                if (o.splits() != 0 && rows.size() > 1 && store.tables().splitCount(entity) < rows.size() - 1) {
                    final java.util.SortedSet<byte[]> shards = new java.util.TreeSet<>(java.util.Arrays::compareUnsigned);
                    shards.addAll(rows.subList(1, rows.size()));
                    out.println("Splitting the entity index at its " + rows.size() + " shards…");
                    store.tables().addSplits(entity, shards);
                }
            }

            if (stored == null) {
                final List<RyaStatement> reference = ReferenceData.statements(generator);
                // One call: each reference subject's statements are all in it, and in no entity.
                dao.add(reference.iterator(), o.statsOnes());
                dao.flush();
                out.println("Wrote " + reference.size() + " reference statements (ontology, countries, categories, dataset description).");
            } else {
                out.println("Already prepared for this dataset; reference data left as is.");
            }

            if (o.grantAuths() && !o.genConfig().labels().isEmpty()) {
                final String user = store.access().whoami();
                // On HBase this also registers the labels and writes their canaries (it needs a superuser).
                Labels.grant(store, user, o.genConfig().labels());
                out.println("Added " + o.genConfig().labels() + " to the authorizations of " + user + ".");
            }
        } finally {
            dao.destroy();
        }
        printNextSteps(store, o, out);
    }

    /**
     * The splits per table: {@code --splits N} for every table ({@code geo} half), or each table's
     * estimated size on disk over {@code --tablet-size}, with at least 4 per server.
     */
    private static Map<String, Integer> plannedSplits(final Store store, final LoaderOptions o, final EntityGenerator generator,
            final StoreRdfConfiguration conf, final int servers, final PrintStream out) {
        final org.tripatha.api.layout.TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        final List<String> names = List.of(tables.getSpo(), tables.getPo(), tables.getOsp(), tables.getEval(), tables.getText(),
                tables.getGeo());
        final Map<String, Integer> splits = new java.util.LinkedHashMap<>();
        if (o.splits() >= 0) {
            for (final String t : names) {
                splits.put(t, t.equals(tables.getGeo()) ? o.splits() / 2 : o.splits());
            }
            out.println("Planning " + o.splits() + " splits per table (" + servers + " servers)…");
            return splits;
        }
        final long entities = o.genConfig().entities();
        final SplitPlanner.Sample estimate = SplitPlanner.sample(generator, conf, o.statsOnes(), 5_000);
        splits.putAll(SplitPlanner.splitsBySize(estimate, entities, o.compression(), o.tabletSize(), 4 * servers - 1,
                tables.getGeo()));
        out.printf(java.util.Locale.ROOT, "Planning splits for %s tablets (an estimate: the keys a sample writes, over a "
                + "compression of %.1f), and at least %d per table (%d servers):%n", gib(o.tabletSize()), o.compression(),
                4 * servers - 1, servers);
        for (final String t : names) {
            final double disk = estimate.diskBytes(t, entities, o.compression());
            if (disk > 0) {
                out.printf(java.util.Locale.ROOT, "  %-10s about %10s on disk: %,d splits%n", t.substring(o.tablePrefix().length()),
                        gib((long) disk), splits.get(t));
            }
        }
        return splits;
    }

    private static String gib(final long bytes) {
        return bytes >= 1L << 30 ? String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30))
                : String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
    }

    private static void printNextSteps(final Store store, final LoaderOptions o, final PrintStream out) {
        out.println();
        out.println("Dataset: " + o.genConfig().entities() + " entities (about "
                + Math.round(o.genConfig().entities() * LoaderOptions.triplesPerEntity(o.genConfig()) / 1e6) + "M triples), fingerprint "
                + o.genConfig().fingerprint().substring(0, 16) + "…");
        out.println();
        if (store instanceof RocksStore) {
            out.println("Load it (one process: only one at a time can open a RocksDB directory):");
            out.println("  run.sh load " + o.datasetArgs() + " --threads 8");
        } else {
            out.println("Load it (one process, or one per --part on as many machines as you like):");
            out.println("  run.sh load " + o.datasetArgs() + " --threads 8");
            out.println("  run.sh load " + o.datasetArgs() + " --part 0 --parts 2 --threads 8   # and --part 1 --parts 2");
        }
        out.println();
        final StringBuilder flags = new StringBuilder();
        if (store instanceof HBaseStore hbase) {
            flags.append("--tripatha.backend=hbase ");
            // Every writer must use the counter table's mode; the endpoint's default is coprocessor.
            if (hbase.counterMode(o.tablePrefix() + "eval") == HBaseStore.CounterMode.INCREMENT) {
                flags.append("--tripatha.hbase.counters=increment ");
            }
            if (!o.hbaseLabelsTable().equals(HBaseLabels.DEFAULT_TABLE)) {
                flags.append("--tripatha.hbase.labels-table=").append(o.hbaseLabelsTable()).append(' ');
            }
        }
        if (store instanceof RocksStore rocks) {
            flags.append("--tripatha.backend=rocksdb --tripatha.rocksdb.path=").append(rocks.directory()).append(' ');
        }
        flags.append("--tripatha.table-prefix=").append(o.tablePrefix());
        flags.append(" --tripatha.text-index=").append(o.textIndex()).append(" --tripatha.geo-index=").append(o.geoIndex());
        if (o.entityIndex()) {
            flags.append(" --tripatha.entity-index=true --tripatha.entity-index-shards=").append(o.entityShards());
        }
        if (!o.textPredicates().isEmpty()) {
            flags.append(" --tripatha.text-index-predicates=").append(String.join(",", o.textPredicates()));
        }
        if (!o.stats()) {
            flags.append(" --tripatha.stats=false");
        }
        if (!o.genConfig().labels().isEmpty()) {
            flags.append(" --tripatha.sparql.authorizations=").append(String.join(",", o.genConfig().labels()));
        }
        out.println("Serve it with the SPARQL endpoint:");
        out.println("  java -jar tripatha.sparql.jar <connection flags> " + flags);
    }
}
