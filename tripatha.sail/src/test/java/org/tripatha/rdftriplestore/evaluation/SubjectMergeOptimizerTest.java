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
import org.eclipse.rdf4j.query.algebra.Difference;
import org.eclipse.rdf4j.query.algebra.Exists;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.ProjectionElem;
import org.eclipse.rdf4j.query.algebra.ProjectionElemList;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.Slice;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
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
    public void aLongPatternOnItsOwnIsAMergeOfOneList() {
        final StatementPattern c = star(C);
        final QueryRoot root = new QueryRoot(projection(c));
        optimize(root, 35, 1000);
        final SubjectMergeJoin merge = assertInstanceOf(SubjectMergeJoin.class, ((Projection) root.getArg()).getArg());
        assertEquals(List.of(c), merge.getPatterns());
        assertSame(merge, c.getParentNode());
        assertEquals("x", merge.getSubjectName());

        // Shorter than the setting, the setting 0, or merges off: a pattern still.
        for (final double[] settings : List.of(new double[] {35, 1001}, new double[] {35, 0}, new double[] {0, 1000})) {
            final QueryRoot left = new QueryRoot(projection(star(C)));
            optimize(left, settings[0], (long) settings[1]);
            assertInstanceOf(StatementPattern.class, ((Projection) left.getArg()).getArg(), List.of(settings[0], settings[1]).toString());
        }
    }

    @Test
    public void aLongDriverIsMergedAloneAndItsLookupsFollowIt() {
        final StatementPattern c = star(C);
        final StatementPattern name = new StatementPattern(new Var("x"), new Var("c-name", VF.createIRI("urn:name")), new Var("n"));
        final Join join = new Join(c, name);
        final QueryRoot root = new QueryRoot(join);
        optimize(root, 35, 1000);
        assertInstanceOf(SubjectMergeJoin.class, join.getLeftArg());
        assertSame(name, join.getRightArg());
    }

    @Test
    public void onlyAPatternEvaluatedOncePerQueryIsMerged() {
        final StatementPattern name = new StatementPattern(new Var("x"), new Var("c-name", VF.createIRI("urn:name")), new Var("n"));
        // Both arms of a union, the left of an OPTIONAL and of a MINUS.
        final Union union = new Union(star(C), star(C));
        final LeftJoin optional = new LeftJoin(star(C), name.clone());
        final Difference minus = new Difference(star(C), name.clone());
        for (final TupleExpr once : List.of(union, optional, minus)) {
            optimize(new QueryRoot(once), 35, 1000);
            assertTrue(contains(once, SubjectMergeJoin.class), once.toString());
        }
        assertInstanceOf(SubjectMergeJoin.class, union.getLeftArg());
        assertInstanceOf(SubjectMergeJoin.class, union.getRightArg());
        // Evaluated per row: the right side of a join, of an OPTIONAL and of a MINUS, and inside EXISTS.
        final List<TupleExpr> perRow = List.of(new Join(name.clone(), star(C)), new LeftJoin(name.clone(), star(C)),
                new Difference(name.clone(), star(C)), new Filter(name.clone(), new Exists(star(C))));
        for (final TupleExpr expr : perRow) {
            optimize(new QueryRoot(expr), 35, 1000);
            assertFalse(contains(expr, SubjectMergeJoin.class), expr.toString());
        }
    }

    @Test
    public void aLimitShorterThanTheSettingKeepsThePattern() {
        final QueryRoot short_ = new QueryRoot(new Slice(projection(star(C)), 0, 999));
        optimize(short_, 35, 1000);
        assertFalse(contains(short_, SubjectMergeJoin.class), short_.toString());
        final QueryRoot offset = new QueryRoot(new Slice(projection(star(C)), 500, 500));
        optimize(offset, 35, 1000);
        assertTrue(contains(offset, SubjectMergeJoin.class), "500 skipped, 500 more: " + offset);
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

    /** @param single how long a pattern on its own must be to be merged */
    private static void optimize(final TupleExpr root, final double ratio, final long single) {
        final StoreRdfConfiguration conf = conf(ratio);
        conf.setJoinMergeSingle(single);
        new SubjectMergeOptimizer(statistics(), conf, true).optimize(root, null, null);
    }

    private static StoreRdfConfiguration conf(final double ratio) {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setJoinMergeRatio(ratio);
        return conf;
    }

    private static Projection projection(final TupleExpr arg) {
        return new Projection(arg, new ProjectionElemList(new ProjectionElem("x")));
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
