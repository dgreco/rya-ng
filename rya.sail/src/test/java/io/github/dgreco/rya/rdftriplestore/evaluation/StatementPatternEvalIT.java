package io.github.dgreco.rya.rdftriplestore.evaluation;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.StatementMetadata;
import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection.StoreTripleSource;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import io.github.dgreco.rya.RyaSailITBase;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet;
import org.eclipse.rdf4j.query.algebra.helpers.collectors.StatementPatternCollector;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StatementPatternEvalIT extends RyaSailITBase {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private AccumuloRyaDAO dao;
    private AccumuloRdfConfiguration conf;
    private RyaEvaluationStrategy eval;

    @BeforeEach
    public void init() throws Exception {
        conf = newConf();
        dao = newDao(conf);
        eval = new RyaEvaluationStrategy(new StoreTripleSource<>(conf, dao), null, null, new EvaluationStatistics());
    }

    @AfterEach
    public void close() throws RyaDAOException {
        dao.destroy();
    }

    /** Evaluates the pattern once per binding set and concatenates the results. */
    private CloseableIteration<BindingSet> evaluate(final StatementPattern sp, final List<? extends BindingSet> bindings) {
        final QueryEvaluationStep step = eval.precompile(sp);
        final List<BindingSet> out = new ArrayList<>();
        for (final BindingSet bs : bindings) {
            try (CloseableIteration<BindingSet> it = step.evaluate(bs)) {
                while (it.hasNext()) {
                    out.add(it.next());
                }
            }
        }
        return new CloseableIteratorIteration<>(out.iterator());
    }
    
    @Test
    public void simpleQueryWithoutBindingSets()
            throws MalformedQueryException, QueryEvaluationException, RyaDAOException {
        //query is used to build statement that will be evaluated
        String query = "select ?x ?c where{ graph ?c  {?x <uri:talksTo> <uri:Bob>. }}";
        SPARQLParser parser = new SPARQLParser();
        ParsedQuery pq = parser.parseQuery(query, null);
        List<StatementPattern> spList = StatementPatternCollector.process(pq.getTupleExpr());
        
        RyaStatement statement1 = new RyaStatement(new RyaIRI("uri:Joe"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context1"), "", new StatementMetadata());
        dao.add(statement1);
        
        RyaStatement statement2 = new RyaStatement(new RyaIRI("uri:Doug"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context2"), "", new StatementMetadata());
        dao.add(statement2);
        
        RyaStatement statement3 = new RyaStatement(new RyaIRI("uri:Eric"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context3"), "", new StatementMetadata());
        dao.add(statement3);

        QueryBindingSet bsConstraint1 = new QueryBindingSet();
        
        CloseableIteration<BindingSet> iteration = evaluate(spList.get(0), Arrays.<BindingSet>asList(bsConstraint1));

        List<BindingSet> bsList = new ArrayList<>();
        while (iteration.hasNext()) {
            bsList.add(iteration.next());
        }

        Assertions.assertEquals(3, bsList.size());
        
        QueryBindingSet expected1 = new QueryBindingSet();
        expected1.addBinding("x", VF.createIRI("uri:Joe"));
        expected1.addBinding("c", VF.createIRI("uri:context1"));

        QueryBindingSet expected2 = new QueryBindingSet();
        expected2.addBinding("x", VF.createIRI("uri:Doug"));
        expected2.addBinding("c", VF.createIRI("uri:context2"));
        
        QueryBindingSet expected3 = new QueryBindingSet();
        expected3.addBinding("x", VF.createIRI("uri:Eric"));
        expected3.addBinding("c", VF.createIRI("uri:context3"));
        
        Set<BindingSet> expected = new HashSet<>(Arrays.asList(expected1, expected2, expected3));
        Set<BindingSet> actual = new HashSet<>(bsList);
        
        Assertions.assertEquals(expected, actual);
        dao.delete(Arrays.asList(statement1, statement2, statement3).iterator(), conf);
    }

    @Test
    public void simpleQueryWithBindingSets()
            throws MalformedQueryException, QueryEvaluationException, RyaDAOException {
        //query is used to build statement that will be evaluated
        String query = "select ?x ?c where{ graph ?c  {?x <uri:talksTo> <uri:Bob>. }}";
        SPARQLParser parser = new SPARQLParser();
        ParsedQuery pq = parser.parseQuery(query, null);
        List<StatementPattern> spList = StatementPatternCollector.process(pq.getTupleExpr());
        
        RyaStatement statement1 = new RyaStatement(new RyaIRI("uri:Joe"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context1"), "", new StatementMetadata());
        dao.add(statement1);
        
        RyaStatement statement2 = new RyaStatement(new RyaIRI("uri:Doug"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context2"), "", new StatementMetadata());
        dao.add(statement2);
        
        RyaStatement statement3 = new RyaStatement(new RyaIRI("uri:Eric"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context3"), "", new StatementMetadata());
        dao.add(statement3);

        QueryBindingSet bsConstraint1 = new QueryBindingSet();
        bsConstraint1.addBinding("c", VF.createIRI("uri:context2"));
        
        QueryBindingSet bsConstraint2 = new QueryBindingSet();
        bsConstraint2.addBinding("c", VF.createIRI("uri:context1"));

        
        CloseableIteration<BindingSet> iteration = evaluate(spList.get(0), Arrays.<BindingSet>asList(bsConstraint1, bsConstraint2));

        List<BindingSet> bsList = new ArrayList<>();
        while (iteration.hasNext()) {
            bsList.add(iteration.next());
        }

        Assertions.assertEquals(2, bsList.size());
        
        QueryBindingSet expected1 = new QueryBindingSet();
        expected1.addBinding("x", VF.createIRI("uri:Joe"));
        expected1.addBinding("c", VF.createIRI("uri:context1"));

        QueryBindingSet expected2 = new QueryBindingSet();
        expected2.addBinding("x", VF.createIRI("uri:Doug"));
        expected2.addBinding("c", VF.createIRI("uri:context2"));
        
        Set<BindingSet> expected = new HashSet<>(Arrays.asList(expected1, expected2));
        Set<BindingSet> actual = new HashSet<>(bsList);
        
        Assertions.assertEquals(expected, actual);
        
        dao.delete(Arrays.asList(statement1, statement2, statement3).iterator(), conf);
    }

    
    @Test
    public void simpleQueryWithBindingSetSameContext()
            throws MalformedQueryException, QueryEvaluationException, RyaDAOException {
        //query is used to build statement that will be evaluated
        String query = "select ?x ?c where{ graph ?c  {?x <uri:talksTo> <uri:Bob>. }}";
        SPARQLParser parser = new SPARQLParser();
        ParsedQuery pq = parser.parseQuery(query, null);
        List<StatementPattern> spList = StatementPatternCollector.process(pq.getTupleExpr());
        
        RyaStatement statement1 = new RyaStatement(new RyaIRI("uri:Joe"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context1"), "", new StatementMetadata());
        dao.add(statement1);
        
        RyaStatement statement2 = new RyaStatement(new RyaIRI("uri:Doug"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context2"), "", new StatementMetadata());
        dao.add(statement2);
        
        RyaStatement statement3 = new RyaStatement(new RyaIRI("uri:Eric"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context3"), "", new StatementMetadata());
        dao.add(statement3);

        QueryBindingSet bsConstraint1 = new QueryBindingSet();
        bsConstraint1.addBinding("c", VF.createIRI("uri:context1"));
        
        QueryBindingSet bsConstraint2 = new QueryBindingSet();
        bsConstraint2.addBinding("c", VF.createIRI("uri:context1"));

        
        CloseableIteration<BindingSet> iteration = evaluate(spList.get(0), Arrays.<BindingSet>asList(bsConstraint1, bsConstraint2));

        List<BindingSet> bsList = new ArrayList<>();
        while (iteration.hasNext()) {
            bsList.add(iteration.next());
        }

        // Two identical input bindings give two identical rows (SPARQL bag semantics).
        Assertions.assertEquals(2, bsList.size());
        
        QueryBindingSet expected = new QueryBindingSet();
        expected.addBinding("x", VF.createIRI("uri:Joe"));
        expected.addBinding("c", VF.createIRI("uri:context1"));
        
        Assertions.assertEquals(expected, bsList.get(0));
        Assertions.assertEquals(expected, bsList.get(1));

        dao.delete(Arrays.asList(statement1, statement2, statement3).iterator(), conf);
    }
    
    @Test
    public void simpleQueryNoBindingSetConstantContext()
            throws MalformedQueryException, QueryEvaluationException, RyaDAOException {
        //query is used to build statement that will be evaluated
        String query = "select ?x ?c where{ graph <uri:context1>  {?x <uri:talksTo> <uri:Bob>. }}";
        SPARQLParser parser = new SPARQLParser();
        ParsedQuery pq = parser.parseQuery(query, null);
        List<StatementPattern> spList = StatementPatternCollector.process(pq.getTupleExpr());
        
        RyaStatement statement1 = new RyaStatement(new RyaIRI("uri:Joe"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context1"), "", new StatementMetadata());
        dao.add(statement1);
        
        RyaStatement statement2 = new RyaStatement(new RyaIRI("uri:Doug"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context2"), "", new StatementMetadata());
        dao.add(statement2);
        
        RyaStatement statement3 = new RyaStatement(new RyaIRI("uri:Eric"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context3"), "", new StatementMetadata());
        dao.add(statement3);

        QueryBindingSet bsConstraint1 = new QueryBindingSet();
        
        CloseableIteration<BindingSet> iteration = evaluate(spList.get(0), Arrays.<BindingSet>asList(bsConstraint1));

        List<BindingSet> bsList = new ArrayList<>();
        while (iteration.hasNext()) {
            bsList.add(iteration.next());
        }
        
        Assertions.assertEquals(1, bsList.size());
       
        QueryBindingSet expected = new QueryBindingSet();
        expected.addBinding("x", VF.createIRI("uri:Joe"));
        
        Assertions.assertEquals(expected, bsList.get(0));
        
        dao.delete(Arrays.asList(statement1, statement2, statement3).iterator(), conf);
    }

    @Test
    public void simpleQueryWithBindingSetConstantContext()
            throws MalformedQueryException, QueryEvaluationException, RyaDAOException {
        //query is used to build statement that will be evaluated
        String query = "select ?x ?c where{ graph <uri:context1>  {?x <uri:talksTo> <uri:Bob>. }}";
        SPARQLParser parser = new SPARQLParser();
        ParsedQuery pq = parser.parseQuery(query, null);
        List<StatementPattern> spList = StatementPatternCollector.process(pq.getTupleExpr());
        
        RyaStatement statement1 = new RyaStatement(new RyaIRI("uri:Joe"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context1"), "", new StatementMetadata());
        dao.add(statement1);
        
        RyaStatement statement2 = new RyaStatement(new RyaIRI("uri:Doug"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context1"), "", new StatementMetadata());
        dao.add(statement2);
        
        RyaStatement statement3 = new RyaStatement(new RyaIRI("uri:Doug"), new RyaIRI("uri:talksTo"),
                new RyaIRI("uri:Bob"), new RyaIRI("uri:context2"), "", new StatementMetadata());
        dao.add(statement3);

        QueryBindingSet bsConstraint1 = new QueryBindingSet();
        bsConstraint1.addBinding("x", VF.createIRI("uri:Doug"));
        
        CloseableIteration<BindingSet> iteration = evaluate(spList.get(0), Arrays.<BindingSet>asList(bsConstraint1));

        List<BindingSet> bsList = new ArrayList<>();
        while (iteration.hasNext()) {
            bsList.add(iteration.next());
        }

        Assertions.assertEquals(1, bsList.size());
        
        QueryBindingSet expected = new QueryBindingSet();
        expected.addBinding("x", VF.createIRI("uri:Doug"));

        Assertions.assertEquals(expected, bsList.get(0));
        
        dao.delete(Arrays.asList(statement1, statement2, statement3).iterator(), conf);
    }

    


}

