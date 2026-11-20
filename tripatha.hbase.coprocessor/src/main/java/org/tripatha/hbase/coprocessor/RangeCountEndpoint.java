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
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterBase;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter;
import org.apache.hadoop.hbase.filter.MultipleColumnPrefixFilter;
import org.apache.hadoop.hbase.ipc.CoprocessorRpcUtils;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.RegionCoprocessorHost;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.security.visibility.Authorizations;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.com.google.protobuf.RpcCallback;
import org.apache.hbase.thirdparty.com.google.protobuf.RpcController;
import org.apache.hbase.thirdparty.com.google.protobuf.Service;

import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.CountRequest;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.CountResponse;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.RangeCount;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.RowRange;

/**
 * Counts, in HBase's region servers, the cells a scan of each range would return (SCALE-STUDY.md
 * O26), the way Accumulo's {@code RangeCountingIterator} counts in its tablet servers: one number
 * per range comes back instead of the cells. {@code HBaseStore} attaches it to its statement tables
 * and calls it once per region and page.
 * <ul>
 * <li><b>Labels:</b> the scan goes through the region's {@code preScannerOpen}, as the intersection
 * endpoint's do, so the caller's labels apply as on a client scan.</li>
 * <li><b>Regions:</b> each range is counted inside this region only, and the response gives the
 * region's bounds: the client sends a range's part outside them to the region holding it.</li>
 * <li><b>Pages:</b> a filter that sees every row the scan reads, hidden ones included, ends the scan
 * between two rows once the page's rows or time are spent, and the response says where to go on.</li>
 * </ul>
 */
public final class RangeCountEndpoint extends RangeCount implements RegionCoprocessor {
    /** Rows read per page when the request doesn't say. */
    static final long MAX_ROWS = 2_000_000;
    /** Milliseconds per page when the request doesn't say. */
    static final int MAX_MILLIS = 2_000;

    private RegionCoprocessorEnvironment env;

    @Override
    public void start(final CoprocessorEnvironment environment) throws IOException {
        if (!(environment instanceof RegionCoprocessorEnvironment region)) {
            throw new CoprocessorException("Tripatha's range count endpoint must be loaded on a table");
        }
        this.env = region;
    }

    @Override
    public Iterable<Service> getServices() {
        return Collections.singleton(this);
    }

    @Override
    public void count(final RpcController controller, final CountRequest request, final RpcCallback<CountResponse> done) {
        try {
            done.run(count(env.getRegion(), request));
        } catch (final IOException e) {
            CoprocessorRpcUtils.setControllerException(controller, e);
            done.run(null);
        } catch (final RuntimeException e) {
            CoprocessorRpcUtils.setControllerException(controller, new IOException(e));
            done.run(null);
        }
    }

    /** A range clipped to this region: rows from {@code start}, up to {@code stop} (null: the region's end). */
    private record Clipped(int index, byte[] start, boolean startInclusive, byte[] stop, boolean stopInclusive) {
        boolean after(final byte[] row) {
            return stop != null && (Bytes.compareTo(row, stop) > 0 || (!stopInclusive && Bytes.compareTo(row, stop) == 0));
        }

        boolean before(final byte[] row) {
            final int c = Bytes.compareTo(row, start);
            return c < 0 || (c == 0 && !startInclusive);
        }
    }

    static CountResponse count(final Region region, final CountRequest request) throws IOException {
        final byte[] regionStart = region.getRegionInfo().getStartKey();
        final byte[] regionEnd = region.getRegionInfo().getEndKey();
        final CountResponse.Builder response = CountResponse.newBuilder().setRegionStart(ByteString.copyFrom(regionStart))
                .setRegionEnd(ByteString.copyFrom(regionEnd));
        final long[] counts = new long[request.getRangesCount()];
        final List<Clipped> clipped = clip(request.getRangesList(), regionStart, regionEnd);
        if (clipped.isEmpty()) {
            for (final long c : counts) {
                response.addCounts(c);
            }
            return response.build();
        }
        final RowBudget budget = new RowBudget(request.hasMaxRows() ? request.getMaxRows() : MAX_ROWS,
                request.hasMaxMillis() ? request.getMaxMillis() : MAX_MILLIS);
        final List<MultiRowRangeFilter.RowRange> rowRanges = new ArrayList<>(clipped.size());
        for (final Clipped c : clipped) {
            rowRanges.add(new MultiRowRangeFilter.RowRange(c.start(), c.startInclusive(), c.stop() == null ? new byte[0] : c.stop(),
                    c.stop() != null && c.stopInclusive()));
        }
        final List<Filter> filters = new ArrayList<>(3);
        filters.add(budget);
        filters.add(new MultiRowRangeFilter(rowRanges));
        if (request.getPrefixesCount() > 0) {
            final byte[][] prefixes = new byte[request.getPrefixesCount()][];
            for (int i = 0; i < prefixes.length; i++) {
                prefixes[i] = request.getPrefixes(i).toByteArray();
            }
            filters.add(new MultipleColumnPrefixFilter(prefixes));
        }
        final Clipped first = clipped.get(0);
        final Clipped last = clipped.get(clipped.size() - 1);
        final Scan scan = new Scan().addFamily(IntersectionEndpoint.FAMILY).withStartRow(first.start(), first.startInclusive())
                .setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL, filters));
        if (last.stop() != null) {
            scan.withStopRow(last.stop(), last.stopInclusive());
        }
        scan.setTimeRange(request.hasFromTimestamp() ? request.getFromTimestamp() : 0,
                request.hasToTimestamp() ? request.getToTimestamp() : Long.MAX_VALUE);
        if (request.getLabelsSet()) {
            scan.setAuthorizations(new Authorizations(request.getLabelsList()));
        }
        region.startRegionOperation(Region.Operation.SCAN);
        try {
            final RegionCoprocessorHost host = region instanceof HRegion h ? h.getCoprocessorHost() : null;
            if (host != null) {
                host.preScannerOpen(scan);
            }
            try (RegionScanner scanner = region.getScanner(scan)) {
                final List<ExtendedCell> cells = new ArrayList<>();
                int at = 0;
                boolean more = true;
                while (more) {
                    cells.clear();
                    more = scanner.nextRaw(cells); // one row's cells
                    if (cells.isEmpty()) {
                        continue;
                    }
                    final byte[] row = CellUtil.cloneRow(cells.get(0));
                    while (at < clipped.size() && clipped.get(at).after(row)) {
                        at++;
                    }
                    if (at < clipped.size() && !clipped.get(at).before(row)) {
                        counts[clipped.get(at).index()] += cells.size();
                    }
                }
            }
        } finally {
            region.closeRegionOperation(Region.Operation.SCAN);
        }
        for (final long c : counts) {
            response.addCounts(c);
        }
        if (budget.stoppedAt != null) {
            // The first range the stop row doesn't come after, and where in it to go on.
            for (final Clipped c : clipped) {
                if (!c.after(budget.stoppedAt)) {
                    response.setResumeRange(c.index())
                            .setResumeRow(ByteString.copyFrom(c.before(budget.stoppedAt) ? c.start() : budget.stoppedAt));
                    break;
                }
            }
        }
        return response.build();
    }

    /** Each request range's part in [regionStart, regionEnd); empty parts left out. */
    private static List<Clipped> clip(final List<RowRange> ranges, final byte[] regionStart, final byte[] regionEnd) {
        final List<Clipped> out = new ArrayList<>(ranges.size());
        for (int i = 0; i < ranges.size(); i++) {
            final RowRange r = ranges.get(i);
            byte[] start = r.getStart().toByteArray();
            boolean startInclusive = r.getStartInclusive();
            if (Bytes.compareTo(start, regionStart) < 0) {
                start = regionStart;
                startInclusive = true;
            }
            byte[] stop = r.hasStop() && !r.getStop().isEmpty() ? r.getStop().toByteArray() : null;
            boolean stopInclusive = stop != null && r.getStopInclusive();
            if (regionEnd.length > 0 && (stop == null || Bytes.compareTo(stop, regionEnd) >= 0)) {
                stop = regionEnd;
                stopInclusive = false;
            }
            if (stop != null) {
                final int c = Bytes.compareTo(start, stop);
                if (c > 0 || (c == 0 && !(startInclusive && stopInclusive))) {
                    continue;
                }
            }
            out.add(new Clipped(i, start, startInclusive, stop, stopInclusive));
        }
        return out;
    }

    /**
     * Counts the rows the scan reads, shown or hidden: {@code filterRowKey} sees each one before
     * the label and column filters judge its cells. Once the page's rows or time are spent it ends
     * the scan before the row it was shown, and keeps that row, for the next page.
     */
    private static final class RowBudget extends FilterBase {
        private static final int CLOCK = 64;

        private final long maxRows;
        private final long deadline;
        private long rows;
        private byte[] stoppedAt;

        RowBudget(final long maxRows, final long maxMillis) {
            this.maxRows = Math.max(1, maxRows);
            this.deadline = System.nanoTime() + Math.max(0, maxMillis) * 1_000_000L;
        }

        @Override
        public boolean filterRowKey(final Cell firstRowCell) {
            if (stoppedAt != null) {
                return true;
            }
            // The page's first row is always read, so every page makes progress.
            if (rows > 0 && (rows >= maxRows || (rows % CLOCK == 0 && System.nanoTime() - deadline > 0))) {
                stoppedAt = CellUtil.cloneRow(firstRowCell);
                return true;
            }
            rows++;
            return false;
        }

        @Override
        public boolean filterAllRemaining() {
            return stoppedAt != null;
        }
    }
}
