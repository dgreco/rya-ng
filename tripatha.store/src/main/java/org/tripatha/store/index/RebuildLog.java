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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.tripatha.api.layout.TableLayoutStrategy;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.RowMutation;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.store.spi.Store;

/**
 * The statements deleted while an index is BUILDING (SCALE-STUDY.md O5). A loader rebuild reads
 * {@code spo}, and imports the index's files hours later: a statement deleted in between would
 * come back from those files. So a writer that sees an index BUILDING records each cell of
 * {@code spo} it deletes here, and flushes the record before the delete. After its import, the
 * rebuild looks up every recorded cell in {@code spo} and deletes the index entries of those
 * that are gone.
 *
 * <p>One cell in the {@code ns} table, which every writer may write, per deleted {@code spo} cell:
 * row = {@value #ROW} {@code 00}, the {@code spo} row's length (4 bytes), that row, then its
 * qualifier; family {@value #FAMILY}; qualifier = the graph; the statement's own label and
 * timestamp (so reading it needs the statement's labels, as {@code spo} does); value = the
 * {@code spo} value. Its readers ask for its family, and nothing else in {@code ns} has it.
 */
public final class RebuildLog {
    /** The rows' prefix, before the 00 byte. */
    public static final String ROW = "tripatha.rebuild";
    /** The family of every cell. */
    public static final String FAMILY = "rebuild";
    private static final byte[] PREFIX = (ROW + "\0").getBytes(StandardCharsets.UTF_8);
    private static final byte[] FAMILY_BYTES = FAMILY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];

    /** A deleted cell of {@code spo}. */
    public record Entry(byte[] row, byte[] graph, byte[] qualifier, byte[] visibility, long timestamp, byte[] value) {
    }

    private RebuildLog() {
    }

    /** The record of one {@code spo} delete (a mutation of {@code serializeDelete}). */
    public static List<RowMutation> records(final RowMutation spoDelete) {
        final List<RowMutation> out = new ArrayList<>(spoDelete.updates().size());
        for (final RowMutation.Update u : spoDelete.updates()) {
            final byte[] qualifier = u.qualifier() == null ? EMPTY : u.qualifier();
            final ByteBuffer row = ByteBuffer.allocate(PREFIX.length + Integer.BYTES + spoDelete.row().length + qualifier.length);
            row.put(PREFIX).putInt(spoDelete.row().length).put(spoDelete.row()).put(qualifier);
            out.add(new RowMutation(row.array()).put(FAMILY_BYTES, u.family() == null ? EMPTY : u.family(),
                    u.visibility() == null ? EMPTY : u.visibility(), u.timestamp(), u.value() == null ? EMPTY : u.value()));
        }
        return out;
    }

    /** Every record under the labels given, in row order. */
    public static List<Entry> read(final Store store, final TableLayoutStrategy tables, final Auths auths) {
        final List<Entry> out = new ArrayList<>();
        final ScanSpec spec = ScanSpec.builder(auths).family(FAMILY_BYTES).build();
        try (CellScanner cells = store.scan(tables.getNs(), KeyRange.prefix(PREFIX), spec)) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                final ByteBuffer row = ByteBuffer.wrap(c.row(), PREFIX.length, c.row().length - PREFIX.length);
                final byte[] spoRow = new byte[row.getInt()];
                row.get(spoRow);
                final byte[] qualifier = Arrays.copyOfRange(c.row(), row.position(), c.row().length);
                out.add(new Entry(spoRow, c.qualifier(), qualifier, c.visibility(), c.timestamp(), c.value()));
            }
        }
        return out;
    }

    /** Deletes every record visible under the labels given. */
    public static void clear(final Store store, final TableLayoutStrategy tables, final Auths auths) {
        store.deleteVisible(tables.getNs(), List.of(KeyRange.prefix(PREFIX)), ScanSpec.builder(auths).family(FAMILY_BYTES).build(),
                org.tripatha.store.spi.WriterOptions.DEFAULT);
    }
}
