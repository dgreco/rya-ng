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
            final int splits = o.splits() < 0 ? 4 * servers - 1 : o.splits();
            if (store instanceof RocksStore) {
                out.println("No splits: a RocksDB store is one local database.");
            } else if (splits > 0) {
                out.println("Planning " + splits + " splits per table (" + servers + " servers)…");
                final Map<String, SortedSet<Text>> plan = SplitPlanner.plan(generator, conf, splits);
                SplitPlanner.apply(store, plan);
            }

            if (stored == null) {
                final List<RyaStatement> reference = ReferenceData.statements(generator);
                dao.add(reference.iterator());
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
