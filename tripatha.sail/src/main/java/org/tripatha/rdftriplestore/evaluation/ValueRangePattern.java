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

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.tripatha.api.domain.RyaValueRange;

/**
 * A statement pattern {@code ?s p ?o} whose object a comparison filter bounds, read as ranges of
 * values (SCALE-STUDY.md O14): one range of {@code po} per datatype of {@code p}'s objects,
 * instead of all of {@code p}'s statements. Built by {@link ValueRangeOptimizer}.
 *
 * <p>The ranges hold every statement the filter can keep, and possibly more: the filter stays in
 * the query and decides every row. When the subject or the object is bound at run time, the
 * lookup is already narrow and goes to the store as usual.
 */
public final class ValueRangePattern extends StatementPattern {
    /**
     * How much the ranges are assumed to narrow the pattern down, for join ordering. A guess:
     * there are no histograms. It makes a bounded pattern the driver of a star when its predicate
     * has fewer than ten times as many statements as the star's shortest other pattern.
     */
    public static final double SELECTIVITY = 0.1;

    private final List<RyaValueRange> ranges;
    private final String condition;

    /**
     * @param original the pattern replaced; its variables are copied
     * @param ranges the ranges to read, possibly none (no stored value can match)
     * @param condition the comparisons the ranges were made for, for plans and traces
     */
    public ValueRangePattern(final StatementPattern original, final List<RyaValueRange> ranges, final String condition) {
        super(original.getScope(), clone(original.getSubjectVar()), clone(original.getPredicateVar()),
                clone(original.getObjectVar()), clone(original.getContextVar()));
        this.ranges = List.copyOf(ranges);
        this.condition = condition;
    }

    private static Var clone(final Var var) {
        return var == null ? null : var.clone();
    }

    public List<RyaValueRange> getRanges() {
        return ranges;
    }

    /** @return the comparisons the ranges were made for, as text */
    public String getCondition() {
        return condition;
    }

    @Override
    public ValueRangePattern clone() {
        return (ValueRangePattern) super.clone();
    }

    @Override
    public String getSignature() {
        return super.getSignature() + " [" + condition + ": "
                + (ranges.isEmpty() ? "no value can match" : ranges.stream().map(ValueRangePattern::describe)
                        .collect(Collectors.joining(", "))) + "]";
    }

    private static String describe(final RyaValueRange range) {
        final String type = range.getDataType().stringValue();
        return (type.startsWith("http://www.w3.org/2001/XMLSchema#") ? "xsd:" + type.substring(33) : "<" + type + ">")
                + " " + range.getData();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof ValueRangePattern o && super.equals(o) && ranges.equals(o.ranges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), ranges);
    }
}
