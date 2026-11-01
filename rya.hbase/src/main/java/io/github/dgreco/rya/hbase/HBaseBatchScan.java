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
package io.github.dgreco.rya.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.ScanSpec;

/**
 * Many ranges of one table, read in parallel (Accumulo's {@code BatchScanner}): exact rows and
 * cells as one multi-get, which HBase groups by server, and the other ranges as one scan per
 * region they touch, each bounded to its region and filtered by a {@link MultiRowRangeFilter}, so
 * a region is read once whatever the number of its ranges. Up to {@link ScanSpec#threads()} reads
 * run at once; cells come back in no particular order.
 */
final class HBaseBatchScan implements CellScanner {
    private static final Object END = new Object();
    private static final int QUEUE = 4096;
    private static final int GETS_PER_CALL = 1000;

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE);
    private final List<Future<?>> workers = new ArrayList<>();
    private volatile boolean closed;
    private Object next;
    private boolean done;

    HBaseBatchScan(final HBaseStore store, final String table, final List<KeyRange> ranges, final ScanSpec spec) {
        final Queue<Unit> units = new ConcurrentLinkedQueue<>(plan(store, table, ranges, spec));
        final int n = Math.max(1, Math.min(spec.threads(), units.size()));
        final AtomicInteger running = new AtomicInteger(n);
        for (int i = 0; i < n; i++) {
            workers.add(HBaseStore.POOL.submit(() -> {
                try (Table t = store.table(table)) {
                    Unit u;
                    while (!closed && (u = units.poll()) != null) {
                        u.run(t, this);
                    }
                } catch (final Throwable e) {
                    offer(e);
                } finally {
                    if (running.decrementAndGet() == 0) {
                        offer(END);
                    }
                }
                return null;
            }));
        }
    }

    /** A read: a multi-get, or a scan of one region. */
    private interface Unit {
        void run(Table table, HBaseBatchScan out) throws IOException;
    }

    private static List<Unit> plan(final HBaseStore store, final String table, final List<KeyRange> ranges, final ScanSpec spec) {
        final List<Unit> units = new ArrayList<>();
        final List<Get> gets = new ArrayList<>();
        final List<KeyRange> rows = new ArrayList<>();
        for (final KeyRange r : ranges) {
            if (r.kind() == KeyRange.Kind.ROW || r.kind() == KeyRange.Kind.CELL) {
                gets.add(store.get(r, spec));
            } else {
                rows.add(r);
            }
        }
        for (int i = 0; i < gets.size(); i += GETS_PER_CALL) {
            final List<Get> chunk = gets.subList(i, Math.min(gets.size(), i + GETS_PER_CALL));
            units.add((t, out) -> {
                for (final Result r : t.get(chunk)) {
                    out.emit(r);
                }
            });
        }
        if (rows.isEmpty()) {
            return units;
        }
        for (final HRegionLocation region : store.regions(table)) {
            final byte[] rs = region.getRegion().getStartKey();
            final byte[] re = region.getRegion().getEndKey();
            final List<MultiRowRangeFilter.RowRange> inRegion = new ArrayList<>();
            for (final KeyRange r : rows) {
                if (intersects(r, rs, re)) {
                    inRegion.add(new MultiRowRangeFilter.RowRange(orEmpty(HBaseCodec.bound(r.start())), r.startInclusive(),
                            orEmpty(HBaseCodec.bound(r.end())), r.end() == null || r.endInclusive()));
                }
            }
            if (inRegion.isEmpty()) {
                continue;
            }
            units.add((t, out) -> {
                final MultiRowRangeFilter ranges1 = new MultiRowRangeFilter(inRegion);
                final Scan scan = new Scan().addFamily(HBaseCodec.FAMILY);
                // Explicit bounds: the region, narrowed to the ranges it holds.
                final byte[] lo = ranges1.getRowRanges().get(0).getStartRow();
                final byte[] start = rs.length == 0 || (lo.length > 0 && Arrays.compareUnsigned(lo, rs) > 0) ? lo : rs;
                if (start.length > 0) {
                    scan.withStartRow(start, true);
                }
                if (re.length > 0) {
                    scan.withStopRow(re, false);
                }
                store.configure(scan, spec, ranges1);
                scan.setTimeRange(HBaseStore.minTs(spec), HBaseStore.maxTs(spec));
                try (ResultScanner scanner = t.getScanner(scan)) {
                    Result r;
                    while (!out.closed && (r = scanner.next()) != null) {
                        out.emit(r);
                    }
                }
            });
        }
        return units;
    }

    private static byte[] orEmpty(final byte[] b) {
        return b == null ? new byte[0] : b;
    }

    /** Whether a range may hold rows of the region [rs, re) (empty = unbounded). */
    private static boolean intersects(final KeyRange r, final byte[] rs, final byte[] re) {
        final byte[] start = HBaseCodec.bound(r.start());
        final byte[] end = HBaseCodec.bound(r.end());
        if (re.length > 0 && start != null && Arrays.compareUnsigned(start, re) >= 0) {
            return false;
        }
        if (rs.length > 0 && end != null) {
            final int c = Arrays.compareUnsigned(end, rs);
            return c > 0 || (c == 0 && r.endInclusive());
        }
        return true;
    }

    private void emit(final Result r) throws IOException {
        final org.apache.hadoop.hbase.Cell[] cells = r.rawCells();
        if (cells == null) {
            return;
        }
        for (final org.apache.hadoop.hbase.Cell c : cells) {
            if (!offer(HBaseCodec.decode(c))) {
                return;
            }
        }
    }

    /** Hands an item to the reader; false once the reader has closed. */
    private boolean offer(final Object item) {
        try {
            while (!closed) {
                if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            }
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean hasNext() {
        if (done) {
            return false;
        }
        if (next == null) {
            try {
                next = queue.take();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RyaDAOException(e);
            }
        }
        if (next == END) {
            done = true;
            return false;
        }
        if (next instanceof Throwable t) {
            done = true;
            throw t instanceof RyaDAOException r ? r : new RyaDAOException(t);
        }
        return true;
    }

    @Override
    public Cell next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        final Cell c = (Cell) next;
        next = null;
        return c;
    }

    @Override
    public void close() {
        closed = true;
        done = true;
        for (final Future<?> w : workers) {
            w.cancel(true);
        }
        queue.clear();
    }
}
