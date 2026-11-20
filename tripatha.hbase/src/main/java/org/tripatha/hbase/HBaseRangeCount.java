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
package org.tripatha.hbase;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.AsyncConnection;
import org.apache.hadoop.hbase.client.AsyncTable;
import org.apache.hadoop.hbase.client.AsyncTableRegionLocator;
import org.apache.hadoop.hbase.security.visibility.Authorizations;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.CountRequest;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.CountResponse;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.RangeCount;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.RowRange;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;

/**
 * Counts disjoint ranges in HBase's region servers (SCALE-STUDY.md O26), with the
 * {@code RangeCountEndpoint} of {@code tripatha.hbase.coprocessor}: the ranges are grouped by the
 * region holding their start, each region counts its group in one call, and the regions are asked
 * at once. A response gives the region's bounds and where its page ended; every part of a range
 * the region didn't count (past its end, before its start when its location was stale, after the
 * page) goes back in the queue for the next round, so splits and moves cost a round, never a count.
 *
 * <p>A page's size comes from the connection's configuration: {@value #MAX_ROWS} rows and
 * {@value #MAX_MILLIS} milliseconds, unset, the endpoint's own.
 */
final class HBaseRangeCount {
    /** Most rows a page reads. */
    static final String MAX_ROWS = "tripatha.count.page.rows";
    /** Most milliseconds a page takes. */
    static final String MAX_MILLIS = "tripatha.count.page.millis";
    /** Rounds before the count gives up: each one counts or moves every part. */
    private static final int ROUNDS = 10_000;
    private static final byte[] EMPTY = new byte[0];

    /** A part of request range {@code index}, as HBase rows; a null stop is the table's end. */
    private record Part(int index, byte[] start, boolean startInclusive, byte[] stop, boolean stopInclusive) {
    }

    private final AsyncConnection connection;
    private final TableName table;
    private final CountRequest template;

    HBaseRangeCount(final AsyncConnection connection, final TableName table, final Configuration conf, final ScanSpec spec,
            final Authorizations labels) {
        this.connection = connection;
        this.table = table;
        final CountRequest.Builder request = CountRequest.newBuilder();
        if (labels != null) {
            request.setLabelsSet(true).addAllLabels(labels.getLabels());
        }
        for (final byte[] family : spec.families()) {
            request.addPrefixes(ByteString.copyFrom(HBaseCodec.familyPrefix(family)));
        }
        request.setFromTimestamp(HBaseStore.minTs(spec)).setToTimestamp(HBaseStore.maxTs(spec));
        if (conf.get(MAX_ROWS) != null) {
            request.setMaxRows(conf.getLong(MAX_ROWS, 0));
        }
        if (conf.get(MAX_MILLIS) != null) {
            request.setMaxMillis(conf.getInt(MAX_MILLIS, 0));
        }
        this.template = request.build();
    }

    /** Whether a spec is one the endpoint applies: labels, families and a time range, no row or qualifier filters. */
    static boolean handles(final ScanSpec spec) {
        return spec.rowRegex() == null && spec.rowSuffix() == null && spec.qualifierRegex() == null && spec.columns().isEmpty()
                && spec.iterators().isEmpty();
    }

    long[] count(final List<KeyRange> ranges) {
        final long[] counts = new long[ranges.size()];
        final Deque<Part> pending = new ArrayDeque<>();
        for (int i = 0; i < ranges.size(); i++) {
            final KeyRange r = ranges.get(i);
            final byte[] start = r.start() == null ? EMPTY : HBaseCodec.bound(r.start());
            pending.add(new Part(i, start, r.start() == null || r.startInclusive(), r.end() == null ? null : HBaseCodec.bound(r.end()),
                    r.end() != null && r.endInclusive()));
        }
        final AsyncTableRegionLocator locator = connection.getRegionLocator(table);
        final AsyncTable<?> async = connection.getTable(table);
        for (int round = 0; !pending.isEmpty(); round++) {
            if (round >= ROUNDS) {
                throw new RyaDAOException("Counting " + ranges.size() + " ranges of " + table + " did not converge in " + ROUNDS + " rounds");
            }
            // A part that came back is located afresh: its region may have split or moved.
            final Map<String, List<Part>> byRegion = group(locator, pending, round > 0);
            pending.clear();
            final List<CompletableFuture<CountResponse>> calls = new ArrayList<>(byRegion.size());
            final List<List<Part>> groups = new ArrayList<>(byRegion.values());
            for (final List<Part> group : groups) {
                calls.add(call(async, group));
            }
            for (int g = 0; g < groups.size(); g++) {
                settle(groups.get(g), await(calls.get(g)), counts, pending);
            }
        }
        return counts;
    }

    private static Map<String, List<Part>> group(final AsyncTableRegionLocator locator, final Deque<Part> parts, final boolean reload) {
        final Map<String, List<Part>> byRegion = new LinkedHashMap<>();
        final List<CompletableFuture<HRegionLocation>> located = new ArrayList<>(parts.size());
        final List<Part> order = new ArrayList<>(parts);
        for (final Part p : order) {
            located.add(locator.getRegionLocation(p.start(), reload));
        }
        for (int i = 0; i < order.size(); i++) {
            byRegion.computeIfAbsent(await(located.get(i)).getRegion().getEncodedName(), k -> new ArrayList<>()).add(order.get(i));
        }
        for (final List<Part> group : byRegion.values()) {
            group.sort(Comparator.comparing(Part::start, Bytes.BYTES_COMPARATOR));
        }
        return byRegion;
    }

    private CompletableFuture<CountResponse> call(final AsyncTable<?> async, final List<Part> group) {
        final CountRequest.Builder request = template.toBuilder();
        for (final Part p : group) {
            final RowRange.Builder range = RowRange.newBuilder().setStart(ByteString.copyFrom(p.start()))
                    .setStartInclusive(p.startInclusive());
            if (p.stop() != null) {
                range.setStop(ByteString.copyFrom(p.stop())).setStopInclusive(p.stopInclusive());
            }
            request.addRanges(range);
        }
        final CountRequest built = request.build();
        return async.<RangeCount.Stub, CountResponse>coprocessorService(RangeCount::newStub,
                (stub, controller, done) -> stub.count(controller, built, done), group.get(0).start());
    }

    /**
     * Adds a region's counts, and queues what it didn't count: before its start, after its end,
     * and from where its page ended (that part, and the group's later parts, whole).
     */
    private static void settle(final List<Part> group, final CountResponse response, final long[] counts, final Deque<Part> pending) {
        final byte[] regionStart = response.getRegionStart().toByteArray();
        final byte[] regionEnd = response.getRegionEnd().toByteArray();
        final int resume = response.hasResumeRange() ? response.getResumeRange() : group.size();
        for (int k = 0; k < group.size(); k++) {
            final Part p = group.get(k);
            counts[p.index()] += response.getCounts(k);
            if (k > resume) {
                pending.add(p); // not counted at all: the page ended before it
                continue;
            }
            if (Bytes.compareTo(p.start(), regionStart) < 0) {
                // Its location was stale: the part before this region is another's.
                final boolean whole = p.stop() != null && Bytes.compareTo(p.stop(), regionStart) < 0;
                pending.add(new Part(p.index(), p.start(), p.startInclusive(), whole ? p.stop() : regionStart, whole && p.stopInclusive()));
                if (whole) {
                    continue;
                }
            }
            if (k == resume) {
                // Counted up to the resume row, which is inside this region: the rest, past it too.
                pending.add(new Part(p.index(), response.getResumeRow().toByteArray(), true, p.stop(), p.stopInclusive()));
                continue;
            }
            final boolean past = regionEnd.length > 0 && (p.stop() == null || Bytes.compareTo(p.stop(), regionEnd) > 0
                    || (Bytes.compareTo(p.stop(), regionEnd) == 0 && p.stopInclusive()));
            if (past) {
                // The part from the region's end, or all of it when it starts there or after.
                pending.add(Bytes.compareTo(p.start(), regionEnd) >= 0 ? p
                        : new Part(p.index(), regionEnd, true, p.stop(), p.stopInclusive()));
            }
        }
    }

    private static <T> T await(final CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.MINUTES);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RyaDAOException("Interrupted while counting in HBase's region servers", e);
        } catch (final ExecutionException e) {
            throw new RyaDAOException("HBase's region servers could not count", e.getCause());
        } catch (final TimeoutException e) {
            throw new RyaDAOException("HBase's region servers did not count within 10 minutes", e);
        }
    }
}
