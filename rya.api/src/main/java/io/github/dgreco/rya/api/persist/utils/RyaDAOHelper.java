package io.github.dgreco.rya.api.persist.utils;


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

import java.util.NoSuchElementException;
import java.util.function.Function;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.resolver.RdfToRyaConversions;
import io.github.dgreco.rya.api.resolver.RyaToRdfConversions;
import io.github.dgreco.rya.api.utils.NullableStatementImpl;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.QueryEvaluationException;

/**
 * Runs statement lookups against a {@link RyaDAO} using RDF4J model types.
 * DAO failures surface as {@link QueryEvaluationException}.
 */
public final class RyaDAOHelper {

    private RyaDAOHelper() {
    }

    public static CloseableIteration<Statement> query(final RyaDAO<?> ryaDAO, final Resource subject, final IRI predicate,
            final Value object, final RdfCloudTripleStoreConfiguration conf, final Resource... contexts) {
        return query(ryaDAO, new NullableStatementImpl(subject, predicate, object, contexts), conf);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static CloseableIteration<Statement> query(final RyaDAO ryaDAO, final Statement stmt,
            final RdfCloudTripleStoreConfiguration conf) {
        final CloseableIteration<RyaStatement> query;
        try {
            query = ryaDAO.getQueryEngine().query(RdfToRyaConversions.convertStatement(stmt), conf);
        } catch (final RyaDAOException e) {
            throw new QueryEvaluationException(e);
        }
        return new Converting<>(query, RyaToRdfConversions::convertStatement);
    }

    /** Maps each element and rethrows DAO failures as {@link QueryEvaluationException}. */
    private static final class Converting<S, T> implements CloseableIteration<T> {
        private final CloseableIteration<S> source;
        private final Function<S, T> mapper;
        private boolean closed = false;

        Converting(final CloseableIteration<S> source, final Function<S, T> mapper) {
            this.source = source;
            this.mapper = mapper;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            try {
                return source.hasNext();
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                final S next = source.next();
                return next == null ? null : mapper.apply(next);
            } catch (final RyaDAOException e) {
                throw new QueryEvaluationException(e);
            }
        }

        @Override
        public void remove() {
            source.remove();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    source.close();
                } catch (final RyaDAOException e) {
                    throw new QueryEvaluationException(e);
                }
            }
        }
    }
}
