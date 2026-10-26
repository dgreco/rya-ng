package io.github.dgreco.rya.api.persist.query;

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

import java.io.Closeable;
import java.util.Collection;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.persist.RyaConfigured;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;

/**
 * Rya Query Engine to perform queries against the Rya triple store.
 * <p/>
 * Date: 7/17/12
 * Time: 8:25 AM
 */
public interface RyaQueryEngine<C extends RdfCloudTripleStoreConfiguration> extends RyaConfigured<C>, Closeable {

    /**
     * Finds the statements matching a pattern. Null subject, predicate, object or context
     * act as wildcards. The configuration supplies auths, TTL, regex filters and so on.
     */
    public CloseableIteration<RyaStatement> query(RyaStatement stmt, C conf) throws RyaDAOException;


    /**
     * Returns the union of the statements matching each pattern.
     */
    public CloseableIteration<RyaStatement> batchQuery(Collection<RyaStatement> stmts, C conf) throws RyaDAOException;

}
