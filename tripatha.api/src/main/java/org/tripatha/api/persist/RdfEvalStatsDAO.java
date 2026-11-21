package org.tripatha.api.persist;

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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;

/**
 * Class RdfEvalStatsDAO
 * Date: Feb 28, 2012
 * Time: 4:17:05 PM
 */
public interface RdfEvalStatsDAO<C extends RdfCloudTripleStoreConfiguration> {
    public enum CARDINALITY_OF {
        SUBJECT, PREDICATE, OBJECT, SUBJECTPREDICATE, SUBJECTOBJECT, PREDICATEOBJECT
    }

    public void init() throws RdfDAOException;

    public boolean isInitialized() throws RdfDAOException;

    public void destroy() throws RdfDAOException;

    // XXX returns -1 if no cardinality could be found.
    public double getCardinality(C conf, CARDINALITY_OF card, List<Value> val) throws RdfDAOException;
    public double getCardinality(C conf, CARDINALITY_OF card, List<Value> val, Resource context) throws RdfDAOException;

    /** One counter the planner reads: which one, its values, and its graph (null: all graphs). */
    record CounterRead(CARDINALITY_OF card, List<Value> values, Resource context) {
    }

    /**
     * Several counters at once (SCALE-STUDY.md M14): each read's {@link #getCardinality} value,
     * -1 when it has none. This default reads them one by one; a store with a round trip per
     * read reads them together.
     */
    default Map<CounterRead, Double> getCardinalities(final C conf, final Collection<CounterRead> reads) throws RdfDAOException {
        final Map<CounterRead, Double> out = new LinkedHashMap<>();
        for (final CounterRead r : reads) {
            out.put(r, getCardinality(conf, r.card(), r.values(), r.context()));
        }
        return out;
    }

    public void setConf(C conf);

    public C getConf();

}
