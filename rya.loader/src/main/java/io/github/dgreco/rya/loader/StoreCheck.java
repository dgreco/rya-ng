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

import org.apache.accumulo.core.client.AccumuloClient;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.index.GeoIndex;
import io.github.dgreco.rya.accumulo.index.IndexManager;
import io.github.dgreco.rya.accumulo.index.IndexState;
import io.github.dgreco.rya.accumulo.index.IndexType;
import io.github.dgreco.rya.accumulo.index.TextIndex;
import io.github.dgreco.rya.accumulo.query.AccumuloRyaQueryEngine;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.loader.gen.GenConfig;
import io.github.dgreco.rya.loader.gen.ReferenceData;

/** Checks that a store and the options describe the same dataset and index settings. */
public final class StoreCheck {
    private StoreCheck() {
    }

    /** A store the loader can't use with these options. */
    public static final class Mismatch extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        Mismatch(final String message) {
            super(message);
        }
    }

    /** Before prepare: an existing store must already have the requested indexes (they can't be added on top). */
    public static void forPrepare(final AccumuloClient client, final LoaderOptions o) {
        final TableLayoutStrategy tables = o.rdfConf().getTableLayoutStrategy();
        if (!client.tableOperations().exists(tables.getSpo())) {
            return;
        }
        for (final IndexType type : IndexType.values()) {
            final boolean wanted = type == IndexType.TEXT ? o.textIndex() : o.geoIndex();
            final boolean present = client.tableOperations().exists(type.table(tables));
            if (wanted && !present) {
                throw new Mismatch("store " + o.tablePrefix() + " already exists without the " + type.id()
                        + " index; drop it first, or prepare without --" + type.id() + "-index");
            }
            if (!wanted && present) {
                throw new Mismatch("store " + o.tablePrefix() + " has a " + type.id() + " index: add --" + type.id() + "-index");
            }
        }
    }

    /** Before load or verify: prepared, same index settings, same dataset. */
    public static void forLoad(final AccumuloClient client, final LoaderOptions o) throws Exception {
        final AccumuloRdfConfiguration conf = o.rdfConf();
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        if (!client.tableOperations().exists(tables.getSpo())) {
            throw new Mismatch("store " + o.tablePrefix() + " does not exist: run prepare first");
        }
        for (final IndexType type : IndexType.values()) {
            final boolean wanted = type == IndexType.TEXT ? o.textIndex() : o.geoIndex();
            final boolean present = client.tableOperations().exists(type.table(tables));
            if (!wanted) {
                if (present) {
                    throw new Mismatch("store " + o.tablePrefix() + " has a " + type.id() + " index: add --" + type.id() + "-index");
                }
                continue;
            }
            if (!present) {
                throw new Mismatch("store " + o.tablePrefix() + " has no " + type.id() + " index: drop --" + type.id() + "-index");
            }
            final IndexState state = IndexManager.readState(client, tables, type);
            final String definition = type == IndexType.TEXT ? new TextIndex(o.textPredicates()).definition()
                    : new GeoIndex().definition();
            if (state == null || state.status() != IndexState.Status.READY) {
                throw new Mismatch("the " + type.id() + " index of " + o.tablePrefix() + " is not ready: run prepare first");
            }
            if (!state.definition().equals(definition)) {
                throw new Mismatch("the " + type.id() + " index of " + o.tablePrefix() + " was built with other settings ("
                        + state.definition() + "); use the same --text-predicates as prepare");
            }
        }
        final String stored = storedFingerprint(client, conf, o.genConfig());
        if (stored == null) {
            throw new Mismatch("store " + o.tablePrefix() + " was not prepared by the loader: run prepare first");
        }
        if (!stored.equals(o.genConfig().fingerprint())) {
            throw new Mismatch("store " + o.tablePrefix() + " holds a different dataset: use exactly the dataset options "
                    + "prepare printed (seed, size, graphs, labels, …)");
        }
    }

    /** The fingerprint recorded by prepare, or null if the dataset description is missing. */
    public static String storedFingerprint(final AccumuloClient client, final AccumuloRdfConfiguration conf, final GenConfig g)
            throws Exception {
        final AccumuloRyaQueryEngine engine = new AccumuloRyaQueryEngine(client, conf);
        try (CloseableIteration<RyaStatement> it = engine.query(new RyaStatement(new RyaIRI(g.datasetIri()),
                new RyaIRI(g.ontology(ReferenceData.FINGERPRINT)), null), conf)) {
            return it.hasNext() ? it.next().getObject().getData() : null;
        } finally {
            engine.close();
        }
    }
}
