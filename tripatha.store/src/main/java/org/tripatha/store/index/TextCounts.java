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
package org.tripatha.store.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.tripatha.api.domain.RyaStatement;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;

/**
 * The text index's posting counts (SCALE-STUDY.md O6), so an {@code AND} is driven by its rarest
 * word: one counter per word, predicate and label in the counter table {@code <prefix>textcount}
 * (row = word {@code 00} predicate, family {@value #FAMILY_NAME}, empty qualifier, the statement's
 * label), +1 for each posting written and −1 for each deleted.
 *
 * <p>They are kept apart from the postings ({@link SecondaryIndex#puts}): a rebuild job replays
 * postings, and counts replayed with them would count twice. They drift as every counter does: a
 * statement added twice counts twice, a graph drop doesn't adjust them, and a statement written
 * during a rebuild may count twice. So they only order the operands of an {@code AND}, and only
 * once a probe has found more than its limit.
 *
 * <p>The counts are complete, and read by the planner, only when the {@code ns} record (row
 * {@code tripatha}, family {@code index}, qualifier {@value #FLAG}) holds the text index's current
 * definition: written with a store created with the index, or by a rebuild that counted.
 */
public final class TextCounts {
    /** The family of every count. */
    public static final String FAMILY_NAME = "n";
    /** The qualifier of the completeness record, next to the indexes' states. */
    public static final String FLAG = "text.counts";
    static final byte[] FAMILY = FAMILY_NAME.getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];

    private TextCounts() {
    }

    /** A word's row, for one predicate. */
    static byte[] row(final String word, final String predicate) {
        return (word + IndexKeys.SEP + predicate).getBytes(StandardCharsets.UTF_8);
    }

    /** The counter deltas of a statement's postings: one per distinct word, as the postings are. */
    public static List<RowMutation> deltas(final TextIndex index, final RyaStatement st, final long delta) {
        final List<RowMutation> out = new ArrayList<>();
        final byte[] cv = st.getColumnVisibility() == null ? EMPTY : st.getColumnVisibility();
        for (final String word : new LinkedHashSet<>(index.terms(st))) {
            out.add(new RowMutation(row(word, st.getPredicate().getData())).addToCounter(FAMILY, EMPTY, cv, delta));
        }
        return out;
    }

    /** Adds a statement's deltas to a pending batch, by counter cell. */
    static void add(final Map<CounterCell, long[]> batch, final TextIndex index, final RyaStatement st, final long delta) {
        final byte[] cv = st.getColumnVisibility() == null ? EMPTY : st.getColumnVisibility();
        for (final String word : new LinkedHashSet<>(index.terms(st))) {
            batch.computeIfAbsent(new CounterCell(word + IndexKeys.SEP + st.getPredicate().getData(),
                    new String(cv, StandardCharsets.UTF_8)), k -> new long[1])[0] += delta;
        }
    }

    /** A pending counter: its row and label. */
    record CounterCell(String row, String visibility) {
        RowMutation mutation(final long delta) {
            return new RowMutation(row.getBytes(StandardCharsets.UTF_8)).addToCounter(FAMILY, EMPTY,
                    visibility.getBytes(StandardCharsets.UTF_8), delta);
        }
    }

    /**
     * A word's count under these labels: its counter for the predicate, or the sum over every
     * predicate when it is null. Every cell is added: HBase's coprocessor mode keeps the deltas
     * until a compaction.
     */
    public static long count(final Store store, final String table, final String word, final String predicate, final Auths auths) {
        final KeyRange range = predicate == null
                ? KeyRange.prefix((word + IndexKeys.SEP).getBytes(StandardCharsets.UTF_8))
                : KeyRange.row(row(word, predicate));
        long total = 0;
        try (CellScanner cells = store.scan(table, range, ScanSpec.builder(auths).column(FAMILY, EMPTY).build())) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                total += store.counterValue(c.value());
            }
        }
        return total;
    }
}
