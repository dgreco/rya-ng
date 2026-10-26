package io.github.dgreco.rya.accumulo;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.DELIM;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.EMPTY_TEXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.OBJ_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PREDOBJECT_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PRED_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTOBJECT_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTPRED_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECT_CF_TXT;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.Combiner;
import org.apache.accumulo.core.iterators.LongCombiner;
import org.apache.accumulo.core.iterators.user.SummingCombiner;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import io.github.dgreco.rya.api.domain.RyaStatement;

/**
 * Builds the cardinality-counter mutations for the eval table, and the named-graph markers.
 *
 * <p>Each statement adds +1 (or -1 on delete) to six counters: its subject, predicate, object,
 * subject+predicate, predicate+object and subject+object. The row is the values joined with
 * {@code DELIM}, the column family says which combination it is, and the column qualifier is
 * empty (all graphs) plus, for a named-graph statement, a second cell with the graph IRI.
 * The cell carries the statement's visibility, so a reader only counts what it may see.
 * Accumulo's {@link SummingCombiner} adds the cells up server side.
 *
 * <p>The counts drive join ordering only, so they may drift (re-inserting an existing
 * statement counts it twice; dropping a graph does not adjust the all-graphs counters).
 */
public final class EvalStatsMutations {
    /** Name of the combiner attached to the eval table. */
    public static final String COMBINER_NAME = "ryaStatsSum";
    /** Combiners must run before the default VersioningIterator (priority 20). */
    public static final int COMBINER_PRIORITY = 10;

    /** Column family of the named-graph marker rows. */
    public static final Text GRAPH_CF_TXT = new Text("g");

    private static final Value PLUS_ONE = new Value("1".getBytes(StandardCharsets.UTF_8));
    private static final Value MINUS_ONE = new Value("-1".getBytes(StandardCharsets.UTF_8));

    private EvalStatsMutations() {
    }

    /** @return the combiner setting that sums the string-encoded counters. */
    public static IteratorSetting combinerSetting() {
        final IteratorSetting setting = new IteratorSetting(COMBINER_PRIORITY, COMBINER_NAME, SummingCombiner.class);
        LongCombiner.setEncodingType(setting, LongCombiner.Type.STRING);
        Combiner.setCombineAllColumns(setting, true);
        return setting;
    }

    /**
     * @param stmt the statement added or removed.
     * @param add true for an insert (+1), false for a delete (-1).
     */
    public static List<Mutation> counters(final RyaStatement stmt, final boolean add) {
        final String s = stmt.getSubject().getData();
        final String p = stmt.getPredicate().getData();
        final String o = stmt.getObject().getData();
        final Text context = stmt.getContext() == null ? null : new Text(stmt.getContext().getData());
        final ColumnVisibility cv = visibility(stmt);
        final Value delta = add ? PLUS_ONE : MINUS_ONE;

        final List<Mutation> mutations = new ArrayList<>(6);
        mutations.add(counter(s, SUBJECT_CF_TXT, context, cv, delta));
        mutations.add(counter(p, PRED_CF_TXT, context, cv, delta));
        mutations.add(counter(o, OBJ_CF_TXT, context, cv, delta));
        mutations.add(counter(s + DELIM + p, SUBJECTPRED_CF_TXT, context, cv, delta));
        mutations.add(counter(p + DELIM + o, PREDOBJECT_CF_TXT, context, cv, delta));
        mutations.add(counter(s + DELIM + o, SUBJECTOBJECT_CF_TXT, context, cv, delta));
        return mutations;
    }

    /**
     * @return a marker saying the statement's graph has data, or null for the default graph.
     *         Writing it again is harmless.
     */
    public static Mutation graphMarker(final RyaStatement stmt) {
        if (stmt.getContext() == null) {
            return null;
        }
        final Mutation m = new Mutation(new Text(stmt.getContext().getData()));
        m.put(GRAPH_CF_TXT, EMPTY_TEXT, visibility(stmt), new Value(new byte[0]));
        return m;
    }

    private static Mutation counter(final String row, final Text cf, final Text context, final ColumnVisibility cv,
            final Value delta) {
        final Mutation m = new Mutation(new Text(row.getBytes(StandardCharsets.UTF_8)));
        m.put(cf, EMPTY_TEXT, cv, delta);
        if (context != null) {
            m.put(cf, context, cv, delta);
        }
        return m;
    }

    private static ColumnVisibility visibility(final RyaStatement stmt) {
        final byte[] cv = stmt.getColumnVisibility();
        return cv == null ? new ColumnVisibility() : new ColumnVisibility(cv);
    }
}
