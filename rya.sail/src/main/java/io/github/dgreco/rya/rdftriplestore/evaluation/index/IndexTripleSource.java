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
package io.github.dgreco.rya.rdftriplestore.evaluation.index;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.ConvertingIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.accumulo.index.IndexCatalog;
import io.github.dgreco.rya.accumulo.index.IndexQuery;
import io.github.dgreco.rya.accumulo.index.IndexSearcher;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.api.resolver.RdfToRyaConversions;
import io.github.dgreco.rya.api.resolver.RyaToRdfConversions;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;

/**
 * Statement lookups for an {@link IndexedStatementPattern}: from the index when neither the
 * subject nor the object is known, otherwise from the store (a bound lookup is cheaper, and it
 * goes through the store's batched-join cache). RDF4J's own statement-pattern step drives it, so
 * graph scopes, datasets and already-bound variables are handled exactly as for any pattern.
 */
public class IndexTripleSource implements TripleSource {
    private final StoreTripleSource<?> store;
    private final IndexQuery query;
    private final IndexCatalog catalog;
    private final IndexSearcher searcher;
    private final AccumuloRdfConfiguration conf;

    public IndexTripleSource(final StoreTripleSource<?> store, final IndexQuery query) {
        this.store = store;
        this.query = query;
        final AccumuloRyaDAO dao = (AccumuloRyaDAO) store.getRyaDAO();
        this.catalog = dao.getIndexCatalog();
        this.searcher = new IndexSearcher(dao);
        this.conf = (AccumuloRdfConfiguration) store.getConf();
    }

    /** Whether this store supports index lookups. */
    public static boolean supports(final TripleSource source) {
        return source instanceof StoreTripleSource<?> s && s.getRyaDAO() instanceof AccumuloRyaDAO
                && s.getConf() instanceof AccumuloRdfConfiguration;
    }

    @Override
    public CloseableIteration<? extends Statement> getStatements(final Resource subject, final IRI predicate,
            final Value object, final Resource... contexts) throws QueryEvaluationException {
        if (subject != null || object != null || !indexed(predicate)) {
            return store.getStatements(subject, predicate, object, contexts);
        }
        final CloseableIteration<RyaStatement> found;
        try {
            found = searcher.search(query, predicate == null ? null : predicate.stringValue(), graphs(contexts), conf);
        } catch (final RyaDAOException e) {
            throw new QueryEvaluationException(e);
        }
        return new ConvertingIteration<RyaStatement, Statement>(found) {
            @Override
            protected Statement convert(final RyaStatement st) {
                return RyaToRdfConversions.convertStatement(st);
            }
        };
    }

    /** Whether the index covers statements with this predicate (or every predicate, if unknown). */
    private boolean indexed(final IRI predicate) {
        if (!(query instanceof IndexQuery.Text)) {
            return true;
        }
        return predicate == null ? catalog.textPredicates().isEmpty() : catalog.textCovers(predicate.stringValue());
    }

    /** RDF4J contexts as index graphs: none means every graph, {@code null} the default graph. */
    private static Set<String> graphs(final Resource... contexts) {
        if (contexts == null || contexts.length == 0) {
            return null;
        }
        final Set<String> graphs = new LinkedHashSet<>();
        for (final Resource c : contexts) {
            graphs.add(c == null ? "" : RdfToRyaConversions.convertResource(c).getData());
        }
        return graphs;
    }

    @Override
    public ValueFactory getValueFactory() {
        return store.getValueFactory();
    }
}
