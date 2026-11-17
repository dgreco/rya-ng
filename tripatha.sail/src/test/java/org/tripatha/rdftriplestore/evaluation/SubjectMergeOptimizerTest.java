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
package org.tripatha.rdftriplestore.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.Compare;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.junit.jupiter.api.Test;
import org.tripatha.api.persist.RdfEvalStatsDAO;
import org.tripatha.store.StoreRdfConfiguration;

/**
 * Which stars {@link SubjectMergeOptimizer} merges, and what it leaves: the list lengths come
 * from stub {@code po} counters.
 */
public class SubjectMergeOptimizerTest {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI P = VF.createIRI("urn:p");
    private static final IRI A = VF.createIRI("urn:A");
    private static final IRI B = VF.createIRI("urn:B");
    private static final IRI C = VF.createIRI("urn:C");
    /** The length of {@code ?x <urn:p> o} for each object o. */
    private static final Map<Value, Double> LENGTHS = Map.of(A, 10.0, B, 30.0, C, 1000.0);

    @Test
    public void listsWithinTheRatioAreMergedAndTheLongOneIsLookedUpAfter() {
        final StatementPattern a = star(A);
        final StatementPattern b = star(B);
        final StatementPattern c = star(C);
        final QueryRoot root = new QueryRoot(new Join(new Join(a, b), c));
        optimize(root, 35);
        final Join top = assertInstanceOf(Join.class, root.getArg());
        final SubjectMergeJoin merge = assertInstanceOf(SubjectMergeJoin.class, top.getLeftArg());
        assertEquals(List.of(a, b), merge.getPatterns());
        assertSame(c, top.getRightArg());
        assertEquals("x", merge.getSubjectName());
        assertTrue(merge.getAssuredBindingNames().contains("x"));
    }

    @Test
    public void everyListWithinTheRatioIsMerged() {
        final QueryRoot root = new QueryRoot(new Join(new Join(star(A), star(B)), star(C)));
        optimize(root, 1000);
        assertEquals(3, assertInstanceOf(SubjectMergeJoin.class, root.getArg()).getPatterns().size());
    }

    @Test
    public void nothingIsMergedWhenOffWithoutCountersOrWithADataset() {
        final QueryRoot off = new QueryRoot(new Join(star(A), star(B)));
        optimize(off, 0);
        assertInstanceOf(Join.class, off.getArg());

        final QueryRoot noCounters = new QueryRoot(new Join(star(A), star(B)));
        new SubjectMergeOptimizer(new EvaluationStatistics(), conf(35), true).optimize(noCounters, null, null);
        assertInstanceOf(Join.class, noCounters.getArg());

        final QueryRoot dataset = new QueryRoot(new Join(star(A), star(B)));
        new SubjectMergeOptimizer(statistics(), conf(35), true).optimize(dataset, new SimpleDataset(), null);
        assertInstanceOf(Join.class, dataset.getArg());

        final QueryRoot tracked = new QueryRoot(new Join(star(A), star(B)));
        new SubjectMergeOptimizer(statistics(), conf(35), false).optimize(tracked, null, null);
        assertInstanceOf(Join.class, tracked.getArg());
    }

    @Test
    public void theDriverMustBeAStarPattern() {
        final StatementPattern attribute = new StatementPattern(new Var("x"), new Var("c-name", VF.createIRI("urn:name")), new Var("n"));
        final QueryRoot root = new QueryRoot(new Join(new Join(attribute, star(A)), star(B)));
        optimize(root, 35);
        assertFalse(contains(root, SubjectMergeJoin.class), root.toString());
    }

    @Test
    public void namedGraphPatternsAndOtherSubjectsAreNotStarPatterns() {
        final StatementPattern named = new StatementPattern(StatementPattern.Scope.NAMED_CONTEXTS, new Var("x"),
                new Var("c-p", P), new Var("c-b", B), new Var("g"));
        assertFalse(SubjectMergeOptimizer.isStarPattern(named));
        final StatementPattern other = new StatementPattern(new Var("y"), new Var("c-p", P), new Var("c-b", B));
        final QueryRoot root = new QueryRoot(new Join(star(A), other));
        optimize(root, 1000);
        assertFalse(contains(root, SubjectMergeJoin.class), "one star pattern on ?x is not a star: " + root);
        assertFalse(SubjectMergeOptimizer.isStarPattern(new StatementPattern(new Var("x"), new Var("c-p", P), new Var("o"))));
    }

    @Test
    public void aFilterEndsTheChain() {
        final StatementPattern c = star(C);
        final Filter filter = new Filter(new Join(star(A), star(B)), new Compare(new Var("x"), new ValueConstant(A)));
        final QueryRoot root = new QueryRoot(new Join(filter, c));
        optimize(root, 1000);
        final Join top = assertInstanceOf(Join.class, root.getArg());
        assertSame(filter, top.getLeftArg());
        assertSame(c, top.getRightArg(), "the pattern above the filter stays where it is");
        assertEquals(2, assertInstanceOf(SubjectMergeJoin.class, filter.getArg()).getPatterns().size());
    }

    @Test
    public void aGroupWithAScopeOfItsOwnKeepsIt() {
        final Join group = new Join(star(A), star(B));
        group.setVariableScopeChange(true);
        final QueryRoot root = new QueryRoot(group);
        optimize(root, 35);
        final SubjectMergeJoin merge = assertInstanceOf(SubjectMergeJoin.class, root.getArg());
        assertTrue(merge.isVariableScopeChange(), "evaluated once, as the group was");
    }

    @Test
    public void aCloneIsADeepCopy() {
        final SubjectMergeJoin merge = new SubjectMergeJoin("x", List.of(star(A), star(B)));
        final SubjectMergeJoin copy = merge.clone();
        assertEquals(merge, copy);
        for (int i = 0; i < 2; i++) {
            assertFalse(merge.getPatterns().get(i) == copy.getPatterns().get(i));
            assertSame(copy, copy.getPatterns().get(i).getParentNode());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static StatementPattern star(final IRI object) {
        return new StatementPattern(new Var("x"), new Var("c-p", P), new Var("c-" + object.getLocalName(), object));
    }

    private static void optimize(final TupleExpr root, final double ratio) {
        new SubjectMergeOptimizer(statistics(), conf(ratio), true).optimize(root, null, null);
    }

    private static StoreRdfConfiguration conf(final double ratio) {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setJoinMergeRatio(ratio);
        return conf;
    }

    private static boolean contains(final TupleExpr root, final Class<?> type) {
        return root.toString().contains(type.getSimpleName());
    }

    /** Statistics whose only counters are the {@code po} ones of {@link #LENGTHS}. */
    private static RdfCloudTripleStoreEvaluationStatistics<StoreRdfConfiguration> statistics() {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        return new RdfCloudTripleStoreEvaluationStatistics<>(conf, new RdfEvalStatsDAO<>() {
            @Override
            public void init() {
            }

            @Override
            public boolean isInitialized() {
                return true;
            }

            @Override
            public void destroy() {
            }

            @Override
            public double getCardinality(final StoreRdfConfiguration c, final CARDINALITY_OF card, final List<Value> val) {
                return getCardinality(c, card, val, null);
            }

            @Override
            public double getCardinality(final StoreRdfConfiguration c, final CARDINALITY_OF card, final List<Value> val,
                    final Resource context) {
                return card == CARDINALITY_OF.PREDICATEOBJECT ? LENGTHS.getOrDefault(val.get(1), -1.0) : -1;
            }

            @Override
            public void setConf(final StoreRdfConfiguration c) {
            }

            @Override
            public StoreRdfConfiguration getConf() {
                return conf;
            }
        });
    }
}
