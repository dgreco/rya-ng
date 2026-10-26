package io.github.dgreco.rya.api.resolver;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.query.strategy.TriplePatternStrategy;
import io.github.dgreco.rya.api.query.strategy.wholerow.HashedPoWholeRowTriplePatternStrategy;
import io.github.dgreco.rya.api.query.strategy.wholerow.HashedSpoWholeRowTriplePatternStrategy;
import io.github.dgreco.rya.api.query.strategy.wholerow.NullRowTriplePatternStrategy;
import io.github.dgreco.rya.api.query.strategy.wholerow.OspWholeRowTriplePatternStrategy;
import io.github.dgreco.rya.api.query.strategy.wholerow.PoWholeRowTriplePatternStrategy;
import io.github.dgreco.rya.api.query.strategy.wholerow.SpoWholeRowTriplePatternStrategy;
import io.github.dgreco.rya.api.resolver.triple.TripleRow;
import io.github.dgreco.rya.api.resolver.triple.TripleRowResolver;
import io.github.dgreco.rya.api.resolver.triple.TripleRowResolverException;
import io.github.dgreco.rya.api.resolver.triple.impl.WholeRowHashedTripleResolver;
import io.github.dgreco.rya.api.resolver.triple.impl.WholeRowTripleResolver;

/**
 * Date: 7/16/12
 * Time: 12:04 PM
 */
public class RyaTripleContext {

    private static final Logger logger = LoggerFactory.getLogger(RyaTripleContext.class);
    private TripleRowResolver tripleResolver;
    private final List<TriplePatternStrategy> triplePatternStrategyList = new ArrayList<TriplePatternStrategy>();

    public RyaTripleContext(final boolean addPrefixHash) {
        addDefaultTriplePatternStrategies(addPrefixHash);
        if (addPrefixHash){
        	tripleResolver = new WholeRowHashedTripleResolver();
        }
        else {
        	tripleResolver = new WholeRowTripleResolver();
        }
    }


    private static class RyaTripleContextHolder {
    	// TODO want to be able to support more variability in configuration here
        public static final RyaTripleContext INSTANCE = new RyaTripleContext(false);
        public static final RyaTripleContext HASHED_INSTANCE = new RyaTripleContext(true);
    }

    /** Thread-safe through the holder class; no lock needed. */
    public static RyaTripleContext getInstance(final RdfCloudTripleStoreConfiguration conf) {
    	if (conf.isPrefixRowsWithHash()){
    		return RyaTripleContextHolder.HASHED_INSTANCE;
    	}
        return RyaTripleContextHolder.INSTANCE;
    }


     public Map<RdfCloudTripleStoreConstants.TABLE_LAYOUT, TripleRow> serializeTriple(final RyaStatement statement) throws TripleRowResolverException {
        return getTripleResolver().serialize(statement);
    }

    public RyaStatement deserializeTriple(final RdfCloudTripleStoreConstants.TABLE_LAYOUT table_layout, final TripleRow tripleRow) throws TripleRowResolverException {
        return getTripleResolver().deserialize(table_layout, tripleRow);
    }

    protected void addDefaultTriplePatternStrategies(final boolean addPrefixHash) {
    	if (addPrefixHash){
            triplePatternStrategyList.add(new HashedSpoWholeRowTriplePatternStrategy());
            triplePatternStrategyList.add(new HashedPoWholeRowTriplePatternStrategy());
    	}
    	else {
            triplePatternStrategyList.add(new SpoWholeRowTriplePatternStrategy());
            triplePatternStrategyList.add(new PoWholeRowTriplePatternStrategy());
            triplePatternStrategyList.add(new NullRowTriplePatternStrategy());
    	}
        triplePatternStrategyList.add(new OspWholeRowTriplePatternStrategy());
    }

    //retrieve triple pattern strategy
    public TriplePatternStrategy retrieveStrategy(final RyaIRI subject, final RyaIRI predicate, final RyaType object, final RyaIRI context) {
        for (final TriplePatternStrategy strategy : triplePatternStrategyList) {
            if (strategy.handles(subject, predicate, object, context)) {
                return strategy;
            }
        }
        return null;
    }

    public TriplePatternStrategy retrieveStrategy(final RyaStatement stmt) {
        return retrieveStrategy(stmt.getSubject(), stmt.getPredicate(), stmt.getObject(), stmt.getContext());
    }

    public TriplePatternStrategy retrieveStrategy(final TABLE_LAYOUT layout) {
        for(final TriplePatternStrategy strategy : triplePatternStrategyList) {
            if (strategy.getLayout().equals(layout)) {
                return strategy;
            }
        }
        return null;
    }

   public TripleRowResolver getTripleResolver() {
        return tripleResolver;
    }

    public void setTripleResolver(final TripleRowResolver tripleResolver) {
        this.tripleResolver = tripleResolver;
    }
}
