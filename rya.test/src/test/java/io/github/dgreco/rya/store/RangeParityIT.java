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
package io.github.dgreco.rya.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.CellWriter;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.spi.TableKind;
import io.github.dgreco.rya.store.spi.WriterOptions;
import io.github.dgreco.rya.store.visibility.VisibilityExpression;
import io.github.dgreco.rya.test.StoreITBase;

/**
 * Every kind of range and every read option returns exactly the cells rya-ng's semantics call for
 * (an in-memory model of Accumulo's), on whichever backend the tests run. Run on both backends,
 * this makes the backends agree with each other.
 */
class RangeParityIT extends StoreITBase {
    private static final byte[][] ROWS = {b(""), b("a"), b("a\u0000"), b("a\u0000b"), b("a\u0001"), b("ab"), b("b"),
            new byte[] {'b', (byte) 0xff}, new byte[] {(byte) 0xff, (byte) 0xff}, b("c")};
    private static final String[] FAMILIES = {"", "g1", "g2"};
    private static final String[] QUALIFIERS = {"", "q"};
    private static final String[] LABELS = {"", "A", "B", "A&B", "A|B"};

    private final List<Cell> stored = new ArrayList<>();
    private String table;
    private List<String> held;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void write() {
        grantAuthorizations("A", "B");
        held = getUserAuthorizations();
        table = getTablePrefix() + "parity";
        getStore().tables().create(table, TableKind.STATEMENTS);
        long ts = 1000;
        try (CellWriter w = getStore().newWriter(WriterOptions.DEFAULT)) {
            int i = 0;
            for (final byte[] row : ROWS) {
                for (final String f : FAMILIES) {
                    for (final String q : QUALIFIERS) {
                        // a third of the combinations, under rotating labels and timestamps
                        if (i++ % 3 != 0) {
                            continue;
                        }
                        final String label = LABELS[i % LABELS.length];
                        final Cell c = new Cell(row, b(f), b(q), b(label), ts += 7, b("v" + i));
                        w.table(table).write(new RowMutation(row).put(c.family(), c.qualifier(), c.visibility(), c.timestamp(), c.value()));
                        stored.add(c);
                    }
                }
            }
            // A second label on the same key and timestamp stays a separate cell.
            final Cell twin = new Cell(b("a"), b("g1"), b(""), b("B"), 1, b("twin-B"));
            final Cell twinA = new Cell(b("a"), b("g1"), b(""), b("A"), 1, b("twin-A"));
            for (final Cell c : List.of(twin, twinA)) {
                w.table(table).write(new RowMutation(c.row()).put(c.family(), c.qualifier(), c.visibility(), c.timestamp(), c.value()));
                stored.add(c);
            }
        }
    }

    private List<KeyRange> ranges() {
        return List.of(KeyRange.all(), KeyRange.row(b("a")), KeyRange.row(b("zz")), KeyRange.row(b("")),
                KeyRange.cell(b("a"), b("g1"), b("")), KeyRange.cell(b("ab"), b(""), b("q")),
                KeyRange.prefix(b("a")), KeyRange.prefix(b("a\u0000")), KeyRange.prefix(new byte[] {'b', (byte) 0xff}),
                KeyRange.prefix(new byte[] {(byte) 0xff}), KeyRange.prefix(b("")),
                KeyRange.closed(b("a"), b("b")), KeyRange.closed(b("a\u0000"), b("a\u0000b")),
                KeyRange.rows(b("a"), false, b("b"), false), KeyRange.rows(b("a"), true, b("b"), false),
                KeyRange.rows(null, true, b("ab"), true), KeyRange.rows(b("ab"), false, null, false), KeyRange.atLeast(b("b")),
                KeyRange.rows(b(""), false, b("a"), true));
    }

    private List<ScanSpec> specs() {
        return List.of(ScanSpec.of(Auths.NONE), ScanSpec.of(Auths.of("A")), ScanSpec.of(Auths.of("B")),
                ScanSpec.of(Auths.of("A", "B")), ScanSpec.of(Auths.ALL_HELD),
                ScanSpec.builder(Auths.of("A", "B")).family(b("g1")).build(),
                ScanSpec.builder(Auths.of("A", "B")).family(b("g1")).family(b("")).build(),
                ScanSpec.builder(Auths.of("A", "B")).column(b("g1"), b("q")).build(),
                ScanSpec.builder(Auths.of("A", "B")).family(b("g2")).column(b(""), b("")).build(),
                ScanSpec.builder(Auths.of("A", "B")).rowRegex("a.*").build(),
                ScanSpec.builder(Auths.of("A", "B")).rowRegex("a\u0000.*").build(),
                ScanSpec.builder(Auths.of("A", "B")).qualifierRegex("q").build(),
                ScanSpec.builder(Auths.of("A", "B")).timestamps(1050L, null).build(),
                ScanSpec.builder(Auths.of("A", "B")).timestamps(null, 1050L).build(),
                ScanSpec.builder(Auths.of("A")).timestamps(1001L, 1100L).family(b("g2")).build());
    }

    @Test
    void singleRangesMatchTheModelInOrder() {
        for (final KeyRange range : ranges()) {
            for (final ScanSpec spec : specs()) {
                final List<String> expected = model(List.of(range), spec);
                final List<String> actual = new ArrayList<>();
                try (CellScanner cells = getStore().scan(table, range, spec)) {
                    cells.forEachRemaining(c -> actual.add(show(c)));
                }
                assertEquals(expected, actual, "scan " + range + " with " + describe(spec));
                final List<String> batched = new ArrayList<>();
                try (CellScanner cells = getStore().batchScan(table, List.of(range), spec)) {
                    cells.forEachRemaining(c -> batched.add(show(c)));
                }
                batched.sort(null);
                final List<String> sortedExpected = new ArrayList<>(expected);
                sortedExpected.sort(null);
                assertEquals(sortedExpected, batched, "batch scan " + range + " with " + describe(spec));
            }
        }
    }

    @Test
    void manyRangesAtOnceReturnTheirUnion() {
        final List<KeyRange> ranges = List.of(KeyRange.prefix(b("a")), KeyRange.row(b("a")), KeyRange.closed(b("ab"), b("b")),
                KeyRange.cell(b("c"), b("g2"), b("")), KeyRange.row(b("")), KeyRange.prefix(new byte[] {(byte) 0xff}),
                KeyRange.row(b("missing")));
        for (final ScanSpec spec : specs()) {
            final TreeSet<String> actual = new TreeSet<>();
            try (CellScanner cells = getStore().batchScan(table, ranges, spec)) {
                cells.forEachRemaining(c -> actual.add(show(c)));
            }
            assertEquals(new TreeSet<>(model(ranges, spec)), actual, "batch scan with " + describe(spec));
        }
    }

    /** The cells a read should return, in Accumulo's key order. */
    private List<String> model(final Collection<KeyRange> ranges, final ScanSpec spec) {
        final List<String> labels = spec.auths().isAllHeld() ? held : spec.auths().labels();
        final List<Cell> out = new ArrayList<>();
        for (final Cell c : stored) {
            if (ranges.stream().noneMatch(r -> inRange(r, c))) {
                continue;
            }
            if (!VisibilityExpression.parse(c.visibility()).isVisibleTo(labels)) {
                continue;
            }
            final boolean anyColumns = !spec.families().isEmpty() || !spec.columns().isEmpty();
            if (anyColumns && spec.families().stream().noneMatch(f -> Arrays.equals(f, c.family()))
                    && spec.columns().stream().noneMatch(col -> Arrays.equals(col.family(), c.family())
                            && Arrays.equals(col.qualifier(), c.qualifier()))) {
                continue;
            }
            if (spec.rowRegex() != null && !Pattern.compile(spec.rowRegex()).matcher(new String(c.row(), StandardCharsets.UTF_8)).matches()) {
                continue;
            }
            if (spec.qualifierRegex() != null
                    && !Pattern.compile(spec.qualifierRegex()).matcher(new String(c.qualifier(), StandardCharsets.UTF_8)).matches()) {
                continue;
            }
            if ((spec.minTimestamp() != null && c.timestamp() < spec.minTimestamp())
                    || (spec.maxTimestamp() != null && c.timestamp() > spec.maxTimestamp())) {
                continue;
            }
            out.add(c);
        }
        out.sort(Comparator.comparing(Cell::row, Arrays::compareUnsigned).thenComparing(Cell::family, Arrays::compareUnsigned)
                .thenComparing(Cell::qualifier, Arrays::compareUnsigned).thenComparing(Cell::visibility, Arrays::compareUnsigned)
                .thenComparing(Comparator.comparingLong(Cell::timestamp).reversed()));
        return out.stream().map(RangeParityIT::show).toList();
    }

    private static boolean inRange(final KeyRange r, final Cell c) {
        if (!r.containsRow(c.row())) {
            return false;
        }
        return r.kind() != KeyRange.Kind.CELL || (Arrays.equals(r.family(), c.family()) && Arrays.equals(r.qualifier(), c.qualifier()));
    }

    private static String show(final Cell c) {
        return io.github.dgreco.rya.store.trace.TraceText.bytes(c.row()) + " | " + new String(c.family(), StandardCharsets.UTF_8)
                + " | " + new String(c.qualifier(), StandardCharsets.UTF_8) + " | " + new String(c.visibility(), StandardCharsets.UTF_8)
                + " @" + c.timestamp() + " = " + new String(c.value(), StandardCharsets.UTF_8);
    }

    private static String describe(final ScanSpec s) {
        return "auths=" + s.auths() + " families=" + s.families().stream().map(f -> new String(f, StandardCharsets.UTF_8)).toList()
                + " columns=" + s.columns().size() + " rowRegex=" + s.rowRegex() + " qualifierRegex=" + s.qualifierRegex()
                + " ts=[" + s.minTimestamp() + "," + s.maxTimestamp() + "]";
    }
}
