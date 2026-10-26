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
package io.github.dgreco.rya;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.GregorianCalendar;
import java.util.List;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResultHandlerException;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResultHandler;
import org.eclipse.rdf4j.query.TupleQueryResultHandlerException;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;


/**
 * Class PartitionConnectionTest
 * Date: Jul 6, 2011
 * Time: 5:24:07 PM
 */
public class RdfCloudTripleStoreIT extends RyaSailITBase {
    public static final String NAMESPACE = "http://here/2010/tracked-data-provenance/ns#";//44 len
    public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String HBNAMESPACE = "http://here/2010/tracked-data-provenance/heartbeat/ns#";
    public static final String HB_TIMESTAMP = HBNAMESPACE + "timestamp";

    private SailRepository repository;
    private SailRepositoryConnection connection;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private String objectUuid = "objectuuid1";
    private String ancestor = "ancestor1";
    private String descendant = "descendant1";
    private static final long START = 1309532965000l;
    private static final long END = 1310566686000l;

    @BeforeEach
    public void setUp() throws Exception {

        RdfCloudTripleStore sail = new RdfCloudTripleStore();
        AccumuloRdfConfiguration conf = newConf();
        sail.setConf(conf);
        AccumuloRyaDAO crdfdao = new AccumuloRyaDAO(getClient(), null);
        crdfdao.setConf(conf);
        sail.setRyaDAO(crdfdao);

        repository = new SailRepository(sail);
        repository.init();
        connection = repository.getConnection();

        loadData();
    }

    private void loadData() throws RepositoryException, DatatypeConfigurationException {
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, objectUuid), VF.createIRI(NAMESPACE, "name"), VF.createLiteral("objUuid")));
        //created
        String uuid = "uuid1";
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(NAMESPACE, "Created")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "createdItem"), VF.createIRI(NAMESPACE, objectUuid)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedBy"), VF.createIRI("urn:system:A")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "stringLit"), VF.createLiteral("stringLit1")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "stringLit"), VF.createLiteral("stringLit2")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "stringLit"), VF.createLiteral("stringLit3")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "stringLit"), VF.createLiteral("stringLit4")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "strLit1"), VF.createLiteral("strLit1")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "strLit1"), VF.createLiteral("strLit2")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "strLit1"), VF.createLiteral("strLit3")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 0, 0, 0, 0))));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "reportedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 1, 0, 0, 0))));
        //clicked
        uuid = "uuid2";
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(NAMESPACE, "Clicked")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "clickedItem"), VF.createIRI(NAMESPACE, objectUuid)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedBy"), VF.createIRI("urn:system:B")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 2, 0, 0, 0))));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "reportedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 3, 0, 0, 0))));
        //deleted
        uuid = "uuid3";
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(NAMESPACE, "Deleted")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "deletedItem"), VF.createIRI(NAMESPACE, objectUuid)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedBy"), VF.createIRI("urn:system:C")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 4, 0, 0, 0))));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "reportedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 5, 0, 0, 0))));
        //dropped
        uuid = "uuid4";
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(NAMESPACE, "Dropped")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "droppedItem"), VF.createIRI(NAMESPACE, objectUuid)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedBy"), VF.createIRI("urn:system:D")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 6, 0, 0, 0))));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "reportedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 7, 0, 0, 0))));
        //received
        uuid = "uuid5";
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(NAMESPACE, "Received")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "receivedItem"), VF.createIRI(NAMESPACE, objectUuid)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedBy"), VF.createIRI("urn:system:E")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 8, 0, 0, 0))));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "reportedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 9, 0, 0, 0))));
        //sent
        uuid = "uuid6";
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(NAMESPACE, "Sent")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "sentItem"), VF.createIRI(NAMESPACE, objectUuid)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedBy"), VF.createIRI("urn:system:F")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 10, 0, 0, 0))));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "reportedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 11, 0, 0, 0))));
        //stored
        uuid = "uuid7";
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(NAMESPACE, "Stored")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "storedItem"), VF.createIRI(NAMESPACE, objectUuid)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedBy"), VF.createIRI("urn:system:G")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "performedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 12, 0, 0, 0))));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, uuid), VF.createIRI(NAMESPACE, "reportedAt"), VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(2011, 7, 12, 6, 13, 0, 0, 0))));

        //derivedFrom
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, descendant), VF.createIRI(NAMESPACE, "derivedFrom"), VF.createIRI(NAMESPACE, ancestor)));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, descendant), VF.createIRI(NAMESPACE, "name"), VF.createLiteral("descendantOne")));
        connection.add(VF.createStatement(VF.createIRI(NAMESPACE, ancestor), VF.createIRI(NAMESPACE, "name"), VF.createLiteral("ancestor1")));

        //heartbeats
        String hbuuid = "hbuuid1";
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(HBNAMESPACE, "HeartbeatMeasurement")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HB_TIMESTAMP), VF.createLiteral((START + 1) + "")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HBNAMESPACE, "count"), VF.createLiteral(1 + "")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HBNAMESPACE, "systemName"), VF.createIRI("urn:system:A")));
        connection.add(VF.createStatement(VF.createIRI("urn:system:A"), VF.createIRI(HBNAMESPACE, "heartbeat"), VF.createIRI(HBNAMESPACE, hbuuid)));

        hbuuid = "hbuuid2";
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(HBNAMESPACE, "HeartbeatMeasurement")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HB_TIMESTAMP), VF.createLiteral((START + 2) + "")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HBNAMESPACE, "count"), VF.createLiteral(2 + "")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HBNAMESPACE, "systemName"), VF.createIRI("urn:system:B")));
        connection.add(VF.createStatement(VF.createIRI("urn:system:B"), VF.createIRI(HBNAMESPACE, "heartbeat"), VF.createIRI(HBNAMESPACE, hbuuid)));

        hbuuid = "hbuuid3";
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(RDF_NS, "type"), VF.createIRI(HBNAMESPACE, "HeartbeatMeasurement")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HB_TIMESTAMP), VF.createLiteral((START + 3) + "")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HBNAMESPACE, "count"), VF.createLiteral(3 + "")));
        connection.add(VF.createStatement(VF.createIRI(HBNAMESPACE, hbuuid), VF.createIRI(HBNAMESPACE, "systemName"), VF.createIRI("urn:system:C")));
        connection.add(VF.createStatement(VF.createIRI("urn:system:C"), VF.createIRI(HBNAMESPACE, "heartbeat"), VF.createIRI(HBNAMESPACE, hbuuid)));

        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral("obj1")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral("obj2")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral("obj3")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral("obj4")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral("obj1")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral("obj2")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral("obj3")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral("obj4")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj3"), VF.createIRI("urn:pred"), VF.createLiteral("obj1")));
        connection.add(VF.createStatement(VF.createIRI("urn:subj3"), VF.createIRI("urn:pred"), VF.createLiteral("obj4")));

        //Foreign Chars
        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_CH_SIM)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_CH_TRAD)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_TH)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj1"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_RN)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_CH_SIM)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_CH_TRAD)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_TH)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj2"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_RN)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj3"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_CH_SIM)));
        connection.add(VF.createStatement(VF.createIRI("urn:subj3"), VF.createIRI("urn:pred"), VF.createLiteral(FAN_CH_TRAD)));
        
        connection.commit();
    }

    private static final String FAN_CH_SIM = "风扇";
    private static final String FAN_CH_TRAD = "風扇";
    private static final String FAN_TH = "แฟน";
    private static final String FAN_RN = "вентилятор";
    
    @AfterEach
    public void tearDown() throws Exception {
        connection.close();
        repository.shutDown();
    }

    protected String getXmlDate(long ts) throws DatatypeConfigurationException {
        GregorianCalendar gregorianCalendar = new GregorianCalendar();
        gregorianCalendar.setTimeInMillis(ts);
        //"2011-07-12T05:12:00.000Z"^^xsd:dateTime
        return "\"" + VF.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(gregorianCalendar)).stringValue() + "\"^^xsd:dateTime";
    }

//    public void testScanAll() throws Exception {
//        Scanner sc = connector.createScanner("lubm_spo", Constants.NO_AUTHS);
//        for (Map.Entry<Key, Value> aSc : sc) System.out.println(aSc.getKey().getRow());
//    }

    @Test
    public void testNamespace() throws Exception {
        String namespace = "urn:testNamespace#";
        String prefix = "pfx";
        connection.setNamespace(prefix, namespace);

        assertEquals(namespace, connection.getNamespace(prefix));
    }

    @Test
    public void testValues() throws Exception {
      String query = "SELECT DISTINCT ?entity WHERE {"
              + "VALUES (?entity) { (<http://test/entity>) }" 
              + "}";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }
    
    @Test
    public void testGetNamespaces() throws Exception {
        String namespace = "urn:testNamespace#";
        String prefix = "pfx";
        connection.setNamespace(prefix, namespace);

        namespace = "urn:testNamespace2#";
        prefix = "pfx2";
        connection.setNamespace(prefix, namespace);

        RepositoryResult<Namespace> result = connection.getNamespaces();
        int count = 0;
        while (result.hasNext()) {
            result.next();
            count++;
        }

        assertEquals(2, count);
    }

    @Test
    public void testAddCommitStatement() throws Exception {
        Statement stmt = VF.createStatement(VF.createIRI("urn:namespace#subj"), VF.createIRI("urn:namespace#pred"), VF.createLiteral("object"));
        connection.add(stmt);
        connection.commit();
    }

    @Test
    public void testSelectOnlyQuery() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "ns:uuid1 ns:createdItem ?cr.\n" +
                "ns:uuid1 ns:reportedAt ?ra.\n" +
                "ns:uuid1 ns:performedAt ?pa.\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testForeignSelectOnlyQuery() throws Exception {
        String query;
        query = "select * where { ?s <urn:pred> ?o }"; // hits po
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(20, tupleHandler.getCount());

        query = "select * where { <urn:subj1> <urn:pred> ?o }"; //hits spo
        tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(8, tupleHandler.getCount());

        query = "select * where { ?s ?p '"+FAN_CH_SIM+"' }"; //hits osp
        tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(3, tupleHandler.getCount());
}
      


    //provenance Queries//////////////////////////////////////////////////////////////////////

    @Test
    public void testEventInfo() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "   ns:uuid1 ?p ?o.\n" +
                "}\n";

        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//                tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(12, tupleHandler.getCount());
    }

    @Test
    public void testAllAncestors() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "ns:" + descendant + " ns:derivedFrom ?dr.\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        //        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testAllDescendants() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "?ds ns:derivedFrom ns:" + ancestor + ".\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testEventsForUri() throws Exception {
        String query = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "PREFIX ns:<" + NAMESPACE + ">\n" +
                "PREFIX org.apachepart: <urn:org.apache.mmrts.partition.rdf/08/2011#>\n" +
                "PREFIX org.apache: <" + RdfCloudTripleStoreConstants.NAMESPACE + ">\n" +
                "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n" +
                "select * where {\n" +
                "{" +
                "   ?s rdf:type ns:Created.\n" +
                "   ?s ns:createdItem ns:objectuuid1.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Clicked.\n" +
                "   ?s ns:clickedItem ns:objectuuid1.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Deleted.\n" +
                "   ?s ns:deletedItem ns:objectuuid1.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Dropped.\n" +
                "   ?s ns:droppedItem ns:objectuuid1.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Received.\n" +
                "   ?s ns:receivedItem ns:objectuuid1.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Stored.\n" +
                "   ?s ns:storedItem ns:objectuuid1.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Sent.\n" +
                "   ?s ns:sentItem ns:objectuuid1.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.setBinding(START_BINDING, vf.createLiteral(START));
//        tupleQuery.setBinding(END_BINDING, vf.createLiteral(END));
//        tupleQuery.setBinding(TIME_PREDICATE, vf.createIRI(NAMESPACE, "performedAt"));
//                tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(7, tupleHandler.getCount());
    }

    @Test
    public void testAllEvents() throws Exception {
        String query = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "PREFIX ns:<" + NAMESPACE + ">\n" +
                "PREFIX org.apachepart: <urn:org.apache.mmrts.partition.rdf/08/2011#>\n" +
                "PREFIX org.apache: <" + RdfCloudTripleStoreConstants.NAMESPACE + ">\n" +
                "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n" +
                "select * where {\n" +
                "{" +
                "   ?s rdf:type ns:Created.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Clicked.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Deleted.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Dropped.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Received.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Stored.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "UNION {" +
                "   ?s rdf:type ns:Sent.\n" +
                "   ?s ns:performedBy ?pb.\n" +
                "   ?s ns:performedAt ?pa.\n" +
                "   FILTER(?pa >= " + getXmlDate(START) + " && ?pa <= " + getXmlDate(END) + ")\n" +
                "}\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.setBinding(START_BINDING, vf.createLiteral(START));
//        tupleQuery.setBinding(END_BINDING, vf.createLiteral(END));
//        tupleQuery.setBinding(TIME_PREDICATE, vf.createIRI(NAMESPACE, "performedAt"));
//                tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(7, tupleHandler.getCount());
//        System.out.println(tupleHandler.getCount());
    }

    @Test
    public void testEventsBtwnSystems() throws Exception {  //TODO: How to do XMLDateTime ranges
        String query = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "PREFIX ns:<" + NAMESPACE + ">\n" +
                "PREFIX org.apachepart: <urn:org.apache.mmrts.partition.rdf/08/2011#>\n" +
                "PREFIX org.apache: <" + RdfCloudTripleStoreConstants.NAMESPACE + ">\n" +
                "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n" +
                "select * where {\n" +
                "   ?sendEvent rdf:type ns:Sent;\n" +
                "              ns:sentItem ?objUuid;\n" +
                "              ns:performedBy <urn:system:F>;\n" +
                "              ns:performedAt ?spa.\n" +
                "   ?recEvent rdf:type ns:Received;\n" +
                "              ns:receivedItem ?objUuid;\n" +
                "              ns:performedBy <urn:system:E>;\n" +
                "              ns:performedAt ?rpa.\n" +
//                "   FILTER(org.apache:range(?spa, \"2011-07-12T05:12:00.000Z\"^^xsd:dateTime, \"2011-07-12T07:12:00.000Z\"^^xsd:dateTime))\n" +
                "   FILTER(?spa >= " + getXmlDate(START) + " && ?spa <= " + getXmlDate(END) + ")\n" +
                "   FILTER(?rpa >= " + getXmlDate(START) + " && ?rpa <= " + getXmlDate(END) + ")\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.setBinding(START_BINDING, vf.createLiteral(START));
//        tupleQuery.setBinding(END_BINDING, vf.createLiteral(END));
//        tupleQuery.setBinding(TIME_PREDICATE, vf.createIRI(NAMESPACE, "performedAt"));
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testHeartbeatCounts() throws Exception {
        String query = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "PREFIX hns:<" + HBNAMESPACE + ">\n" +
                "PREFIX org.apachepart: <urn:org.apache.mmrts.partition.rdf/08/2011#>\n" +
                "PREFIX org.apache: <" + RdfCloudTripleStoreConstants.NAMESPACE + ">\n" +
                "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n" +
                "select * where {\n" +
                "   ?hb rdf:type hns:HeartbeatMeasurement;\n" +
                "              hns:count ?count;\n" +
                "              hns:timestamp ?ts;\n" +
                "              hns:systemName ?systemName.\n" +
                "   FILTER(?ts >= \"" + START + "\" && ?ts < \"" + (START + 3) + "\")\n" +
                "}\n";
//        System.out.println(query);
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.setBinding(RdfCloudTripleStoreConfiguration.CONF_QUERYPLAN_FLAG, vf.createLiteral(true));
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(2, tupleHandler.getCount());
    }

    //provenance Queries//////////////////////////////////////////////////////////////////////

    @Test
    public void testCreatedEvents() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "   ?s ns:createdItem ns:objectuuid1.\n" +
                "   ?s ns:reportedAt ?ra.\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testSelectAllAfterFilter() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "   ?s ns:createdItem ns:objectuuid1.\n" +
                "   ?s ?p ?o.\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(12, tupleHandler.getCount());
    }

    @Test
    public void testFilterQuery() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "ns:uuid1 ns:createdItem ?cr.\n" +
                "ns:uuid1 ns:stringLit ?sl.\n" +
                "FILTER regex(?sl, \"stringLit1\")" +
                "ns:uuid1 ns:reportedAt ?ra.\n" +
                "ns:uuid1 ns:performedAt ?pa.\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
        //        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testMultiplePredicatesMultipleBindingSets() throws Exception {
        //MMRTS-121
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "select * where {\n" +
                "?id ns:createdItem ns:objectuuid1.\n" +
                "?id ns:stringLit ?sl.\n" +
                "?id ns:strLit1 ?s2.\n" +
                "}\n";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(12, tupleHandler.getCount());
    }

    @Test
    public void testMultiShardLookupTimeRange() throws Exception {
        //MMRTS-113
        String query = "PREFIX hb: <http://here/2010/tracked-data-provenance/heartbeat/ns#>\n" +
                "PREFIX org.apachepart: <urn:org.apache.mmrts.partition.rdf/08/2011#>\n" +
                "SELECT * WHERE\n" +
                "{\n" +
                "?id hb:timestamp ?timestamp.\n" +
//                "FILTER(org.apachepart:timeRange(?id, hb:timestamp, " + START + " , " + (START + 2) + " , 'TIMESTAMP'))\n" +
                "?id hb:count ?count.\n" +
                "?system hb:heartbeat ?id.\n" +
                "}";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(3, tupleHandler.getCount());
    }

    @Test
    public void testMultiShardLookupTimeRangeValueConst() throws Exception {
        //MMRTS-113
        String query = "PREFIX hb: <http://here/2010/tracked-data-provenance/heartbeat/ns#>\n" +
                "PREFIX org.apachepart: <urn:org.apache.mmrts.partition.rdf/08/2011#>\n" +
                "SELECT * WHERE\n" +
                "{\n" +
                "<http://here/2010/tracked-data-provenance/heartbeat/ns#hbuuid2> hb:timestamp ?timestamp.\n" +
//                "FILTER(org.apachepart:timeRange(<http://here/2010/tracked-data-provenance/heartbeat/ns#hbuuid2>, hb:timestamp, " + START + " , " + END + " , 'TIMESTAMP'))\n" +
                "<http://here/2010/tracked-data-provenance/heartbeat/ns#hbuuid2> hb:count ?count.\n" +
                "?system hb:heartbeat <http://here/2010/tracked-data-provenance/heartbeat/ns#hbuuid2>.\n" +
                "}";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testLinkQuery() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "SELECT * WHERE {\n" +
                "     <http://here/2010/tracked-data-provenance/ns#uuid1> ns:createdItem ?o .\n" +
                "     ?o ns:name ?n .\n" +
                "}";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(1, tupleHandler.getCount());
    }

    @Test
    public void testRangeOverDuplicateItems() throws Exception {
        String query = "PREFIX ns:<" + NAMESPACE + ">\n" +
                "SELECT * WHERE {\n" +
                "     ?subj <urn:pred> \"obj2\" .\n" +
                "}";
        TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
//        tupleQuery.evaluate(new PrintTupleHandler());
        CountTupleHandler tupleHandler = new CountTupleHandler();
        tupleQuery.evaluate(tupleHandler);
        assertEquals(2, tupleHandler.getCount());
    }

    private static class PrintTupleHandler implements TupleQueryResultHandler {

        @Override
        public void startQueryResult(List<String> strings) throws TupleQueryResultHandlerException {
        }

        @Override
        public void endQueryResult() throws TupleQueryResultHandlerException {

        }

        @Override
        public void handleSolution(BindingSet bindingSet) throws TupleQueryResultHandlerException {
            System.out.println(bindingSet);
        }

        @Override
        public void handleBoolean(boolean paramBoolean) throws QueryResultHandlerException {
        }

        @Override
        public void handleLinks(List<String> paramList) throws QueryResultHandlerException {
        }
    }

    private static class CountTupleHandler implements TupleQueryResultHandler {

        int count = 0;

        @Override
        public void startQueryResult(List<String> strings) throws TupleQueryResultHandlerException {
        }

        @Override
        public void endQueryResult() throws TupleQueryResultHandlerException {
        }

        @Override
        public void handleSolution(BindingSet bindingSet) throws TupleQueryResultHandlerException {
            count++;
        }

        public int getCount() {
            return count;
        }

        @Override
        public void handleBoolean(boolean paramBoolean) throws QueryResultHandlerException {
        }

        @Override
        public void handleLinks(List<String> paramList) throws QueryResultHandlerException {
        }
    }

}
