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

import org.eclipse.rdf4j.common.iteration.CloseableIteration;

import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.index.GeoIndex;
import org.tripatha.store.spi.Store;
import org.tripatha.store.index.IndexManager;
import org.tripatha.store.index.IndexState;
import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.TextIndex;
import org.tripatha.store.query.RyaStoreQueryEngine;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.loader.gen.GenConfig;
import org.tripatha.loader.gen.ReferenceData;

/** Checks that a store and the options describe the same dataset and index settings. */
public final class StoreCheck {
    private StoreCheck() {
    }

    /** A store the loader can't use with these options. */
    public static final class Mismatch extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public Mismatch(final String message) {
            super(message);
        }
    }

    private static boolean wanted(final IndexType type, final LoaderOptions o) {
        return switch (type) {
            case TEXT -> o.textIndex();
            case GEO -> o.geoIndex();
            case ENTITY -> o.entityIndex();
        };
    }

    /** Before prepare: an existing store must already have the requested indexes (they can't be added on top). */
    public static void forPrepare(final Store store, final LoaderOptions o) {
        final TableLayoutStrategy tables = o.rdfConf().getTableLayoutStrategy();
        if (!store.tables().exists(tables.getSpo())) {
            return;
        }
        for (final IndexType type : IndexType.values()) {
            final boolean wanted = wanted(type, o);
            final boolean present = store.tables().exists(type.table(tables));
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
    public static void forLoad(final Store store, final LoaderOptions o) throws Exception {
        final StoreRdfConfiguration conf = o.rdfConf();
        final TableLayoutStrategy tables = conf.getTableLayoutStrategy();
        if (!store.tables().exists(tables.getSpo())) {
            throw new Mismatch("store " + o.tablePrefix() + " does not exist: run prepare first");
        }
        indexes(store, o);
        final String stored = storedFingerprint(store, conf, o.genConfig());
        if (stored == null) {
            throw new Mismatch("store " + o.tablePrefix() + " was not prepared by the loader: run prepare first");
        }
        if (!stored.equals(o.genConfig().fingerprint())) {
            throw new Mismatch("store " + o.tablePrefix() + " holds a different dataset: use exactly the dataset options "
                    + "prepare printed (seed, size, graphs, labels, …)");
        }
    }

    /**
     * A store's indexes are the ones asked for, READY, with the same definitions: writing to it
     * with other index settings would leave an index short of postings, or redefine it.
     */
    public static void indexes(final Store store, final LoaderOptions o) throws Exception {
        final TableLayoutStrategy tables = o.rdfConf().getTableLayoutStrategy();
        for (final IndexType type : IndexType.values()) {
            final boolean wanted = wanted(type, o);
            final boolean present = store.tables().exists(type.table(tables));
            if (!wanted) {
                if (present) {
                    throw new Mismatch("store " + o.tablePrefix() + " has a " + type.id() + " index: add --" + type.id() + "-index");
                }
                continue;
            }
            if (!present) {
                throw new Mismatch("store " + o.tablePrefix() + " has no " + type.id() + " index: drop --" + type.id() + "-index");
            }
            final IndexState state = IndexManager.readState(store, tables, type);
            final String definition = switch (type) {
                case TEXT -> new TextIndex(o.textPredicates()).definition();
                case GEO -> new GeoIndex().definition();
                case ENTITY -> new EntityIndex(o.entityShards()).definition();
            };
            if (state == null || state.status() != IndexState.Status.READY) {
                throw new Mismatch("the " + type.id() + " index of " + o.tablePrefix() + " is not ready: run prepare first");
            }
            if (!state.definition().equals(definition)) {
                throw new Mismatch("the " + type.id() + " index of " + o.tablePrefix() + " was built with other settings ("
                        + state.definition() + "); use the same --text-predicates and --entity-shards as prepare");
            }
        }
    }

    /** The fingerprint recorded by prepare, or null if the dataset description is missing. */
    public static String storedFingerprint(final Store store, final StoreRdfConfiguration conf, final GenConfig g)
            throws Exception {
        final RyaStoreQueryEngine engine = new RyaStoreQueryEngine(store, conf);
        try (CloseableIteration<RyaStatement> it = engine.query(new RyaStatement(new RyaIRI(g.datasetIri()),
                new RyaIRI(g.ontology(ReferenceData.FINGERPRINT)), null), conf)) {
            return it.hasNext() ? it.next().getObject().getData() : null;
        } finally {
            engine.close();
        }
    }
}
