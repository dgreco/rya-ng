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
package io.github.dgreco.rya.hbase.coprocessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.PrivateCellUtil;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.regionserver.FlushLifeCycleTracker;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.MiniBatchOperationInProgress;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.ScannerContext;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionLifeCycleTracker;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

/**
 * Makes rya-ng's counter table write-only: a counter delta is a plain put of an 8-byte long, so it
 * joins the client's write batches like any other cell, where an HBase increment would lock the
 * row, read the counter and sync the WAL on its own (BACKENDS-STUDY.md, section 3.8).
 * <ul>
 *   <li><b>Writes:</b> every delta gets its own timestamp, {@code max(now, last one for this cell + 1,
 *       newest timestamp in the region's files + 1)}, so no delta replaces another. The table keeps
 *       every version.</li>
 *   <li><b>Reads:</b> gets and scans see every version, and each cell's versions come back added
 *       up, as one cell stamped with the newest one. (Clients add up a cell's values anyway, so a
 *       row split across responses still counts right.)</li>
 *   <li><b>Storage:</b> flushes and major compactions add the versions up the same way, so a counter
 *       doesn't keep growing. Minor compactions don't: a delete in a file they don't read could
 *       still apply to part of what they would add up.</li>
 * </ul>
 * Deletes are ordinary: a delete of a cell at the timestamp it was read with removes all of it.
 */
public final class CounterObserver implements RegionCoprocessor, RegionObserver {
    /**
     * Entries of the last-timestamp map are dropped once the clock has passed them: a later delta
     * gets the clock's time, which is already greater.
     */
    private static final int CLEANUP_EVERY = 10_000;

    private final Map<ColumnKey, Long> last = new ConcurrentHashMap<>();
    private final AtomicLong writes = new AtomicLong();
    /** The region's clock: never goes back, and never below what its files hold. */
    private final AtomicLong clock = new AtomicLong();
    private volatile long floor;

    @Override
    public Optional<RegionObserver> getRegionObserver() {
        return Optional.of(this);
    }

    /** A row and qualifier, compared by content. */
    private record ColumnKey(byte[] row, byte[] qualifier) {
        @Override
        public boolean equals(final Object o) {
            return o instanceof ColumnKey k && Arrays.equals(k.row, row) && Arrays.equals(k.qualifier, qualifier);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(row) + Arrays.hashCode(qualifier);
        }
    }

    @Override
    public void postOpen(final ObserverContext<? extends RegionCoprocessorEnvironment> c) {
        // New deltas must sort after everything already stored, whatever this server's clock says.
        long newest = 0;
        for (final Store store : c.getEnvironment().getRegion().getStores()) {
            for (final StoreFile file : store.getStorefiles()) {
                final OptionalLong max = file.getMaximumTimestamp();
                if (max.isPresent()) {
                    newest = Math.max(newest, max.getAsLong());
                }
            }
        }
        floor = newest + 1;
    }

    @Override
    public void preBatchMutate(final ObserverContext<? extends RegionCoprocessorEnvironment> c,
            final MiniBatchOperationInProgress<Mutation> batch) throws IOException {
        // Puts take shared row locks, so two batches may stamp the same cell at once: a cell's next
        // timestamp is taken atomically, with the clock read inside, so it is greater than any
        // timestamp the cleanup below may have dropped.
        for (int i = 0; i < batch.size(); i++) {
            final Mutation m = batch.getOperation(i);
            if (!(m instanceof Put)) {
                continue;
            }
            for (final List<Cell> cells : m.getFamilyCellMap().values()) {
                for (final Cell cell : cells) {
                    final ColumnKey key = new ColumnKey(CellUtil.cloneRow(cell), CellUtil.cloneQualifier(cell));
                    final long ts = last.compute(key, (k, previous) -> {
                        final long now = now();
                        return previous == null || previous < now ? now : previous + 1;
                    });
                    PrivateCellUtil.setTimestamp(cell, ts);
                }
            }
        }
        if (writes.incrementAndGet() % CLEANUP_EVERY == 0) {
            // Removes an entry only if it still holds the old value (ConcurrentHashMap semantics).
            final long now = now();
            last.values().removeIf(ts -> ts < now);
        }
    }

    private long now() {
        return clock.accumulateAndGet(Math.max(EnvironmentEdgeManager.currentTime(), floor), Math::max);
    }

    @Override
    public void preGetOp(final ObserverContext<? extends RegionCoprocessorEnvironment> c, final Get get,
            final List<Cell> result) throws IOException {
        get.readAllVersions();
    }

    @Override
    public void postGetOp(final ObserverContext<? extends RegionCoprocessorEnvironment> c, final Get get,
            final List<Cell> result) throws IOException {
        final List<Cell> folded = fold(result);
        result.clear();
        result.addAll(folded);
    }

    @Override
    public void preScannerOpen(final ObserverContext<? extends RegionCoprocessorEnvironment> c, final Scan scan) {
        scan.readAllVersions();
    }

    @Override
    public boolean postScannerNext(final ObserverContext<? extends RegionCoprocessorEnvironment> c, final InternalScanner s,
            final List<Result> results, final int limit, final boolean hasNext) throws IOException {
        for (int i = 0; i < results.size(); i++) {
            final Result r = results.get(i);
            final Cell[] cells = r.rawCells();
            if (cells != null && cells.length > 1) {
                results.set(i, Result.create(fold(Arrays.asList(cells)), null, r.isStale(), r.mayHaveMoreCellsInRow()));
            }
        }
        return hasNext;
    }

    @Override
    public InternalScanner preFlush(final ObserverContext<? extends RegionCoprocessorEnvironment> c, final Store store,
            final InternalScanner scanner, final FlushLifeCycleTracker tracker) {
        return new Folding(scanner);
    }

    @Override
    public InternalScanner preCompact(final ObserverContext<? extends RegionCoprocessorEnvironment> c, final Store store,
            final InternalScanner scanner, final ScanType scanType, final CompactionLifeCycleTracker tracker,
            final CompactionRequest request) {
        // Only when the compaction reads every file and drops the deletes it applies.
        return scanType == ScanType.COMPACT_DROP_DELETES && request.isAllFiles() ? new Folding(scanner) : scanner;
    }

    private static boolean isDelta(final Cell c) {
        return c.getType() == Cell.Type.Put && c.getValueLength() == Bytes.SIZEOF_LONG;
    }

    private static boolean sameColumn(final Cell a, final Cell b) {
        return CellUtil.matchingRows(a, b) && CellUtil.matchingFamily(a, b) && CellUtil.matchingQualifier(a, b);
    }

    private static long value(final Cell c) {
        return Bytes.toLong(c.getValueArray(), c.getValueOffset(), c.getValueLength());
    }

    /** The cells with each column's consecutive deltas added up (the newest delta's key, the sum as value). */
    static List<Cell> fold(final List<? extends Cell> cells) throws IOException {
        final List<Cell> out = new ArrayList<>(cells.size());
        Cell first = null;
        long sum = 0;
        int n = 0;
        for (final Cell c : cells) {
            if (first != null && isDelta(c) && sameColumn(first, c)) {
                sum += value(c);
                n++;
                continue;
            }
            if (first != null) {
                out.add(n == 1 ? first : summed(first, sum));
            }
            if (isDelta(c)) {
                first = c;
                sum = value(c);
                n = 1;
            } else {
                first = null;
                out.add(c);
            }
        }
        if (first != null) {
            out.add(n == 1 ? first : summed(first, sum));
        }
        return out;
    }

    /** A copy of {@code newest} with the sum as value, keeping its timestamp, labels and sequence id. */
    private static Cell summed(final Cell newest, final long sum) throws IOException {
        final ExtendedCell e = (ExtendedCell) newest;
        final KeyValue kv = new KeyValue(CellUtil.cloneRow(e), CellUtil.cloneFamily(e), CellUtil.cloneQualifier(e),
                e.getTimestamp(), KeyValue.Type.Put, Bytes.toBytes(sum), PrivateCellUtil.getTags(e));
        kv.setSequenceId(e.getSequenceId());
        return kv;
    }

    /**
     * Adds up the deltas of each column as a flush or compaction writes them. A column's versions
     * may span several calls (calls are limited in cells), so the last sum is carried to the next.
     * Deletes and anything else pass through unchanged and end a run.
     */
    private static final class Folding implements InternalScanner {
        private final InternalScanner delegate;
        private ExtendedCell first;
        private long sum;
        private int n;
        private long sequenceId;

        Folding(final InternalScanner delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean next(final List<? super ExtendedCell> result, final ScannerContext context) throws IOException {
            final List<ExtendedCell> in = new ArrayList<>();
            final boolean more = delegate.next(in, context);
            for (final ExtendedCell c : in) {
                if (first != null && isDelta(c) && sameColumn(first, c)) {
                    sum += value(c);
                    n++;
                    sequenceId = Math.max(sequenceId, c.getSequenceId());
                    continue;
                }
                emit(result);
                if (isDelta(c)) {
                    first = c;
                    sum = value(c);
                    n = 1;
                    sequenceId = c.getSequenceId();
                } else {
                    result.add(c);
                }
            }
            if (!more) {
                emit(result);
            }
            return more;
        }

        private void emit(final List<? super ExtendedCell> result) throws IOException {
            if (first == null) {
                return;
            }
            if (n == 1) {
                result.add(first);
            } else {
                final KeyValue kv = new KeyValue(CellUtil.cloneRow(first), CellUtil.cloneFamily(first),
                        CellUtil.cloneQualifier(first), first.getTimestamp(), KeyValue.Type.Put, Bytes.toBytes(sum),
                        PrivateCellUtil.getTags(first));
                kv.setSequenceId(sequenceId);
                result.add(kv);
            }
            first = null;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
