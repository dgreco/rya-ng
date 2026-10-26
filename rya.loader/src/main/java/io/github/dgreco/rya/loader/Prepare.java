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
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.accumulo.index.IndexType;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.loader.gen.EntityGenerator;
import io.github.dgreco.rya.loader.gen.ReferenceData;

/**
 * Prepares a store for a load, once per dataset, before any load process starts: creates the
 * tables through a single DAO (so the indexes start READY without a race between processes),
 * pre-splits them, and writes the ontology, the reference data and the dataset description.
 */
public final class Prepare {
    private Prepare() {
    }

    public static void run(final AccumuloClient client, final LoaderOptions o, final PrintStream out) throws Exception {
        StoreCheck.forPrepare(client, o);
        final AccumuloRdfConfiguration conf = o.rdfConf();
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final AccumuloRyaDAO dao = new AccumuloRyaDAO(client, conf);
        dao.init();
        try {
            for (final IndexType type : IndexType.values()) {
                if (type.isEnabled(conf) && !dao.getIndexCatalog().isReady(type)) {
                    throw new StoreCheck.Mismatch("the " + type.id() + " index of " + o.tablePrefix() + " is not ready "
                            + "(the store had data before the index was added)");
                }
            }
            final String stored = StoreCheck.storedFingerprint(client, conf, o.genConfig());
            if (stored != null && !stored.equals(o.genConfig().fingerprint())) {
                throw new StoreCheck.Mismatch("store " + o.tablePrefix() + " was prepared for a different dataset");
            }

            final int servers = Math.max(1, client.instanceOperations().getTabletServers().size());
            final int splits = o.splits() < 0 ? 4 * servers - 1 : o.splits();
            if (splits > 0) {
                out.println("Planning " + splits + " splits per table (" + servers + " tablet servers)…");
                final Map<String, SortedSet<Text>> plan = SplitPlanner.plan(generator, conf, splits);
                SplitPlanner.apply(client, plan);
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
                final String user = client.whoami();
                final List<byte[]> merged = new ArrayList<>(client.securityOperations().getUserAuthorizations(user).getAuthorizations());
                for (final String label : o.genConfig().labels()) {
                    merged.add(label.getBytes(StandardCharsets.UTF_8));
                }
                client.securityOperations().changeUserAuthorizations(user, new Authorizations(merged));
                out.println("Added " + o.genConfig().labels() + " to the authorizations of " + user + ".");
            }
        } finally {
            dao.destroy();
        }
        printNextSteps(o, out);
    }

    private static void printNextSteps(final LoaderOptions o, final PrintStream out) {
        out.println();
        out.println("Dataset: " + o.genConfig().entities() + " entities (about "
                + Math.round(o.genConfig().entities() * LoaderOptions.triplesPerEntity(o.genConfig()) / 1e6) + "M triples), fingerprint "
                + o.genConfig().fingerprint().substring(0, 16) + "…");
        out.println();
        out.println("Load it (one process, or one per --part on as many machines as you like):");
        out.println("  run.sh load " + o.datasetArgs() + " --threads 8");
        out.println("  run.sh load " + o.datasetArgs() + " --part 0 --parts 2 --threads 8   # and --part 1 --parts 2");
        out.println();
        final StringBuilder flags = new StringBuilder("--rya.table-prefix=" + o.tablePrefix());
        flags.append(" --rya.text-index=").append(o.textIndex()).append(" --rya.geo-index=").append(o.geoIndex());
        if (!o.textPredicates().isEmpty()) {
            flags.append(" --rya.text-index-predicates=").append(String.join(",", o.textPredicates()));
        }
        if (!o.stats()) {
            flags.append(" --rya.stats=false");
        }
        if (!o.genConfig().labels().isEmpty()) {
            flags.append(" --rya.sparql.authorizations=").append(String.join(",", o.genConfig().labels()));
        }
        out.println("Serve it with the SPARQL endpoint:");
        out.println("  java -jar rya.sparql.jar <connection flags> " + flags);
    }
}
