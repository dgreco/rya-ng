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
package io.github.dgreco.rya.rdftriplestore.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import io.github.dgreco.rya.rdftriplestore.inference.DoNotExpandSP;
import io.github.dgreco.rya.rdftriplestore.inference.InferJoin;
import io.github.dgreco.rya.rdftriplestore.utils.FixedStatementPattern;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.BinaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.UnaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.FilterOptimizer;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.jupiter.api.Test;

/** Which joins and OPTIONALs form an entity chain, and over which driver. */
public class EntityChainTest {
    private static final String PERSON = "?s a <urn:Person> . ";

    @Test
    public void requiredAttributes() {
        final EntityChain chain = chain(PERSON + "?s <urn:name> ?n . ?s <urn:age> ?a");
        assertNotNull(chain);
        assertEquals(List.of("urn:name", "urn:age"), predicates(chain));
        assertEquals("urn:Person", ((StatementPattern) chain.driver()).getObjectVar().getValue().stringValue());
    }

    @Test
    public void oneRequiredAttributeIsLeftToTheBatchedJoin() {
        assertNull(chain(PERSON + "?s <urn:name> ?n"));
    }

    @Test
    public void oneOptionalAttribute() {
        final EntityChain chain = chain(PERSON + "OPTIONAL { ?s <urn:email> ?e }");
        assertNotNull(chain);
        assertEquals(List.of("urn:email"), predicates(chain));
        assertInstanceOf(LeftJoin.class, chain.links().get(0));
    }

    @Test
    public void mixedWithAConditionInsideOptional() {
        final EntityChain chain = chain(PERSON + "?s <urn:name> ?n OPTIONAL { ?s <urn:email> ?e } "
                + "OPTIONAL { ?s <urn:age> ?a FILTER(?a > 1) }");
        assertNotNull(chain);
        assertEquals(List.of("urn:name", "urn:email", "urn:age"), predicates(chain));
    }

    @Test
    public void aFilteredJoinStaysInTheDriver() {
        final EntityChain chain = chain(PERSON + "?s <urn:age> ?a FILTER(?a > 90) OPTIONAL { ?s <urn:email> ?e }");
        assertNotNull(chain);
        assertEquals(List.of("urn:email"), predicates(chain));
        final Join driver = assertInstanceOf(Join.class, chain.driver());
        assertInstanceOf(Filter.class, driver.getRightArg());
    }

    @Test
    public void aVariableBoundByALinkEndsTheChainBelowIt() {
        // ?x comes from the urn:p link, so urn:q's lookup isn't known from a driver row.
        assertNull(chain(PERSON + "?s <urn:p> ?x . ?s <urn:q> ?x"));
        final EntityChain chain = chain(PERSON + "?s <urn:p> ?x OPTIONAL { ?s <urn:q> ?x }");
        assertNotNull(chain);
        assertEquals(List.of("urn:q"), predicates(chain));
        assertInstanceOf(Join.class, chain.driver());
    }

    @Test
    public void attributesOfTwoEntitiesTheDriverBinds() {
        final EntityChain chain = chain(PERSON + "?s <urn:worksFor> ?c . ?c <urn:name> ?cn . ?s <urn:name> ?n");
        assertNotNull(chain);
        assertEquals(List.of("urn:name", "urn:name"), predicates(chain));
        assertEquals("c", chain.patterns().get(0).getSubjectVar().getName());
        assertEquals("s", chain.patterns().get(1).getSubjectVar().getName());
    }

    @Test
    public void aPatternOnTheObjectSide() {
        final EntityChain chain = chain("?c a <urn:Company> OPTIONAL { ?s <urn:worksFor> ?c } OPTIONAL { ?c <urn:city> ?y }");
        assertNotNull(chain);
        assertEquals(List.of("urn:worksFor", "urn:city"), predicates(chain));
    }

    @Test
    public void aPatternNotAnchoredOnTheDriver() {
        assertNull(chain(PERSON + "OPTIONAL { ?x <urn:other> ?y }"));
    }

    @Test
    public void aSubqueryIsNotALink() {
        assertNull(chain(PERSON + "OPTIONAL { SELECT ?s ?e WHERE { ?s <urn:email> ?e } }"));
    }

    @Test
    public void anInferredTypeBecomesTheDriver() {
        // ?s a :Animal under subClassOf inference: an in-memory pattern joined with a store pattern.
        final Var type = new Var("t");
        final FixedStatementPattern subclasses = new FixedStatementPattern(type.clone(),
                constant("c-sub", RDFS.SUBCLASSOF.stringValue()), constant("c-animal", "urn:Animal"));
        final InferJoin animals = new InferJoin(subclasses,
                new DoNotExpandSP(new Var("s"), constant("c-type", RDF.TYPE.stringValue()), type.clone()));
        final Join name = new Join(animals, pattern("s", "urn:name", "n"));
        final LeftJoin age = new LeftJoin(name, pattern("s", "urn:age", "a"));

        final EntityChain chain = EntityChain.find(age);
        assertNotNull(chain);
        assertSame(animals, chain.driver());
        assertEquals(List.of("urn:name", "urn:age"), predicates(chain));
    }

    // ------------------------------------------------------------------ helpers

    /** Parses the WHERE clause, pushes filters down as the optimizer pipeline does, and asks for the top node's chain. */
    private static EntityChain chain(final String where) {
        TupleExpr expr = new SPARQLParser().parseQuery("SELECT * WHERE { " + where + " }", null).getTupleExpr();
        new FilterOptimizer().optimize(expr, null, EmptyBindingSet.getInstance());
        while (expr instanceof UnaryTupleOperator unary) {
            expr = unary.getArg();
        }
        assertInstanceOf(BinaryTupleOperator.class, expr);
        return EntityChain.find(expr);
    }

    private static List<String> predicates(final EntityChain chain) {
        return chain.patterns().stream().map(sp -> sp.getPredicateVar().getValue().stringValue()).toList();
    }

    private static Var constant(final String name, final String iri) {
        return new Var(name, SimpleValueFactory.getInstance().createIRI(iri));
    }

    private static StatementPattern pattern(final String subject, final String predicate, final String object) {
        return new StatementPattern(new Var(subject), constant("c-" + predicate, predicate), new Var(object));
    }
}
