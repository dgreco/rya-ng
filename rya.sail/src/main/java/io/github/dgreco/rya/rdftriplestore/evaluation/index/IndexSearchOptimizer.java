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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.GEOF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.Compare;
import org.eclipse.rdf4j.query.algebra.Compare.CompareOp;
import org.eclipse.rdf4j.query.algebra.Difference;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.helpers.TupleExprs;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;

import io.github.dgreco.rya.accumulo.index.IndexCatalog;
import io.github.dgreco.rya.accumulo.index.IndexQuery;
import io.github.dgreco.rya.accumulo.index.IndexType;
import io.github.dgreco.rya.accumulo.index.TextIndex;
import io.github.dgreco.rya.accumulo.index.geo.GeoRegion;
import io.github.dgreco.rya.accumulo.index.geo.GeoSupport;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates;
import io.github.dgreco.rya.accumulo.index.text.TextQuery;
import io.github.dgreco.rya.accumulo.index.text.TextQueryException;
import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.rdftriplestore.function.RyaText;
import io.github.dgreco.rya.rdftriplestore.function.TextMatchesFunction;
import io.github.dgreco.rya.rdftriplestore.inference.DoNotExpandSP;

/**
 * Replaces statement patterns whose object is filtered by {@code text:matches} or a GeoSPARQL
 * function with {@link IndexedStatementPattern}s, so their matches come from an index. The
 * filters stay in the query and check every match exactly; the index only has to return every
 * statement that could pass them.
 * <p>
 * A pattern is replaced only where the filter applies to all of its matches: it must be reachable
 * from the filter through joins, unions and nested filters (or the left side of an OPTIONAL or
 * MINUS), and the filtered variable must be its unbound object and appear nowhere else in it.
 * Geo functions use the index when they imply that the two geometries intersect; distances use
 * it in the form {@code geof:distance(?g, point, unit) < d} (or {@code <=}, or reversed).
 */
public class IndexSearchOptimizer implements QueryOptimizer {
    /** GeoSPARQL relations that can only hold when the two geometries intersect. */
    static final Set<String> INTERSECTING_RELATIONS = Set.of(
            GEOF.SF_EQUALS.stringValue(), GEOF.SF_INTERSECTS.stringValue(), GEOF.SF_TOUCHES.stringValue(),
            GEOF.SF_CROSSES.stringValue(), GEOF.SF_WITHIN.stringValue(), GEOF.SF_CONTAINS.stringValue(),
            GEOF.SF_OVERLAPS.stringValue(),
            GEOF.EH_EQUALS.stringValue(), GEOF.EH_MEET.stringValue(), GEOF.EH_OVERLAP.stringValue(),
            GEOF.EH_COVERS.stringValue(), GEOF.EH_COVERED_BY.stringValue(), GEOF.EH_INSIDE.stringValue(),
            GEOF.EH_CONTAINS.stringValue(),
            GEOF.RCC8_EQ.stringValue(), GEOF.RCC8_EC.stringValue(), GEOF.RCC8_PO.stringValue(),
            GEOF.RCC8_TPP.stringValue(), GEOF.RCC8_TPPI.stringValue(), GEOF.RCC8_NTPP.stringValue(),
            GEOF.RCC8_NTPPI.stringValue());

    private final IndexCatalog catalog;
    private final RdfCloudTripleStoreConfiguration conf;

    public IndexSearchOptimizer(final IndexCatalog catalog, final RdfCloudTripleStoreConfiguration conf) {
        this.catalog = catalog;
        this.conf = conf;
    }

    @Override
    public void optimize(final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings) {
        if (!conf.isUseIndex() || conf.getLimit() != null
                || !(catalog.isReady(IndexType.TEXT) || catalog.isReady(IndexType.GEO))) {
            return;
        }
        tupleExpr.visit(new AbstractSimpleQueryModelVisitor<RuntimeException>() {
            @Override
            public void meet(final Filter filter) {
                // A chain of filters (from FILTER(a && b) once split) is handled once, from its top.
                if (!(filter.getParentNode() instanceof Filter)) {
                    rewrite(filter);
                }
                super.meet(filter);
            }
        });
    }

    private void rewrite(final Filter top) {
        final Map<String, Lookups> byVariable = new LinkedHashMap<>();
        TupleExpr below = top;
        while (below instanceof Filter f) {
            for (final ValueExpr condition : conjuncts(f.getCondition())) {
                collect(condition, byVariable);
            }
            below = f.getArg();
        }
        for (final Map.Entry<String, Lookups> e : byVariable.entrySet()) {
            final Optional<IndexQuery> query = e.getValue().query(catalog);
            if (query.isEmpty()) {
                continue;
            }
            final List<StatementPattern> targets = new ArrayList<>();
            findTargets(below, e.getKey(), targets);
            for (final StatementPattern sp : targets) {
                if (sp.getObjectVar().getName().equals(e.getKey()) && accepts(sp, query.get())) {
                    sp.replaceWith(new IndexedStatementPattern(sp, query.get()));
                }
            }
        }
    }

    /** Index lookups requested for one variable. */
    private static final class Lookups {
        private final List<TextQuery> texts = new ArrayList<>();
        private GeoRegion geo;

        /** Text and geo on one variable can't both hold (a literal is one or the other): text wins. */
        Optional<IndexQuery> query(final IndexCatalog catalog) {
            if (!texts.isEmpty() && catalog.isReady(IndexType.TEXT)) {
                final Optional<TextCandidates> candidates = TextCandidates.allOf(texts);
                if (candidates.isPresent()) {
                    return Optional.of(new IndexQuery.Text(candidates.get()));
                }
            }
            if (geo != null && texts.isEmpty() && catalog.isReady(IndexType.GEO)) {
                return Optional.of(new IndexQuery.Geo(geo));
            }
            return Optional.empty();
        }
    }

    private static List<ValueExpr> conjuncts(final ValueExpr condition) {
        final List<ValueExpr> out = new ArrayList<>();
        if (condition instanceof And and) {
            out.addAll(conjuncts(and.getLeftArg()));
            out.addAll(conjuncts(and.getRightArg()));
        } else {
            out.add(condition);
        }
        return out;
    }

    private void collect(final ValueExpr condition, final Map<String, Lookups> byVariable) {
        if (condition instanceof FunctionCall call) {
            if (RyaText.MATCHES.stringValue().equals(call.getURI())) {
                text(call, byVariable);
            } else if (INTERSECTING_RELATIONS.contains(call.getURI()) || GEOF.RELATE.stringValue().equals(call.getURI())) {
                relation(call, byVariable);
            }
        } else if (condition instanceof Compare compare) {
            distance(compare, byVariable);
        }
    }

    private static void text(final FunctionCall call, final Map<String, Lookups> byVariable) {
        if (call.getArgs().size() != 2 || !(call.getArgs().get(0) instanceof Var v) || v.hasValue()) {
            return;
        }
        final Value query = TextQueryValidator.constant(call.getArgs().get(1));
        if (!(query instanceof Literal literal) || !TextIndex.isText(literal.getDatatype())) {
            return;
        }
        try {
            final TextQuery parsed = TextMatchesFunction.parse(literal.getLabel());
            byVariable.computeIfAbsent(v.getName(), k -> new Lookups())
                    .texts.add(parsed);
        } catch (final TextQueryException e) {
            // TextQueryValidator reports it.
        }
    }

    private static void relation(final FunctionCall call, final Map<String, Lookups> byVariable) {
        final List<ValueExpr> args = call.getArgs();
        if (GEOF.RELATE.stringValue().equals(call.getURI())) {
            if (args.size() != 3 || !impliesIntersection(TextQueryValidator.constant(args.get(2)))) {
                return;
            }
        } else if (args.size() != 2) {
            return;
        }
        final Var v;
        final Value region;
        if (args.get(0) instanceof Var a && !a.hasValue()) {
            v = a;
            region = TextQueryValidator.constant(args.get(1));
        } else if (args.get(1) instanceof Var b && !b.hasValue()) {
            v = b;
            region = TextQueryValidator.constant(args.get(0));
        } else {
            return;
        }
        final Optional<Shape> shape = wkt(region);
        if (shape.isEmpty() || shape.get().isEmpty()) {
            return;
        }
        final Lookups lookups = byVariable.computeIfAbsent(v.getName(), k -> new Lookups());
        if (lookups.geo == null) {
            lookups.geo = GeoRegion.intersecting(shape.get());
        }
    }

    /** A DE-9IM pattern that requires interiors or boundaries to meet (positions II, IB, BI or BB). */
    static boolean impliesIntersection(final Value pattern) {
        if (!(pattern instanceof Literal literal)) {
            return false;
        }
        final String p = literal.getLabel();
        if (!p.matches("[TF012*]{9}")) {
            return false;
        }
        for (final int i : new int[] { 0, 1, 3, 4 }) {
            if ("T012".indexOf(p.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void distance(final Compare compare, final Map<String, Lookups> byVariable) {
        final FunctionCall call;
        final Value limit;
        final CompareOp op = compare.getOperator();
        if ((op == CompareOp.LT || op == CompareOp.LE) && compare.getLeftArg() instanceof FunctionCall f) {
            call = f;
            limit = TextQueryValidator.constant(compare.getRightArg());
        } else if ((op == CompareOp.GT || op == CompareOp.GE) && compare.getRightArg() instanceof FunctionCall f) {
            call = f;
            limit = TextQueryValidator.constant(compare.getLeftArg());
        } else {
            return;
        }
        if (!GEOF.DISTANCE.stringValue().equals(call.getURI()) || call.getArgs().size() != 3
                || !(limit instanceof Literal number)) {
            return;
        }
        final double d;
        try {
            d = number.doubleValue();
        } catch (final NumberFormatException e) {
            return;
        }
        if (!Double.isFinite(d) || d < 0 || !(TextQueryValidator.constant(call.getArgs().get(2)) instanceof IRI unit)) {
            return;
        }
        final List<ValueExpr> args = call.getArgs();
        final Var v;
        final Value center;
        if (args.get(0) instanceof Var a && !a.hasValue()) {
            v = a;
            center = TextQueryValidator.constant(args.get(1));
        } else if (args.get(1) instanceof Var b && !b.hasValue()) {
            v = b;
            center = TextQueryValidator.constant(args.get(0));
        } else {
            return;
        }
        final Optional<Shape> point = wkt(center);
        if (point.isEmpty() || !(point.get() instanceof Point p)) {
            return;
        }
        final Optional<GeoRegion> region = GeoRegion.pointsWithin(p, d, unit);
        if (region.isPresent()) {
            final Lookups lookups = byVariable.computeIfAbsent(v.getName(), k -> new Lookups());
            if (lookups.geo == null) {
                lookups.geo = region.get();
            }
        }
    }

    private static Optional<Shape> wkt(final Value value) {
        if (value instanceof Literal literal && GeoSupport.isWkt(literal.getDatatype())) {
            return GeoSupport.parse(literal.getLabel());
        }
        return Optional.empty();
    }

    /** Statement patterns under {@code node} that every row passing the filter came through. */
    private static void findTargets(final TupleExpr node, final String variable, final List<StatementPattern> out) {
        if (node.getClass() == StatementPattern.class || node.getClass() == DoNotExpandSP.class) {
            out.add((StatementPattern) node);
            return;
        }
        if (TupleExprs.isVariableScopeChange(node)) {
            return;
        }
        if (node instanceof Join join) {
            findTargets(join.getLeftArg(), variable, out);
            findTargets(join.getRightArg(), variable, out);
        } else if (node instanceof Union union) {
            findTargets(union.getLeftArg(), variable, out);
            findTargets(union.getRightArg(), variable, out);
        } else if (node instanceof Filter filter) {
            findTargets(filter.getArg(), variable, out);
        } else if (node instanceof LeftJoin leftJoin) {
            findTargets(leftJoin.getLeftArg(), variable, out);
        } else if (node instanceof Difference difference) {
            findTargets(difference.getLeftArg(), variable, out);
        } else if (node instanceof Extension extension) {
            for (final ExtensionElem elem : extension.getElements()) {
                if (elem.getName().equals(variable)) {
                    return;
                }
            }
            findTargets(extension.getArg(), variable, out);
        }
    }

    /** Whether the pattern's object is the filtered variable and the index covers the pattern. */
    private boolean accepts(final StatementPattern sp, final IndexQuery query) {
        final Var o = sp.getObjectVar();
        final Var s = sp.getSubjectVar();
        final Var p = sp.getPredicateVar();
        final Var c = sp.getContextVar();
        if (o.hasValue() || s.hasValue() || o.getName().equals(s.getName()) || o.getName().equals(p.getName())
                || (c != null && o.getName().equals(c.getName()))) {
            return false;
        }
        if (query instanceof IndexQuery.Text) {
            if (p.hasValue()) {
                return catalog.textCovers(p.getValue().stringValue());
            }
            return catalog.textPredicates().isEmpty();
        }
        return true;
    }
}
