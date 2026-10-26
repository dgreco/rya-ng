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
package io.github.dgreco.rya.accumulo.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRdfUtils;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.resolver.RyaTripleContext;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;

/**
 * Keeps the enabled secondary indexes in step with the statement tables, and records whether
 * each one is complete.
 * <p>
 * An index is READY (queries may use it) when it was created together with the store, or after
 * a rebuild. Enabling it on a store that already has data, or changing its definition (for
 * example the indexed predicates), leaves it BUILDING until the store is started with
 * {@link RdfCloudTripleStoreConfiguration#CONF_INDEX_REBUILD}. An index table that exists while
 * its index is switched off stops the store from starting: writes would no longer reach it and
 * it would silently become incomplete.
 */
public final class IndexManager implements IndexCatalog {
    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);
    private static final Text RECORD_ROW = new Text("rya");
    private static final Text RECORD_CF = new Text("index");
    private static final int THREADS = 4;

    private final AccumuloClient client;
    private final AccumuloRdfConfiguration conf;
    private final TableLayoutStrategy tables;
    private final MultiTableBatchWriter mtbw;
    private final BatchWriter bwNs;
    private final Map<IndexType, SecondaryIndex> active = new EnumMap<>(IndexType.class);
    private final Map<IndexType, BatchWriter> writers = new EnumMap<>(IndexType.class);
    private final Map<IndexType, IndexState> states = new ConcurrentHashMap<>();
    private final Set<String> textPredicates;

    /**
     * Opens the enabled indexes, creating their tables when needed.
     *
     * @param storeCreated whether the statement tables were created just now (the store is empty)
     */
    public IndexManager(final AccumuloClient client, final AccumuloRdfConfiguration conf, final MultiTableBatchWriter mtbw,
            final BatchWriter bwNs, final boolean storeCreated) throws Exception {
        this.client = client;
        this.conf = conf;
        this.tables = conf.getTableLayoutStrategy();
        this.mtbw = mtbw;
        this.bwNs = bwNs;
        final TextIndex text = new TextIndex(conf.getTextIndexPredicates());
        this.textPredicates = text.predicates();

        final TableOperations ops = client.tableOperations();
        final List<SecondaryIndex> toClear = new ArrayList<>();
        for (final SecondaryIndex index : List.of(text, new GeoIndex())) {
            final IndexType type = index.type();
            final String table = type.table(tables);
            final boolean enabled = type.isEnabled(conf);
            final boolean exists = ops.exists(table);
            if (!enabled) {
                if (exists) {
                    throw new RyaDAOException("The " + type.id() + " index table " + table + " exists but "
                            + type.setting() + " is off. Turn the index back on, or delete the table to drop the index.");
                }
                continue;
            }
            final IndexState stored = readState(client, tables, type);
            final IndexState state;
            if (!exists) {
                AccumuloRdfUtils.createTableIfNotExist(ops, table);
                state = new IndexState(storeCreated ? IndexState.Status.READY : IndexState.Status.BUILDING, index.definition());
            } else if (stored == null) {
                state = new IndexState(IndexState.Status.BUILDING, index.definition());
            } else if (!stored.definition().equals(index.definition())) {
                if (!conf.isRebuildIndexes()) {
                    throw new RyaDAOException("The " + type.id() + " index was built with different settings ("
                            + stored.definition() + ", now " + index.definition() + "). Start once with "
                            + RdfCloudTripleStoreConfiguration.CONF_INDEX_REBUILD + "=true to rebuild it.");
                }
                state = new IndexState(IndexState.Status.BUILDING, index.definition());
                toClear.add(index);
            } else {
                state = stored;
            }
            active.put(type, index);
            writers.put(type, mtbw.getBatchWriter(table));
            if (state.equals(stored)) {
                states.put(type, state); // unchanged: no write, so a read-only user can open the store
            } else {
                setState(type, state);
            }
        }
        mtbw.flush();
        for (final SecondaryIndex index : toClear) {
            log.info("Clearing the {} index: its definition changed", index.type().id());
            ops.deleteRows(index.type().table(tables), null, null);
        }
        final List<SecondaryIndex> building = new ArrayList<>();
        for (final SecondaryIndex index : active.values()) {
            if (states.get(index.type()).status() == IndexState.Status.BUILDING) {
                building.add(index);
            }
        }
        if (!building.isEmpty()) {
            if (conf.isRebuildIndexes()) {
                rebuild(building);
            } else {
                for (final SecondaryIndex index : building) {
                    log.warn("The {} index does not cover the existing statements yet, so queries won't use it. "
                            + "Start once with {}=true to build it.", index.type().id(),
                            RdfCloudTripleStoreConfiguration.CONF_INDEX_REBUILD);
                }
            }
        }
    }

    /** Index entries for a statement being added. */
    public void add(final RyaStatement statement) throws Exception {
        for (final SecondaryIndex index : active.values()) {
            final List<Mutation> puts = index.puts(statement);
            if (!puts.isEmpty()) {
                writers.get(index.type()).addMutations(puts);
            }
        }
    }

    /** Removes the index entries of a stored statement (as read back from spo). */
    public void delete(final RyaStatement stored) throws Exception {
        for (final SecondaryIndex index : active.values()) {
            final List<Mutation> deletes = index.deletes(stored);
            if (!deletes.isEmpty()) {
                writers.get(index.type()).addMutations(deletes);
            }
        }
    }

    /** The tables of the enabled indexes. */
    public List<String> activeTables() {
        final List<String> names = new ArrayList<>();
        for (final IndexType type : active.keySet()) {
            names.add(type.table(tables));
        }
        return names;
    }

    /** Rewrites the state records, after {@code purge} deleted them with everything else. */
    public void rewriteRecords() throws Exception {
        for (final Map.Entry<IndexType, IndexState> e : states.entrySet()) {
            writeRecord(e.getKey(), e.getValue());
        }
        mtbw.flush();
    }

    /**
     * Rebuilds every enabled index from the statement tables, reading them with all the
     * authorizations of the Accumulo user (statements under labels it lacks are not indexed).
     */
    public void rebuildAll() throws Exception {
        rebuild(new ArrayList<>(active.values()));
    }

    private void rebuild(final List<SecondaryIndex> indexes) throws Exception {
        final Authorizations auths = client.securityOperations().getUserAuthorizations(client.whoami());
        final List<String> ids = indexes.stream().map(i -> i.type().id()).toList();
        log.info("Building the {} index(es) from {} with authorizations {}", ids, tables.getSpo(), auths);
        for (final SecondaryIndex index : indexes) {
            setState(index.type(), new IndexState(IndexState.Status.BUILDING, index.definition()));
        }
        mtbw.flush();
        final RyaTripleContext context = RyaTripleContext.getInstance(conf);
        long count = 0;
        try (BatchScanner scanner = client.createBatchScanner(tables.getSpo(), auths, THREADS)) {
            scanner.setRanges(Collections.singleton(new Range()));
            for (final Map.Entry<Key, Value> e : scanner) {
                final Key key = e.getKey();
                final RyaStatement st = context.deserializeTriple(TABLE_LAYOUT.SPO,
                        new TripleRow(key.getRowData().toArray(), key.getColumnFamilyData().toArray(),
                                key.getColumnQualifierData().toArray(), key.getTimestamp(),
                                key.getColumnVisibilityData().toArray(), e.getValue().get()));
                for (final SecondaryIndex index : indexes) {
                    final List<Mutation> puts = index.puts(st);
                    if (!puts.isEmpty()) {
                        writers.get(index.type()).addMutations(puts);
                    }
                }
                count++;
            }
        }
        mtbw.flush();
        for (final SecondaryIndex index : indexes) {
            setState(index.type(), new IndexState(IndexState.Status.READY, index.definition()));
        }
        mtbw.flush();
        log.info("Built the {} index(es) over {} statements", ids, count);
    }

    private void setState(final IndexType type, final IndexState state) throws Exception {
        states.put(type, state);
        writeRecord(type, state);
    }

    private void writeRecord(final IndexType type, final IndexState state) throws Exception {
        final Mutation m = new Mutation(RECORD_ROW);
        m.put(RECORD_CF, new Text(type.id()), new Value(state.encode().getBytes(StandardCharsets.UTF_8)));
        bwNs.addMutation(m);
    }

    /** The stored state of an index, or null if none is recorded. */
    public static IndexState readState(final AccumuloClient client, final TableLayoutStrategy tables, final IndexType type)
            throws Exception {
        try (Scanner scanner = client.createScanner(tables.getNs(), Authorizations.EMPTY)) {
            scanner.setRange(new Range(RECORD_ROW));
            scanner.fetchColumn(RECORD_CF, new Text(type.id()));
            final Iterator<Map.Entry<Key, Value>> it = scanner.iterator();
            return it.hasNext() ? IndexState.decode(new String(it.next().getValue().get(), StandardCharsets.UTF_8)) : null;
        }
    }

    /** The stored state of an enabled index; null when the index is off. */
    public IndexState state(final IndexType type) {
        return states.get(type);
    }

    @Override
    public boolean isReady(final IndexType type) {
        final IndexState state = states.get(type);
        return state != null && state.status() == IndexState.Status.READY;
    }

    @Override
    public Set<String> textPredicates() {
        return textPredicates;
    }

    @Override
    public String table(final IndexType type) {
        return type.table(tables);
    }
}
