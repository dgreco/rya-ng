package org.tripatha.rdftriplestore.evaluation;

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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.persist.RdfEvalStatsDAO;
import org.tripatha.api.persist.RdfEvalStatsDAO.CARDINALITY_OF;
import org.tripatha.api.persist.RdfEvalStatsDAO.CounterRead;
import org.tripatha.rdftriplestore.inference.DoNotExpandSP;
import org.tripatha.rdftriplestore.evaluation.index.IndexedStatementPattern;
import org.tripatha.rdftriplestore.utils.FixedStatementPattern;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.algebra.BinaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.Slice;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TripleRef;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.UnaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;

/**
 * Class RdfCloudTripleStoreEvaluationStatistics
 * Date: Apr 12, 2011
 * Time: 1:31:05 PM
 */
public class RdfCloudTripleStoreEvaluationStatistics<C extends RdfCloudTripleStoreConfiguration> extends EvaluationStatistics {

    private C conf;
    private RdfEvalStatsDAO<C> rdfEvalStatsDAO;
    protected boolean pushEmptyRdfTypeDown = true;
    protected boolean useCompositeCardinalities = true;
    private static final double TRIPLE_REF_CARDINALITY = 1_000_000_000d;
    /** The counters this query has read, by read: filled by {@link #prefetch}, and read first by every estimate. */
    private final Map<CounterRead, Double> counters = new HashMap<>();

    public RdfCloudTripleStoreEvaluationStatistics(final C conf, final RdfEvalStatsDAO<C> rdfEvalStatsDAO) {
        checkNotNull(conf);
        checkNotNull(rdfEvalStatsDAO);
        try {
            this.conf = conf;
            this.rdfEvalStatsDAO = rdfEvalStatsDAO;
            pushEmptyRdfTypeDown = conf.isStatsPushEmptyRdftypeDown();
            useCompositeCardinalities = conf.isUseCompositeCardinality();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public double getCardinality(final TupleExpr expr) {
        if (expr instanceof Filter) {
            final Filter f = (Filter) expr;
            // filters must make sets smaller
            return super.getCardinality(f.getArg()) / 10;
        }
        return super.getCardinality(expr);
    }

    @Override
    protected CardinalityCalculator createCardinalityCalculator() {
        return new RdfCloudTripleStoreCardinalityCalculator(this);
    }

    /**
     * Reads, together, the counter every statement pattern of the query will be estimated by
     * (SCALE-STUDY.md M14): one round trip, where the estimates would make one each (a counter
     * read cost 0.4 to 0.65 ms on the Accumulo test cluster, and planning half of a small query's
     * time). Run before the optimizers that estimate. A read it doesn't foresee is made when it
     * is asked for; {@code query.stats.prefetch=false} turns this off.
     */
    public void prefetch(final TupleExpr expr) {
        if (!conf.isStatsPrefetch()) {
            return;
        }
        final Set<CounterRead> reads = new LinkedHashSet<>();
        expr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final StatementPattern sp) {
                final CounterRead read = counterRead(sp);
                if (read != null && !counters.containsKey(read)) {
                    reads.add(read);
                }
            }
        });
        if (reads.size() >= 2) {
            counters.putAll(rdfEvalStatsDAO.getCardinalities(conf, reads));
        }
    }

    /** The counter a pattern's estimate reads, or null when it reads none (as {@code plainCardinality} decides). */
    CounterRead counterRead(final StatementPattern sp) {
        if (sp instanceof FixedStatementPattern) {
            return null;
        }
        final Value s = value(sp.getSubjectVar());
        final Value p = value(sp.getPredicateVar());
        final Value c = value(sp.getContextVar());
        if ((s != null && !(s instanceof Resource)) || (p != null && !(p instanceof IRI)) || (c != null && !(c instanceof Resource))) {
            return null;
        }
        final Value o = value(sp.getObjectVar());
        if (pushEmptyRdfTypeDown && RDF.TYPE.equals(p) && s == null && o == null) {
            return null;
        }
        return counterRead((Resource) s, (IRI) p, o, (Resource) c);
    }

    private static Value value(final Var var) {
        return var == null ? null : var.getValue();
    }

    /** The counter of a pattern with these constants: its subject's, else its predicate's, else its object's. */
    CounterRead counterRead(final Resource subj, final IRI pred, final Value obj, final Resource context) {
        final List<Value> values = new ArrayList<>(2);
        final CARDINALITY_OF card;
        if (subj != null) {
            values.add(subj);
            if (useCompositeCardinalities && pred != null) {
                values.add(pred);
                card = CARDINALITY_OF.SUBJECTPREDICATE;
            } else if (useCompositeCardinalities && obj != null) {
                values.add(obj);
                card = CARDINALITY_OF.SUBJECTOBJECT;
            } else {
                card = CARDINALITY_OF.SUBJECT;
            }
        } else if (pred != null) {
            values.add(pred);
            if (useCompositeCardinalities && obj != null) {
                values.add(obj);
                card = CARDINALITY_OF.PREDICATEOBJECT;
            } else {
                card = CARDINALITY_OF.PREDICATE;
            }
        } else if (obj != null) {
            values.add(obj);
            card = CARDINALITY_OF.OBJECT;
        } else {
            return null;
        }
        return new CounterRead(card, List.copyOf(values), context);
    }

    /** A counter's value, -1 when it has none: read once per query. */
    double cardinality(final CounterRead read) {
        final Double known = counters.get(read);
        if (known != null) {
            return known;
        }
        final double value = rdfEvalStatsDAO.getCardinality(conf, read.card(), read.values(), read.context());
        counters.put(read, value);
        return value;
    }

    public RdfEvalStatsDAO<C> getRdfEvalStatsDAO() {
        return rdfEvalStatsDAO;
    }

    public void setRdfEvalStatsDAO(final RdfEvalStatsDAO<C> rdfEvalStatsDAO) {
        this.rdfEvalStatsDAO = rdfEvalStatsDAO;
    }

    public class RdfCloudTripleStoreCardinalityCalculator extends CardinalityCalculator {
        private final RdfCloudTripleStoreEvaluationStatistics<C> statistics;
        protected Map<Var, Collection<Statement>> fspMap;

        public RdfCloudTripleStoreCardinalityCalculator(final RdfCloudTripleStoreEvaluationStatistics<C> statistics) {
            this.statistics = statistics;
        }


        @Override
        protected double getCardinality(final StatementPattern sp) {
            final double plain = plainCardinality(sp);
            if (sp instanceof ValueRangePattern ranged) {
                return ranged.estimate(plain);
            }
            return sp instanceof IndexedStatementPattern ? Math.max(1, plain * IndexedStatementPattern.SELECTIVITY) : plain;
        }

        /**
         * Without a store to count them, a triple term reference is taken to be large, so join
         * ordering starts from a pattern. {@link TripleTermOptimizer} then places it.
         */
        @Override
        protected double getCardinality(final TripleRef tripleRef) {
            return TRIPLE_REF_CARDINALITY;
        }

        private double plainCardinality(final StatementPattern sp) {
            final Var subjectVar = sp.getSubjectVar();
            final Var predicateVar = sp.getPredicateVar();
            // A literal or a triple term can be bound to these positions, and matches nothing there.
            if ((getConstantValue(subjectVar) != null && !(getConstantValue(subjectVar) instanceof Resource))
                    || (getConstantValue(predicateVar) != null && !(getConstantValue(predicateVar) instanceof IRI))
                    || (getConstantValue(sp.getContextVar()) != null && !(getConstantValue(sp.getContextVar()) instanceof Resource))) {
                return 0;
            }
            final Resource subj = (Resource) getConstantValue(subjectVar);
            final IRI pred = (IRI) getConstantValue(predicateVar);
            final Var objectVar = sp.getObjectVar();
            final Value obj = getConstantValue(objectVar);
            final Resource context = (Resource) getConstantValue(sp.getContextVar());

            // set rdf type to be a max value (as long as the object/subject aren't specified) to
                if (pred != null) {
                    if (statistics.pushEmptyRdfTypeDown && RDF.TYPE.equals(pred) && subj == null && obj == null) {
                        return Double.MAX_VALUE;
                    }
                }

            // FixedStatementPattern indicates that this is when backward chaining reasoning is being used
            if (sp instanceof FixedStatementPattern) {
                //no query here
                final FixedStatementPattern fsp = (FixedStatementPattern) sp;
                //TODO: assume that only the subject is open ended here
                final Var fspSubjectVar = fsp.getSubjectVar();
                if (fspSubjectVar != null && fspSubjectVar.getValue() == null) {
                    if (fspMap == null) {
                        fspMap = new HashMap<Var, Collection<Statement>>();
                    }
                    fspMap.put(fspSubjectVar, fsp.statements);
                }
                return fsp.statements.size();
            }

            /**
             * Use the output of the FixedStatementPattern to determine more information
             */
            if (fspMap != null && sp instanceof DoNotExpandSP) {
                //TODO: Might be a better way than 3 map pulls
                RdfEvalStatsDAO.CARDINALITY_OF cardinality_of = null;
                Collection<Statement> statements = null;
                // TODO unsure of how to incorporate additional cardinalities here
                if (objectVar != null && objectVar.getValue() == null) {
                    statements = fspMap.get(objectVar);
                    cardinality_of = RdfEvalStatsDAO.CARDINALITY_OF.OBJECT;
                }
                if (statements == null && predicateVar != null && predicateVar.getValue() == null) {
                    statements = fspMap.get(predicateVar);
                    cardinality_of = RdfEvalStatsDAO.CARDINALITY_OF.PREDICATE;
                }
                if (statements == null && subjectVar != null && subjectVar.getValue() == null) {
                    statements = fspMap.get(subjectVar);
                    cardinality_of = RdfEvalStatsDAO.CARDINALITY_OF.SUBJECT;
                }
                if (statements != null) {
                    double fspCard = 0;
                    for (final Statement statement : statements) {
                        // A missing counter (-1) counts as 1, as in the single-pattern estimates below.
                        final double card = statistics.cardinality(new CounterRead(cardinality_of, List.of(statement.getSubject()), context));
                        fspCard += card >= 0 ? card : 1;
                    }
                    return fspCard;
                }
            }

            /**
             * We put full triple scans before rdf:type because more often than not
             * the triple scan is being joined with something else that is better than
             * asking the full rdf:type of everything.
             */
            final CounterRead read = statistics.counterRead(subj, pred, obj, context);
            if (read == null) {
                return Double.MAX_VALUE - 1;
            }
            // -1: no counter (a counter of 1 may be left out), so 1.
            final double evalCard = statistics.cardinality(read);
            return evalCard >= 0 ? Math.min(Double.MAX_VALUE - 1, evalCard) : 1;
        }

        @Override
        protected void meetUnaryTupleOperator(final UnaryTupleOperator node) {
            if (node instanceof Projection) {
                cardinality += -1.0;
            }
            super.meetUnaryTupleOperator(node);
        }

        @Override
        protected void meetBinaryTupleOperator(final BinaryTupleOperator node) {
            node.getLeftArg().visit(this);
            final double leftArgCost = cardinality;
            node.getRightArg().visit(this);
            cardinality += leftArgCost;
        }

        // TODO Is this sufficient for add capability of slice node?
        @Override
        public void meet(final Slice node) {
            cardinality = node.getLimit();
        }


        @Override
        public void meet(final Join node) {
            node.getLeftArg().visit(this);
            final double leftArgCost = cardinality;
            node.getRightArg().visit(this);
            if (leftArgCost > cardinality) {
                cardinality = leftArgCost;    //TODO: Is this ok?
            }
        }

        protected Value getConstantValue(final Var var) {
            if (var != null) {
                return var.getValue();
            } else {
                return null;
            }
        }
    }

}
