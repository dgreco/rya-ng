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

import java.util.List;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.junit.jupiter.api.Assertions;


public class PropertyChainIT extends RyaSailITBase {
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

    @Test
    public void testGraphConfiguration() throws Exception {
        // build a connection
        RdfCloudTripleStore store = new RdfCloudTripleStore();
        store.setConf(conf);
        store.setRyaDAO(ryaDAO);
        InferenceEngine inferenceEngine = new InferenceEngine();
        inferenceEngine.setRyaDAO(ryaDAO);
        store.setInferenceEngine(inferenceEngine);
        store.init();
        SailRepository repository = new SailRepository(store);
        SailRepositoryConnection conn = repository.getConnection();
        

        
    	String query = "INSERT DATA\n"//
    			+ "{ GRAPH <http://updated/test> {\n"//
    			+ "  <urn:greatMother> owl:propertyChainAxiom <urn:12342>  . " + 
    			" <urn:12342> <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> _:node1atjakcvbx15023 . " + 
    			" _:node1atjakcvbx15023 <http://www.w3.org/2002/07/owl#inverseOf> <urn:isChildOf> . " + 
    			" <urn:12342> <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> _:node1atjakcvbx15123 . " + 
       			" _:node1atjakcvbx15123 <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> <http://www.w3.org/1999/02/22-rdf-syntax-ns#nil> . " + 
    			" _:node1atjakcvbx15123 <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <urn:MotherOf> .  }}";
    	Update update = conn.prepareUpdate(QueryLanguage.SPARQL, query);
    	update.execute();
        inferenceEngine.refreshGraph();
       List<IRI> chain = inferenceEngine.getPropertyChain(VF.createIRI("urn:greatMother"));
       Assertions.assertEquals(chain.size(), 2);
       Assertions.assertEquals(chain.get(0), new InverseIRI(VF.createIRI("urn:isChildOf")));
       Assertions.assertEquals(chain.get(1), VF.createIRI("urn:MotherOf"));
 
    }
}
