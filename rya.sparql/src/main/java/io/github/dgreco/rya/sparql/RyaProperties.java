package io.github.dgreco.rya.sparql;

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

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code rya.*}.
 *
 * @param accumulo how to reach Accumulo.
 * @param tablePrefix prefix of the Rya tables, e.g. {@code rya_}.
 * @param inference whether queries use OWL/RDFS inference.
 * @param inferenceRefresh how often the ontology is reloaded; changes to subClassOf,
 *        sameAs and so on take effect after the next reload.
 * @param stats whether writes keep cardinality statistics and queries use them for join ordering.
 * @param joinBatchSize how many left-hand rows of a join are looked up together in one batch
 *        scan; 1 turns batching off.
 * @param textIndex whether to keep the full-text index that speeds up {@code text:matches}.
 * @param textIndexPredicates the predicates whose text literals are indexed; empty for all.
 * @param geoIndex whether to keep the geospatial index that speeds up GeoSPARQL filters.
 * @param rebuildIndexes whether to build, at startup, indexes that don't cover the stored
 *        statements yet (turned on over existing data, or redefined).
 * @param sparql endpoint behavior.
 */
@ConfigurationProperties("rya")
public record RyaProperties(
        Accumulo accumulo,
        @DefaultValue("rya_") String tablePrefix,
        @DefaultValue("false") boolean inference,
        @DefaultValue("5m") Duration inferenceRefresh,
        @DefaultValue("true") boolean stats,
        @DefaultValue("1000") int joinBatchSize,
        @DefaultValue("false") boolean textIndex,
        @DefaultValue List<String> textIndexPredicates,
        @DefaultValue("false") boolean geoIndex,
        @DefaultValue("false") boolean rebuildIndexes,
        @DefaultValue Sparql sparql) {

    /**
     * Either {@code clientProperties} (path to an {@code accumulo-client.properties} file) or
     * instance, ZooKeepers, user and password.
     */
    public record Accumulo(
            String clientProperties,
            String instance,
            String zookeepers,
            String user,
            String password) {
    }

    /**
     * @param authorizations the most a request may read with; requests may narrow this with
     *        the {@code auths} parameter. The Accumulo user must hold all of them.
     * @param defaultVisibility visibility label for written statements; empty for none.
     * @param allowRequestVisibility whether an update may set its own label with the
     *        {@code visibility} parameter.
     * @param queryTimeout maximum query or update time.
     */
    public record Sparql(
            @DefaultValue List<String> authorizations,
            @DefaultValue("") String defaultVisibility,
            @DefaultValue("false") boolean allowRequestVisibility,
            @DefaultValue("120s") Duration queryTimeout) {
    }
}
