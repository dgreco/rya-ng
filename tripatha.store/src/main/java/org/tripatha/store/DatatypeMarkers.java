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
package org.tripatha.store;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import org.tripatha.api.domain.RyaStatement;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;
import org.tripatha.store.trace.StoreTrace;

/**
 * Which datatypes the objects of each predicate use (SCALE-STUDY.md O14), so that a comparison
 * filter can read one range of values per type instead of every statement of the predicate.
 *
 * <p>A marker is a counter cell in {@code eval}: the predicate as row, family {@code t}, the
 * object's datatype IRI as qualifier, the statement's label, and a delta of 1 (eval is a counter
 * table, so every backend sums it). Every add writes the markers of its statements, once per
 * call and key, whether or not statistics are on. Only a purge removes them: a marker for a type
 * with no values left only narrows less.
 *
 * <p>A reader sees a marker under the same labels as the statements it stands for, so it learns
 * only the types of statements it may see. A type is present when any of its cells is visible,
 * whatever they add up to (HBase's increment mode sets a dropped counter to zero).
 *
 * <p>The markers are complete only in a store that code writing them created. Such a store has a
 * flag in {@code ns} (row {@code tripatha}, family {@code datatypes}), written with the store.
 */
public final class DatatypeMarkers {
    /** The eval column family of the markers. */
    public static final String FAMILY = "t";
    /** The ns column family of the flag that says a store's markers are complete. */
    public static final String FLAG_CF = "datatypes";

    static final byte[] FAMILY_BYTES = FAMILY.getBytes(StandardCharsets.UTF_8);

    private DatatypeMarkers() {
    }

    /** One marker: a predicate, its object's datatype and the statement's label (one char per byte). */
    record Key(String predicate, String datatype, String visibility) {
        static Key of(final RyaStatement stmt) {
            final byte[] cv = stmt.getColumnVisibility();
            return new Key(stmt.getPredicate().getData(), DatatypeMarkers.datatype(stmt),
                    cv == null ? "" : new String(cv, StandardCharsets.ISO_8859_1));
        }

        RowMutation mutation() {
            return new RowMutation(predicate.getBytes(StandardCharsets.UTF_8)).addToCounter(FAMILY_BYTES,
                    datatype.getBytes(StandardCharsets.UTF_8), visibility.getBytes(StandardCharsets.ISO_8859_1), 1);
        }
    }

    /** The object's datatype IRI, or empty when it has none (which no comparison narrows). */
    static String datatype(final RyaStatement stmt) {
        return stmt.getObject().getDataType() == null ? "" : stmt.getObject().getDataType().stringValue();
    }

    /**
     * @return the datatypes of the objects of {@code predicate} among the statements {@code auths}
     *         may see: the qualifiers of the visible markers
     */
    static Set<String> read(final Store store, final String evalTable, final String predicate, final Auths auths) {
        final ScanSpec spec = ScanSpec.builder(auths).family(FAMILY_BYTES).build();
        final Set<String> types = new TreeSet<>();
        try (StoreTrace.Reason why = StoreTrace.reason(() -> "value ranges: the datatypes of <" + predicate + ">'s objects");
                CellScanner cells = store.scan(evalTable, KeyRange.row(predicate.getBytes(StandardCharsets.UTF_8)), spec)) {
            while (cells.hasNext()) {
                types.add(new String(cells.next().qualifier(), StandardCharsets.UTF_8));
            }
        }
        return types;
    }
}
