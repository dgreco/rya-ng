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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.dgreco.rya.rdftriplestore.utils.FixedStatementPattern;
import io.github.dgreco.rya.rdftriplestore.utils.TransitivePropertySP;
import org.eclipse.rdf4j.query.algebra.BinaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.TupleExprs;

/**
 * Joins and OPTIONALs that each add one statement pattern on top of a <em>driver</em>, where
 * every pattern's lookup can be worked out from a driver row alone. Typically the attributes of
 * the entities the driver finds:
 *
 * <pre>
 * LeftJoin                       OPTIONAL { ?s :phone ?ph }
 *   LeftJoin                     OPTIONAL { ?s :email ?e }
 *     Join                       ?s :name ?n
 *       driver: ?s a :Person     binds ?s on every row
 * </pre>
 *
 * <p>{@link EntityPrefetchStep} then fetches all those lookups for a window of driver rows at
 * once. The rules:
 * <ul>
 *   <li>A link is a join whose right side is a statement pattern, or an OPTIONAL whose right side
 *       is one (its condition is fine: it never drops a row). A join whose pattern is filtered is
 *       not a link: it stays in the driver, so the patterns above it are fetched only for the
 *       rows that pass the filter.</li>
 *   <li>Each pattern's subject or object is bound on every driver row.</li>
 *   <li>No pattern uses a variable bound by a link below it, unless the driver binds it on every
 *       row: the lookup would depend on that link's results.</li>
 *   <li>At least two links, or one OPTIONAL: a single join is already batched by
 *       {@link BatchedJoinStep}.</li>
 * </ul>
 *
 * @param driver the expression whose rows feed the chain.
 * @param links the links, bottom to top.
 * @param patterns each link's statement pattern, bottom to top.
 */
record EntityChain(TupleExpr driver, List<BinaryTupleOperator> links, List<StatementPattern> patterns) {

    /**
     * @return the longest chain topped by this node, or {@code null} if there is none worth
     *         prefetching (then the node is planned as before, and the nodes below it get their
     *         own chance when they are planned).
     */
    static EntityChain find(final TupleExpr top) {
        final List<BinaryTupleOperator> links = new ArrayList<>();
        final List<StatementPattern> patterns = new ArrayList<>();
        TupleExpr node = top;
        StatementPattern sp;
        while ((sp = linkPattern(node)) != null) {
            links.add((BinaryTupleOperator) node);
            patterns.add(sp);
            node = ((BinaryTupleOperator) node).getLeftArg();
        }
        Collections.reverse(links);
        Collections.reverse(patterns);
        // The lowest links may not qualify (an inferred type is a join of an in-memory pattern and
        // a store pattern, for instance): they then become part of the driver.
        for (int bottom = 0; bottom < links.size(); bottom++) {
            final List<BinaryTupleOperator> chainLinks = links.subList(bottom, links.size());
            final List<StatementPattern> chainPatterns = patterns.subList(bottom, patterns.size());
            final TupleExpr driver = chainLinks.get(0).getLeftArg();
            if (worthPrefetching(chainLinks) && keysFollowFromDriver(driver, chainPatterns)) {
                return new EntityChain(driver, List.copyOf(chainLinks), List.copyOf(chainPatterns));
            }
        }
        return null;
    }

    /** @return the statement pattern this node adds if it can be a link, else null. */
    private static StatementPattern linkPattern(final TupleExpr node) {
        final TupleExpr right;
        if (node instanceof Join join) {
            // RDF4J evaluates a right side that changes scope without the left bindings.
            if (join.isMergeJoin() || TupleExprs.isVariableScopeChange(join.getRightArg())) {
                return null;
            }
            right = join.getRightArg();
        } else if (node instanceof LeftJoin leftJoin) {
            right = leftJoin.getRightArg();
        } else {
            return null;
        }
        if (!(right instanceof StatementPattern sp) || sp instanceof FixedStatementPattern
                || sp instanceof TransitivePropertySP || TupleExprs.containsSubquery(sp)) {
            return null;
        }
        return sp;
    }

    private static boolean worthPrefetching(final List<BinaryTupleOperator> links) {
        return links.size() >= 2 || links.stream().anyMatch(LeftJoin.class::isInstance);
    }

    /** Whether every pattern's lookup is decided by the driver row, whatever the links below it found. */
    private static boolean keysFollowFromDriver(final TupleExpr driver, final List<StatementPattern> patterns) {
        final Set<String> assured = driver.getAssuredBindingNames();
        final Set<String> boundByLinks = new HashSet<>();
        for (final StatementPattern sp : patterns) {
            if (!boundOnEveryRow(sp.getSubjectVar(), assured) && !boundOnEveryRow(sp.getObjectVar(), assured)) {
                return false;
            }
            for (final Var var : sp.getVarList()) {
                if (!var.hasValue() && boundByLinks.contains(var.getName()) && !assured.contains(var.getName())) {
                    return false;
                }
            }
            for (final Var var : sp.getVarList()) {
                if (!var.hasValue()) {
                    boundByLinks.add(var.getName());
                }
            }
        }
        return true;
    }

    private static boolean boundOnEveryRow(final Var var, final Set<String> assured) {
        return var != null && !var.hasValue() && assured.contains(var.getName());
    }
}
