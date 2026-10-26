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
package io.github.dgreco.rya.rdftriplestore.inference;

import io.github.dgreco.rya.RyaSailITBase;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;
import io.github.dgreco.rya.api.resolver.RdfToRyaConversions;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;


public class SameAsIT extends RyaSailITBase {
    private AccumuloRyaDAO ryaDAO;
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private String namespace = "urn:test#";
    private AccumuloRdfConfiguration conf;

    @BeforeEach
    public void setUp() throws Exception {
        conf = newConf();
        ryaDAO = new AccumuloRyaDAO(getClient(), null);
        ryaDAO.setConf(conf);
        ryaDAO.init();
    }

    @AfterEach
    public void tearDown() throws Exception {
        ryaDAO.destroy();
    }

        //This isn't a good test.  It's simply a cut-and-paste from a test that was failing in a different package in the SameAsVisitor.
    @Test
    public void testGraphConfiguration() throws Exception {
        IRI a = VF.createIRI(namespace, "a");
        Statement statement = VF.createStatement(a, VF.createIRI(namespace, "p"), VF.createLiteral("l"));
        Statement statement2 = VF.createStatement(a, VF.createIRI(namespace, "p2"), VF.createLiteral("l"));
        ryaDAO.add(RdfToRyaConversions.convertStatement(statement));
        ryaDAO.add(RdfToRyaConversions.convertStatement(statement2));
        ryaDAO.add(RdfToRyaConversions.convertStatement(VF.createStatement(VF.createIRI(namespace, "b"), VF.createIRI(namespace, "p"), VF.createLiteral("l"))));
        ryaDAO.add(RdfToRyaConversions.convertStatement(VF.createStatement(VF.createIRI(namespace, "c"), VF.createIRI(namespace, "n"), VF.createLiteral("l"))));

        // build a connection
        RdfCloudTripleStore store = new RdfCloudTripleStore();
        store.setConf(conf);
        store.setRyaDAO(ryaDAO);

        InferenceEngine inferenceEngine = new InferenceEngine();
        inferenceEngine.setRyaDAO(ryaDAO);
        store.setInferenceEngine(inferenceEngine);
        
        store.init();

        System.out.println(Iterations.asList(store.getConnection().getStatements(a, VF.createIRI(namespace, "p"), VF.createLiteral("l"), false, new Resource[0])).size());
    }
}
