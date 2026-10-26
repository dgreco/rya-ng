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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.DELIM;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.EMPTY_TEXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.OBJ_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PREDOBJECT_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.PRED_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTOBJECT_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECTPRED_CF_TXT;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.SUBJECT_CF_TXT;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;
import io.github.dgreco.rya.api.persist.RdfDAOException;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;

/**
 * Reads the cardinality counters that {@link AccumuloRyaDAO} keeps in the eval table
 * (see {@link EvalStatsMutations}). Returns -1 for a value combination that was never written.
 */
public class AccumuloRdfEvalStatsDAO implements RdfEvalStatsDAO<AccumuloRdfConfiguration> {

    private final AtomicBoolean isInitialized = new AtomicBoolean();
    private AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
    private AccumuloClient client;
    private String evalTable;

    public AccumuloRdfEvalStatsDAO() {
    }

    public AccumuloRdfEvalStatsDAO(final AccumuloClient client, final AccumuloRdfConfiguration conf) {
        this.client = client;
        this.conf = conf;
    }

    @Override
    public void init() {
        if (isInitialized.get()) {
            return;
        }
        checkNotNull(client, "client");
        try {
            evalTable = conf.getTableLayoutStrategy().getEval();
            AccumuloRdfUtils.createTableIfNotExist(client.tableOperations(), evalTable);
            isInitialized.set(true);
        } catch (final Exception e) {
            throw new RdfDAOException(e);
        }
    }

    @Override
    public void destroy() {
        isInitialized.set(false);
    }

    @Override
    public boolean isInitialized() {
        return isInitialized.get();
    }

    public AccumuloClient getClient() {
        return client;
    }

    public void setClient(final AccumuloClient client) {
        this.client = client;
    }

    @Override
    public AccumuloRdfConfiguration getConf() {
        return conf;
    }

    @Override
    public void setConf(final AccumuloRdfConfiguration conf) {
        this.conf = conf;
    }

    @Override
    public double getCardinality(final AccumuloRdfConfiguration conf, final CARDINALITY_OF card, final List<Value> val) {
        return getCardinality(conf, card, val, null);
    }

    @Override
    public double getCardinality(AccumuloRdfConfiguration conf, final CARDINALITY_OF card, final List<Value> val,
            final Resource context) {
        if (conf == null) {
            conf = this.conf;
        }
        final Text cf = switch (card) {
            case SUBJECT -> SUBJECT_CF_TXT;
            case PREDICATE -> PRED_CF_TXT;
            case OBJECT -> OBJ_CF_TXT;
            case SUBJECTPREDICATE -> SUBJECTPRED_CF_TXT;
            case PREDICATEOBJECT -> PREDOBJECT_CF_TXT;
            case SUBJECTOBJECT -> SUBJECTOBJECT_CF_TXT;
        };
        final Text cq = context == null ? EMPTY_TEXT : new Text(context.stringValue().getBytes(StandardCharsets.UTF_8));
        final StringJoiner row = new StringJoiner(DELIM);
        for (final Value v : val) {
            row.add(v.stringValue());
        }

        try (Scanner scanner = client.createScanner(evalTable, conf.getAuthorizations())) {
            scanner.setRange(new Range(new Text(row.toString().getBytes(StandardCharsets.UTF_8))));
            scanner.fetchColumn(cf, cq);
            // One cell per visibility label; add up the ones this reader can see.
            long total = 0;
            boolean found = false;
            for (final Map.Entry<Key, org.apache.accumulo.core.data.Value> e : scanner) {
                total += Long.parseLong(new String(e.getValue().get(), StandardCharsets.UTF_8));
                found = true;
            }
            return found ? Math.max(total, 0) : -1;
        } catch (final Exception e) {
            throw new RdfDAOException(e);
        }
    }
}
