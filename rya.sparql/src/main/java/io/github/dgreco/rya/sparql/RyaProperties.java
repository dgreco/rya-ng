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
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code rya.*}.
 *
 * @param backend the storage backend: {@code accumulo} or {@code hbase}.
 * @param accumulo how to reach Accumulo, when the backend is Accumulo.
 * @param hbase how to reach HBase, when the backend is HBase.
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
        @DefaultValue("accumulo") String backend,
        Accumulo accumulo,
        @DefaultValue HBase hbase,
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
     * How to reach HBase.
     *
     * @param site {@code hbase-site.xml} files (and {@code core-site.xml} for Kerberos); empty to use
     *        the ones on the classpath
     * @param conf HBase client settings, such as {@code rya.hbase.conf[hbase.zookeeper.quorum]}
     * @param labelsTable the table of label canaries (see the {@code labels} command of the loader)
     * @param counters {@code coprocessor} (rya-ng's coprocessor, installed on the region servers) or
     *        {@code increment}: how new counter tables store their deltas
     * @param countersJar the hdfs path the region servers load the coprocessor from, when it isn't on
     *        their classpath
     * @param user with simple authentication only, the account to connect as; with Kerberos, the
     *        logged-in principal is used
     */
    public record HBase(
            @DefaultValue List<String> site,
            @DefaultValue Map<String, String> conf,
            @DefaultValue("rya_labels") String labelsTable,
            @DefaultValue("coprocessor") String counters,
            String countersJar,
            String user) {
    }

    /**
     * @param authorizations the most a request may read with; requests may narrow this with
     *        the {@code auths} parameter. The storage account must hold all of them; on HBase the
     *        endpoint checks this at startup.
     * @param defaultVisibility visibility label for written statements; empty for none.
     * @param allowRequestVisibility whether an update may set its own label with the
     *        {@code visibility} parameter.
     * @param queryTimeout maximum query or update time, unless the request sets its own with the
     *        {@code timeout} parameter.
     * @param maxQueryTimeout the longest a request may ask for with {@code timeout}; zero for no limit.
     * @param allowTrace whether a request may be traced ({@code trace} parameter and the
     *        {@code /sparql/trace} stream), showing the storage operations it causes.
     */
    public record Sparql(
            @DefaultValue List<String> authorizations,
            @DefaultValue("") String defaultVisibility,
            @DefaultValue("false") boolean allowRequestVisibility,
            @DefaultValue("120s") Duration queryTimeout,
            @DefaultValue("30m") Duration maxQueryTimeout,
            @DefaultValue("true") boolean allowTrace) {
    }
}
