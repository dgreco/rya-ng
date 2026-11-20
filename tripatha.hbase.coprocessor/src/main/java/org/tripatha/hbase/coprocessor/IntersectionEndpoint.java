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

import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.FilterBase;
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

import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.EntityIntersection;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.IntersectRequest;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.IntersectResponse;

/**
 * The entity index's intersection in HBase's region servers (SCALE-STUDY.md O23, D14), what
 * Accumulo's {@code EntityIntersectingIterator} does in its tablet servers: for one shard row, the
 * subjects found under every term, with their counts, or only how many. {@code HBaseStore}
 * attaches it to its index tables and calls it once per shard row and page ({@link Leapfrog}).
 *
 * <p><b>Labels:</b> a scanner an endpoint opens skips the region's coprocessor hooks, and with them
 * {@code VisibilityController}'s label filter, which client scans get in {@code RSRpcServices}. So
 * each scan here goes through the region's {@code preScannerOpen} first, on the call's own thread,
 * where HBase finds the caller: the labels are the caller's, as on a client scan, and so are any
 * other checks the region's coprocessors make there.
 */
public final class IntersectionEndpoint extends EntityIntersection implements RegionCoprocessor {
    /** Matches per page when the request doesn't say. */
    static final int MAX_MATCHES = 16_384;
    /** Cells read per page when the request doesn't say. */
    static final long MAX_CELLS = 4_000_000;
    /** Milliseconds per page when the request doesn't say. */
    static final int MAX_MILLIS = 2_000;
    /** Cells per scanner call when the request doesn't say. */
    static final int BATCH = 256;

    private RegionCoprocessorEnvironment env;

    @Override
    public void start(final CoprocessorEnvironment environment) throws IOException {
        if (!(environment instanceof RegionCoprocessorEnvironment region)) {
            throw new CoprocessorException("Tripatha's intersection endpoint must be loaded on a table");
        }
        this.env = region;
    }

    @Override
    public Iterable<Service> getServices() {
        return Collections.singleton(this);
    }

    @Override
    public void intersect(final RpcController controller, final IntersectRequest request,
            final RpcCallback<IntersectResponse> done) {
        try {
            done.run(page(env.getRegion(), request));
        } catch (final IOException e) {
            CoprocessorRpcUtils.setControllerException(controller, e);
            done.run(null);
        } catch (final RuntimeException e) {
            CoprocessorRpcUtils.setControllerException(controller, new IOException(e));
            done.run(null);
        }
    }

    static IntersectResponse page(final Region region, final IntersectRequest request) throws IOException {
        final IntersectResponse.Builder response = IntersectResponse.newBuilder();
        if (request.getPrefixesCount() == 0) {
            return response.build(); // asked whether the endpoint is here
        }
        final byte[] row = request.getRow().toByteArray();
        if (!region.getRegionInfo().containsRow(row)) {
            throw new IOException("Row " + Bytes.toStringBinary(row) + " is not in region " + region.getRegionInfo().getRegionNameAsString());
        }
        final Authorizations labels = request.getLabelsSet() ? new Authorizations(request.getLabelsList()) : null;
        final RegionPostings.Opener opener = (filter, batch) -> open(region, row, labels, filter, batch);
        final int maxMatches = request.getCount() ? Integer.MAX_VALUE
                : request.hasMaxMatches() && request.getMaxMatches() > 0 ? request.getMaxMatches() : MAX_MATCHES;
        final Leapfrog.Budget budget = new Leapfrog.Budget(request.hasMaxCells() ? request.getMaxCells() : MAX_CELLS,
                request.hasMaxMillis() ? request.getMaxMillis() : MAX_MILLIS);
        final long fromTs = request.hasFromTimestamp() ? request.getFromTimestamp() : 0;
        final long toTs = request.hasToTimestamp() ? request.getToTimestamp() : Long.MAX_VALUE;
        final int batch = request.hasBatch() && request.getBatch() > 0 ? request.getBatch() : BATCH;
        final byte[] from = request.getFromSubject().toByteArray();
        final long[] totals = new long[2];
        region.startRegionOperation(Region.Operation.SCAN);
        final List<RegionPostings> lists = new ArrayList<>(request.getPrefixesCount());
        Throwable failed = null;
        try {
            for (final ByteString prefix : request.getPrefixesList()) {
                lists.add(new RegionPostings(opener, prefix.toByteArray(), from, budget, fromTs, toTs, batch));
            }
            final byte[] resume = Leapfrog.page(lists, from, maxMatches, budget, (subject, rows) -> {
                if (request.getCount()) {
                    totals[0]++;
                    totals[1] = Math.addExact(totals[1], rows);
                } else {
                    response.addSubjects(ByteString.copyFrom(subject)).addValues(rows);
                }
            });
            if (request.getCount()) {
                response.setSubjectCount(totals[0]).setRowCount(totals[1]);
            }
            if (resume != null) {
                response.setResume(ByteString.copyFrom(resume));
            }
            return response.build();
        } catch (final IOException | RuntimeException e) {
            failed = e;
            throw e;
        } finally {
            // A scanner that fails to close after a whole page leaves the page right: only a failed
            // page reports it, with its own failure.
            for (final RegionPostings list : lists) {
                try {
                    list.close();
                } catch (final IOException e) {
                    if (failed != null) {
                        failed.addSuppressed(e);
                    }
                }
            }
            region.closeRegionOperation(Region.Operation.SCAN);
        }
    }

    /** A scanner over the one row, through the region's preScannerOpen like a client scan's. */
    private static RegionScanner open(final Region region, final byte[] row, final Authorizations labels, final FilterBase filter,
            final int batch) throws IOException {
        final Scan scan = new Scan().withStartRow(row).withStopRow(row, true).addFamily(FAMILY).setFilter(filter);
        scan.setBatch(batch);
        if (labels != null) {
            scan.setAuthorizations(labels);
        }
        final RegionCoprocessorHost host = region instanceof HRegion h ? h.getCoprocessorHost() : null;
        if (host != null) {
            host.preScannerOpen(scan);
        }
        return region.getScanner(scan);
    }

    /** Tripatha's one column family on HBase ({@code HBaseCodec.FAMILY}). */
    static final byte[] FAMILY = {'c'};
}
