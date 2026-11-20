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
package org.tripatha.hbase.coprocessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.PrivateCellUtil;
import org.apache.hadoop.hbase.filter.FilterBase;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScannerContext;

/**
 * One term's postings in a shard row of this region: the cells whose column starts with the
 * term's prefix ({@code family 00}, Tripatha's family being the term), read by a region scanner
 * of their own with the caller's labels. Seeks within the row go through {@link Seek}, a filter
 * whose hint moves the scanner to the bound, as {@code ColumnPrefixFilter} does.
 *
 * <p>The filter sees every cell the scanner reads, so it spends the page's budget, and applies the
 * time range itself for that reason: the scanner's own would skip cells unseen. When the budget is
 * spent it ends the scan at the cell it was shown, unread, and {@link #resume} opens another
 * scanner from there.
 */
final class RegionPostings implements Postings {
    /** Opens a region scanner over the row, with the caller's labels and the given filter. */
    interface Opener {
        RegionScanner open(FilterBase filter, int batch) throws IOException;
    }

    private final Opener opener;
    private final byte[] prefix;
    private final Leapfrog.Budget budget;
    private final long fromTimestamp;
    private final long toTimestamp;
    private final int batch;
    private final List<ExtendedCell> cells = new ArrayList<>();
    private int index;
    private byte[] head;
    /** Where the list reads from: the column of its bound, raised by seeks. */
    private byte[] bound;
    private RegionScanner scanner;
    private Seek seek;
    private boolean more;
    /**
     * Cells to ask for next: one after a seek, doubling up to {@link #batch} while the list is read
     * in order. A long list is mostly sought, and a full batch after each seek would read ahead
     * cells the next seek skips (on the benchmark, 256 per seek made a three-list star slower than
     * reading the lists on the client).
     */
    private int want = 1;

    RegionPostings(final Opener opener, final byte[] prefix, final byte[] from, final Leapfrog.Budget budget,
            final long fromTimestamp, final long toTimestamp, final int batch) throws IOException {
        this.opener = opener;
        this.prefix = prefix;
        this.budget = budget;
        this.fromTimestamp = fromTimestamp;
        this.toTimestamp = toTimestamp;
        this.batch = Math.max(1, batch);
        this.bound = concat(prefix, from);
        open(bound);
    }

    private void open(final byte[] column) throws IOException {
        closeScanner();
        seek = new Seek(column);
        scanner = opener.open(seek, batch);
        more = true;
        want = 1;
    }

    @Override
    public byte[] head() throws IOException {
        while (index >= cells.size()) {
            if (!more) {
                return null;
            }
            cells.clear();
            index = 0;
            head = null;
            final int n = want;
            want = Math.min(batch, want * 2);
            more = scanner.nextRaw(cells, ScannerContext.newBuilder().setBatchLimit(n).build()) && seek.stoppedAt == null;
            if (seek.stoppedAt != null || !more) {
                more = false;
                closeScanner();
            }
        }
        if (head == null) {
            head = subject(cells.get(index));
        }
        return head;
    }

    @Override
    public boolean paused() {
        return index >= cells.size() && !more && seek.stoppedAt != null;
    }

    @Override
    public byte[] frontier() {
        final byte[] column = seek.stoppedAt == null || Arrays.compareUnsigned(bound, seek.stoppedAt) >= 0 ? bound : seek.stoppedAt;
        return subject(column, 0, column.length);
    }

    @Override
    public void seek(final byte[] subject) throws IOException {
        final byte[] column = concat(prefix, subject);
        if (Arrays.compareUnsigned(column, bound) > 0) {
            bound = column;
        }
        while (index < cells.size() && Arrays.compareUnsigned(subject(cells.get(index)), subject) < 0) {
            index++;
            head = null;
        }
        if (index >= cells.size()) {
            want = 1; // the next read starts at the bound, past what was buffered
        }
        seek.target = bound;
    }

    @Override
    public void next() {
        index++;
        head = null;
    }

    @Override
    public void resume() throws IOException {
        final byte[] stoppedAt = seek.stoppedAt;
        open(stoppedAt == null || Arrays.compareUnsigned(bound, stoppedAt) >= 0 ? bound : stoppedAt);
    }

    @Override
    public void close() throws IOException {
        closeScanner();
    }

    private void closeScanner() throws IOException {
        if (scanner != null) {
            final RegionScanner s = scanner;
            scanner = null;
            s.close();
        }
    }

    private byte[] subject(final Cell cell) {
        final byte[] column = CellUtil.cloneQualifier(cell);
        return subject(column, 0, column.length);
    }

    /** A posting's subject: its column after the prefix, up to the first {@code 01} or {@code 00}. */
    private byte[] subject(final byte[] column, final int offset, final int length) {
        final int start = Math.min(offset + prefix.length, offset + length);
        int end = start;
        while (end < offset + length && column[end] != 1 && column[end] != 0) {
            end++;
        }
        return Arrays.copyOfRange(column, start, end);
    }

    static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * The list's filter: seeks to the bound, ends the scan past the term's columns or when the
     * budget is spent, and keeps the cells of the time range.
     */
    private final class Seek extends FilterBase {
        private byte[] target;
        /** The column of the cell the budget stopped at, unread; null while reading. */
        private byte[] stoppedAt;

        Seek(final byte[] target) {
            this.target = target;
        }

        @Override
        public ReturnCode filterCell(final Cell c) {
            if (stoppedAt != null) {
                return ReturnCode.NEXT_ROW;
            }
            if (CellUtil.compareQualifiers(c, target, 0, target.length) < 0) {
                // Not charged: a scanner sees at most one cell below its bound per seek (a reopened
                // one starts at the row's start), and seeks follow cells read. Charging it would
                // let an allowance of one cell go on the seek and never reach the next cell.
                return ReturnCode.SEEK_NEXT_USING_HINT;
            }
            if (!PrivateCellUtil.qualifierStartsWith(c, prefix)) {
                return ReturnCode.NEXT_ROW; // past the term's columns: the list has ended
            }
            if (!budget.spend()) {
                stoppedAt = CellUtil.cloneQualifier(c);
                return ReturnCode.NEXT_ROW;
            }
            final long ts = c.getTimestamp();
            return ts >= fromTimestamp && ts < toTimestamp ? ReturnCode.INCLUDE : ReturnCode.SKIP;
        }

        @Override
        public Cell getNextCellHint(final Cell current) {
            return PrivateCellUtil.createFirstOnRowCol(current, target, 0, target.length);
        }
    }
}
