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
package io.github.dgreco.rya.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;
import io.github.dgreco.rya.api.layout.TablePrefixLayoutStrategy;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

/**
 * Rdf triple store specific configuration
 */
public abstract class RdfCloudTripleStoreConfiguration extends Configuration {

    //    public static final String CONF_ISQUERYTIMEBASED = "query.timebased";
    public static final String CONF_TTL = "query.ttl";
    public static final String CONF_STARTTIME = "query.startTime";
    //    public static final String CONF_TIMEINDEXURIS = "query.timeindexuris";
    public static final String CONF_NUM_THREADS = "query.numthreads";
    public static final String CONF_PERFORMANT = "query.performant";
    public static final String CONF_INFER = "query.infer";
    public static final String CONF_USE_STATS = "query.usestats";
    public static final String CONF_USE_COMPOSITE = "query.usecompositecard";
    public static final String CONF_TBL_PREFIX = "query.tblprefix";
    public static final String CONF_BATCH_SIZE = "query.batchsize";
    /** How many left-hand rows of a join are looked up together in one batch scan; 1 or less disables it. */
    public static final String CONF_JOIN_BATCH_SIZE = "query.join.batchsize";
    /** Most statements one join batch may prefetch; beyond it the batch falls back to per-row lookups. */
    public static final String CONF_JOIN_PREFETCH_LIMIT = "query.join.prefetchlimit";
    /** Maintain the full-text index (table {@code <prefix>text}). */
    public static final String CONF_INDEX_TEXT = "index.text";
    /** Predicates whose text literals are indexed; empty means every text literal. */
    public static final String CONF_INDEX_TEXT_PREDICATES = "index.text.predicates";
    /** Maintain the geospatial index (table {@code <prefix>geo}). */
    public static final String CONF_INDEX_GEO = "index.geo";
    /** Rebuild indexes that are not ready (enabled on existing data, or redefined) when the store starts. */
    public static final String CONF_INDEX_REBUILD = "index.rebuild";
    /** Let queries use the secondary indexes (they are still maintained when this is false). */
    public static final String CONF_USE_INDEX = "query.useindex";
    public static final String CONF_OFFSET = "query.offset";
    public static final String CONF_LIMIT = "query.limit";
    public static final String CONF_QUERYPLAN_FLAG = "query.printqueryplan";
    public static final String CONF_QUERY_AUTH = "query.auth";
    public static final String CONF_RESULT_FORMAT = "query.resultformat";
    public static final String CONF_CV = "conf.cv";
    public static final String CONF_TBL_SPO = "tbl.spo";
    public static final String CONF_TBL_PO = "tbl.po";
    public static final String CONF_TBL_OSP = "tbl.osp";
    public static final String CONF_TBL_NS = "tbl.ns";
    public static final String CONF_TBL_EVAL = "tbl.eval";
    public static final String CONF_PREFIX_ROW_WITH_HASH = "tbl.hashprefix";
    public static final String STATS_PUSH_EMPTY_RDFTYPE_DOWN = "conf.stats.rdftype.down";
    public static final String INFER_INCLUDE_ALL_VALUES_FROM = "infer.include.allvaluesfrom";
    public static final String INFER_INCLUDE_DOMAIN_RANGE = "infer.include.domainrange";
    public static final String INFER_INCLUDE_HAS_SELF = "infer.include.hasself";
    public static final String INFER_INCLUDE_HAS_VALUE = "infer.include.hasvalue";
    public static final String INFER_INCLUDE_INTERSECTION_OF = "infer.include.intersectionof";
    public static final String INFER_INCLUDE_INVERSEOF = "infer.include.inverseof";
    public static final String INFER_INCLUDE_ONE_OF = "infer.include.oneof";
    public static final String INFER_INCLUDE_PROPERTY_CHAIN = "infer.include.propertychain";
    public static final String INFER_INCLUDE_REFLEXIVE_PROP = "infer.include.reflexiveprop";
    public static final String INFER_INCLUDE_SOME_VALUES_FROM = "infer.include.somevaluesfrom";
    public static final String INFER_INCLUDE_SAME_AS = "infer.include.sameas";
    public static final String INFER_INCLUDE_SUBCLASSOF = "infer.include.subclassof";
    public static final String INFER_INCLUDE_SUBPROPOF = "infer.include.subpropof";
    public static final String INFER_INCLUDE_SYMMPROP = "infer.include.symmprop";
    public static final String INFER_INCLUDE_TRANSITIVEPROP = "infer.include.transprop";

    public static final String RDF_DAO_CLASS = "class.rdf.dao";
    public static final String RDF_EVAL_STATS_DAO_CLASS = "class.rdf.evalstats";

    public static final String REGEX_SUBJECT = "query.regex.subject";
    public static final String REGEX_PREDICATE = "query.regex.predicate";
    public static final String REGEX_OBJECT = "query.regex.object";
    private static final String[] EMPTY_STR_ARR = new String[0];

    private TableLayoutStrategy tableLayoutStrategy = new TablePrefixLayoutStrategy();

    public RdfCloudTripleStoreConfiguration() {
    }

    public RdfCloudTripleStoreConfiguration(final Configuration other) {
        super(other);
        if (other instanceof RdfCloudTripleStoreConfiguration) {
            setTableLayoutStrategy(((RdfCloudTripleStoreConfiguration) other).getTableLayoutStrategy());
        }
    }

    @Override
    public abstract RdfCloudTripleStoreConfiguration clone();

    public TableLayoutStrategy getTableLayoutStrategy() {
        return tableLayoutStrategy;
    }

    public void setTableLayoutStrategy(final TableLayoutStrategy tableLayoutStrategy) {
        if (tableLayoutStrategy != null) {
            this.tableLayoutStrategy = tableLayoutStrategy;
        } else {
            this.tableLayoutStrategy = new TablePrefixLayoutStrategy(); //default
        }
        set(CONF_TBL_SPO, this.tableLayoutStrategy.getSpo());
        set(CONF_TBL_PO, this.tableLayoutStrategy.getPo());
        set(CONF_TBL_OSP, this.tableLayoutStrategy.getOsp());
        set(CONF_TBL_NS, this.tableLayoutStrategy.getNs());
        set(CONF_TBL_EVAL, this.tableLayoutStrategy.getEval());
    }

    public Long getTtl() {
        final String val = get(CONF_TTL);
        if (val != null) {
            return Long.valueOf(val);
        }
        return null;
    }

    public void setTtl(final Long ttl) {
        Preconditions.checkNotNull(ttl);
        Preconditions.checkArgument(ttl >= 0, "ttl must be non negative");
        set(CONF_TTL, ttl.toString());
    }

    public Long getStartTime() {
        final String val = get(CONF_STARTTIME);
        if (val != null) {
            return Long.valueOf(val);
        }
        return null;
    }

    public void setStartTime(final Long startTime) {
        Preconditions.checkNotNull(startTime);
        Preconditions.checkArgument(startTime >= 0, "startTime must be non negative");
        set(CONF_STARTTIME, startTime.toString());
    }

    public Integer getNumThreads() {
        return getInt(CONF_NUM_THREADS, 2);
    }

    public void setNumThreads(final Integer numThreads) {
        Preconditions.checkNotNull(numThreads);
        Preconditions.checkArgument(numThreads > 0, "numThreads must be greater than 0");
        setInt(CONF_NUM_THREADS, numThreads);
    }

    public Boolean isPerformant() {
        return getBoolean(CONF_PERFORMANT, true);
    }

    public void setPerformant(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(CONF_PERFORMANT, val);
    }

    public Boolean isInfer() {
        return getBoolean(CONF_INFER, false);
    }

    public void setInfer(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(CONF_INFER, val);
    }

    public Boolean isUseStats() {
        return getBoolean(CONF_USE_STATS, true);
    }

    public void setUseStats(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(CONF_USE_STATS, val);
    }

    public boolean isTextIndex() {
        return getBoolean(CONF_INDEX_TEXT, false);
    }

    public void setTextIndex(final boolean enabled) {
        setBoolean(CONF_INDEX_TEXT, enabled);
    }

    /** The predicate IRIs whose text literals are indexed; empty means all of them. */
    public List<String> getTextIndexPredicates() {
        final List<String> predicates = new ArrayList<>();
        for (final String p : getTrimmedStrings(CONF_INDEX_TEXT_PREDICATES)) {
            if (!p.isEmpty()) {
                predicates.add(p);
            }
        }
        return Collections.unmodifiableList(predicates);
    }

    public void setTextIndexPredicates(final Collection<String> predicates) {
        Preconditions.checkNotNull(predicates);
        setStrings(CONF_INDEX_TEXT_PREDICATES, predicates.toArray(new String[0]));
    }

    public boolean isGeoIndex() {
        return getBoolean(CONF_INDEX_GEO, false);
    }

    public void setGeoIndex(final boolean enabled) {
        setBoolean(CONF_INDEX_GEO, enabled);
    }

    public boolean isRebuildIndexes() {
        return getBoolean(CONF_INDEX_REBUILD, false);
    }

    public void setRebuildIndexes(final boolean rebuild) {
        setBoolean(CONF_INDEX_REBUILD, rebuild);
    }

    public boolean isUseIndex() {
        return getBoolean(CONF_USE_INDEX, true);
    }

    public void setUseIndex(final boolean use) {
        setBoolean(CONF_USE_INDEX, use);
    }

    public Boolean isPrefixRowsWithHash() {
        return getBoolean(CONF_PREFIX_ROW_WITH_HASH, false);
    }

    public void setPrefixRowsWithHash(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(CONF_PREFIX_ROW_WITH_HASH, val);
    }

    public String getTablePrefix() {
        return get(CONF_TBL_PREFIX, RdfCloudTripleStoreConstants.TBL_PRFX_DEF);
    }

    public void setTablePrefix(final String tablePrefix) {
        Preconditions.checkNotNull(tablePrefix);
        set(CONF_TBL_PREFIX, tablePrefix);
        setTableLayoutStrategy(new TablePrefixLayoutStrategy(tablePrefix)); //TODO: Should we change the layout strategy
    }

    public Integer getBatchSize() {
        final String val = get(CONF_BATCH_SIZE);
        if (val != null) {
            return Integer.valueOf(val);
        }
        return null;
    }

    public void setBatchSize(final Long batchSize) {
        Preconditions.checkNotNull(batchSize);
        Preconditions.checkArgument(batchSize > 0, "Batch Size must be greater than 0");
        setLong(CONF_BATCH_SIZE, batchSize);
    }

    public Long getOffset() {
        final String val = get(CONF_OFFSET);
        if (val != null) {
            return Long.valueOf(val);
        }
        return null;
    }

    public void setOffset(final Long offset) {
        Preconditions.checkNotNull(offset);
        Preconditions.checkArgument(offset >= 0, "offset must be positive");
        setLong(CONF_OFFSET, offset);
    }

    public Long getLimit() {
        final String val = get(CONF_LIMIT);
        if (val != null) {
            return Long.valueOf(val);
        }
        return null;
    }

    public void setLimit(final Long limit) {
        Preconditions.checkNotNull(limit);
        Preconditions.checkArgument(limit >= 0, "limit must be positive");
        setLong(CONF_LIMIT, limit);
    }

    public Boolean isDisplayQueryPlan() {
        return getBoolean(CONF_QUERYPLAN_FLAG, false);
    }

    public void setDisplayQueryPlan(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(CONF_QUERYPLAN_FLAG, val);
    }

    /**
     * @return
     * @deprecated
     */
    @Deprecated
    public String getAuth() {
        return Joiner.on(",").join(getAuths());
    }

    /**
     * @param auth
     * @deprecated
     */
    @Deprecated
    public void setAuth(final String auth) {
        Preconditions.checkNotNull(auth);
        setStrings(CONF_QUERY_AUTH, auth);
    }

    public String[] getAuths() {
        return getStrings(CONF_QUERY_AUTH, EMPTY_STR_ARR);
    }

    public void setAuths(final String... auths) {
        Preconditions.checkNotNull(auths);
        setStrings(CONF_QUERY_AUTH, auths);
    }

    public String getEmit() {
        return get(CONF_RESULT_FORMAT);
    }

    public void setEmit(final String emit) {
        Preconditions.checkNotNull(emit);
        set(CONF_RESULT_FORMAT, emit);
    }

    public String getCv() {
        return get(CONF_CV);
    }

    public void setCv(final String cv) {
        Preconditions.checkNotNull(cv);
        set(CONF_CV, cv);
    }

    public Boolean isUseCompositeCardinality() {
        return getBoolean(CONF_USE_COMPOSITE, true);
    }

    public void setCompositeCardinality(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(CONF_USE_COMPOSITE, val);
    }

    public Boolean isStatsPushEmptyRdftypeDown() {
        return getBoolean(STATS_PUSH_EMPTY_RDFTYPE_DOWN, true);
    }

    public void setStatsPushEmptyRdftypeDown(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(STATS_PUSH_EMPTY_RDFTYPE_DOWN, val);
    }

    /**
     * @return {@code true} if owl:allValuesFrom inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferAllValuesFrom() {
        return getBoolean(INFER_INCLUDE_ALL_VALUES_FROM, true);
    }

    /**
     * Sets whether owl:allValuesFrom inferencing is enabled or disabled.
     * @param value {@code true} if owl:allValuesFrom inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferAllValuesFrom(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_ALL_VALUES_FROM, value);
    }

    /**
     * @return {@code true} if rdfs:domain and rdfs:range inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferDomainRange() {
        return getBoolean(INFER_INCLUDE_DOMAIN_RANGE, true);
    }

    /**
     * Sets whether rdfs:domain and rdfs:range inferencing is enabled or disabled.
     * @param val {@code true} if rdfs:domain/range inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferDomainRange(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(INFER_INCLUDE_DOMAIN_RANGE, val);
    }

    /**
     * @return {@code true} if owl:hasSelf inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferHasSelf() {
        return getBoolean(INFER_INCLUDE_HAS_SELF, true);
    }

    /**
     * Sets whether owl:hasSelf inferencing is enabled or disabled.
     * @param val {@code true} if owl:hasSelf inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferHasSelf(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(INFER_INCLUDE_HAS_SELF, val);
    }

    /**
     * @return {@code true} if owl:hasValue inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferHasValue() {
        return getBoolean(INFER_INCLUDE_HAS_VALUE, true);
    }

    /**
     * Sets whether owl:hasValue inferencing is enabled or disabled.
     * @param value {@code true} if owl:hasValue inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferHasValue(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_HAS_VALUE, value);
    }

    /**
     * @return {@code true} if owl:intersectionOf inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferIntersectionOf() {
        return getBoolean(INFER_INCLUDE_INTERSECTION_OF, true);
    }

    /**
     * Sets whether owl:intersectionOf inferencing is enabled or disabled.
     * @param value {@code true} if owl:intersectionOf inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferIntersectionOf(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_INTERSECTION_OF, value);
    }

    /**
     * @return {@code true} if owl:inverseOf inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferInverseOf() {
        return getBoolean(INFER_INCLUDE_INVERSEOF, true);
    }

    /**
     * Sets whether owl:inverseOf inferencing is enabled or disabled.
     * @param val {@code true} if owl:inverseOf inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferInverseOf(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(INFER_INCLUDE_INVERSEOF, val);
    }

    /**
     * @return {@code true} if owl:oneOf inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferOneOf() {
        return getBoolean(INFER_INCLUDE_ONE_OF, true);
    }

    /**
     * Sets whether owl:oneOf inferencing is enabled or disabled.
     * @param value {@code true} if owl:oneOf inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferOneOf(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_ONE_OF, value);
    }

    /**
     * @return {@code true} if owl:propertyChainAxiom inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferPropertyChain() {
        return getBoolean(INFER_INCLUDE_PROPERTY_CHAIN, true);
    }

    /**
     * Sets whether owl:propertyChainAxiom inferencing is enabled or disabled.
     * @param value {@code true} if owl:propertyChainAxiom inferencing is
     * enabled. {@code false} otherwise.
     */
    public void setInferPropertyChain(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_PROPERTY_CHAIN, value);
    }

    /**
     * @return {@code true} if owl:ReflexiveProperty inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferReflexiveProperty() {
        return getBoolean(INFER_INCLUDE_REFLEXIVE_PROP, true);
    }

    /**
     * Sets whether owl:ReflexiveProperty inferencing is enabled or disabled.
     * @param value {@code true} if owl:ReflexiveProperty inferencing is
     * enabled. {@code false} otherwise.
     */
    public void setInferReflexiveProperty(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_REFLEXIVE_PROP, value);
    }

    /**
     * @return {@code true} if owl:sameAs inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferSameAs() {
        return getBoolean(INFER_INCLUDE_SAME_AS, true);
    }

    /**
     * Sets whether owl:sameAs inferencing is enabled or disabled.
     * @param value {@code true} if owl:sameAs inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferSameAs(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_SAME_AS, value);
    }

    /**
     * @return {@code true} if owl:someValuesFrom inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferSomeValuesFrom() {
        return getBoolean(INFER_INCLUDE_SOME_VALUES_FROM, true);
    }

    /**
     * Sets whether owl:someValuesFrom inferencing is enabled or disabled.
     * @param value {@code true} if owl:someValuesFrom inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferSomeValuesFrom(final Boolean value) {
        Preconditions.checkNotNull(value);
        setBoolean(INFER_INCLUDE_SOME_VALUES_FROM, value);
    }

    /**
     * @return {@code true} if rdfs:subClassOf inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferSubClassOf() {
        return getBoolean(INFER_INCLUDE_SUBCLASSOF, true);
    }

    /**
     * Sets whether rdfs:subClassOf inferencing is enabled or disabled.
     * @param val {@code true} if rdfs:subClassOf inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferSubClassOf(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(INFER_INCLUDE_SUBCLASSOF, val);
    }

    /**
     * @return {@code true} if rdfs:subPropertyOf inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferSubPropertyOf() {
        return getBoolean(INFER_INCLUDE_SUBPROPOF, true);
    }

    /**
     * Sets whether rdfs:subPropertyOf inferencing is enabled or disabled.
     * @param val {@code true} if rdfs:subPropertyOf inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferSubPropertyOf(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(INFER_INCLUDE_SUBPROPOF, val);
    }

    /**
     * @return {@code true} if owl:SymmetricProperty inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferSymmetricProperty() {
        return getBoolean(INFER_INCLUDE_SYMMPROP, true);
    }

    /**
     * Sets whether owl:SymmetricProperty inferencing is enabled or disabled.
     * @param val {@code true} if owl:SymmetricProperty inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferSymmetricProperty(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(INFER_INCLUDE_SYMMPROP, val);
    }

    /**
     * @return {@code true} if owl:TransitiveProperty inferencing is enabled.
     * {@code false} otherwise. Defaults to {@code true} if nothing is
     * specified.
     */
    public Boolean isInferTransitiveProperty() {
        return getBoolean(INFER_INCLUDE_TRANSITIVEPROP, true);
    }

    /**
     * Sets whether owl:TransitiveProperty inferencing is enabled or disabled.
     * @param val {@code true} if owl:TransitiveProperty inferencing is enabled.
     * {@code false} otherwise.
     */
    public void setInferTransitiveProperty(final Boolean val) {
        Preconditions.checkNotNull(val);
        setBoolean(INFER_INCLUDE_TRANSITIVEPROP, val);
    }

    public void setRdfEvalStatsDaoClass(final Class<? extends RdfEvalStatsDAO> rdfEvalStatsDaoClass) {
        Preconditions.checkNotNull(rdfEvalStatsDaoClass);
        setClass(RDF_EVAL_STATS_DAO_CLASS, rdfEvalStatsDaoClass, RdfEvalStatsDAO.class);
    }

    public Class<? extends RdfEvalStatsDAO> getRdfEvalStatsDaoClass() {
        return getClass(RDF_EVAL_STATS_DAO_CLASS, null, RdfEvalStatsDAO.class);
    }

    public String getRegexSubject() {
        return get(REGEX_SUBJECT);
    }

    public void setRegexSubject(final String regexSubject) {
        Preconditions.checkNotNull(regexSubject);
        set(REGEX_SUBJECT, regexSubject);
    }

    public String getRegexPredicate() {
        return get(REGEX_PREDICATE);
    }

    public void setRegexPredicate(final String regex) {
        Preconditions.checkNotNull(regex);
        set(REGEX_PREDICATE, regex);
    }

    public String getRegexObject() {
        return get(REGEX_OBJECT);
    }

    public void setRegexObject(final String regex) {
        Preconditions.checkNotNull(regex);
        set(REGEX_OBJECT, regex);
    }

    /** @return left rows per batched join lookup (default 1000); 1 or less means no batching. */
    public int getJoinBatchSize() {
        return getInt(CONF_JOIN_BATCH_SIZE, 1000);
    }

    public void setJoinBatchSize(final int size) {
        setInt(CONF_JOIN_BATCH_SIZE, size);
    }

    /** @return most statements a single join batch may hold in memory (default 100,000). */
    public int getJoinPrefetchLimit() {
        return getInt(CONF_JOIN_PREFETCH_LIMIT, 100_000);
    }

    public void setJoinPrefetchLimit(final int limit) {
        setInt(CONF_JOIN_PREFETCH_LIMIT, limit);
    }
}
