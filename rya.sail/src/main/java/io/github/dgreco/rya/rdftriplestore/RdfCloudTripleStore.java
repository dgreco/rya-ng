package io.github.dgreco.rya.rdftriplestore;

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

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO;
import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.rdftriplestore.inference.InferenceEngine;
import io.github.dgreco.rya.rdftriplestore.namespace.NamespaceManager;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.helpers.AbstractSail;

/**
 * RDF4J {@link org.eclipse.rdf4j.sail.Sail} over a {@link RyaDAO}.
 *
 * <p>Set the DAO (required), and optionally the eval-stats DAO (join ordering) and the
 * {@link InferenceEngine} (query-time OWL/RDFS inference) before initializing. Shutting the
 * sail down shuts them down too. See {@link RyaAccumuloSailFactory} for a ready-made setup.
 */
public class RdfCloudTripleStore<C extends RdfCloudTripleStoreConfiguration> extends AbstractSail {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private C conf;
    private RyaDAO<C> ryaDAO;
    private InferenceEngine inferenceEngine;
    private RdfEvalStatsDAO<C> rdfEvalStatsDAO;
    private NamespaceManager namespaceManager;

    @Override
    protected SailConnection getConnectionInternal() throws SailException {
        return new RdfCloudTripleStoreConnection<>(this, conf);
    }

    @Override
    protected void initializeInternal() throws SailException {
        checkNotNull(ryaDAO, "ryaDAO");
        if (conf == null) {
            conf = ryaDAO.getConf();
        }
        checkNotNull(conf, "conf");
        try {
            if (!ryaDAO.isInitialized()) {
                ryaDAO.setConf(conf);
                ryaDAO.init();
            }
            if (rdfEvalStatsDAO != null && !rdfEvalStatsDAO.isInitialized()) {
                rdfEvalStatsDAO.setConf(conf);
                rdfEvalStatsDAO.init();
            }
            if (inferenceEngine != null && !inferenceEngine.isInitialized()) {
                inferenceEngine.setRyaDAO(ryaDAO);
                inferenceEngine.setConf(conf);
                inferenceEngine.init();
            }
        } catch (final Exception e) {
            throw new SailException(e);
        }
        if (namespaceManager == null) {
            namespaceManager = new NamespaceManager(ryaDAO);
        }
    }

    @Override
    protected void shutDownInternal() throws SailException {
        try {
            if (namespaceManager != null) {
                namespaceManager.shutdown();
            }
            if (inferenceEngine != null) {
                inferenceEngine.destroy();
            }
            if (rdfEvalStatsDAO != null) {
                rdfEvalStatsDAO.destroy();
            }
            ryaDAO.destroy();
        } catch (final Exception e) {
            throw new SailException(e);
        }
    }

    @Override
    public ValueFactory getValueFactory() {
        return VF;
    }

    @Override
    public boolean isWritable() throws SailException {
        return true;
    }

    public synchronized C getConf() {
        return conf;
    }

    public synchronized void setConf(final C conf) {
        this.conf = conf;
    }

    public RdfEvalStatsDAO<C> getRdfEvalStatsDAO() {
        return rdfEvalStatsDAO;
    }

    public void setRdfEvalStatsDAO(final RdfEvalStatsDAO<C> rdfEvalStatsDAO) {
        this.rdfEvalStatsDAO = rdfEvalStatsDAO;
    }

    public synchronized RyaDAO<C> getRyaDAO() {
        return ryaDAO;
    }

    public synchronized void setRyaDAO(final RyaDAO<C> ryaDAO) {
        this.ryaDAO = ryaDAO;
    }

    public synchronized InferenceEngine getInferenceEngine() {
        return inferenceEngine;
    }

    public synchronized void setInferenceEngine(final InferenceEngine inferenceEngine) {
        this.inferenceEngine = inferenceEngine;
    }

    public NamespaceManager getNamespaceManager() {
        return namespaceManager;
    }

    public void setNamespaceManager(final NamespaceManager namespaceManager) {
        this.namespaceManager = namespaceManager;
    }
}
