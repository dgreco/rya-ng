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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.AsyncTable;
import org.apache.hadoop.hbase.security.visibility.Authorizations;
import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.EntityIntersection;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.IntersectRequest;
import org.tripatha.hbase.coprocessor.protobuf.IntersectionProtos.IntersectResponse;
import org.tripatha.store.spi.Cell;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.ScanSpec;

/**
 * The entity index's intersection by the region servers (SCALE-STUDY.md O23, D14): each shard row
 * is asked of the region holding it, page after page, through {@code tripatha.hbase.coprocessor}'s
 * {@code IntersectionEndpoint}. Up to {@link ScanSpec#threads()} pages are in flight or waiting to
 * be read, so memory stays at that many pages whatever the shards hold.
 * <ul>
 * <li><b>Matches:</b> as {@link org.tripatha.store.spi.Store#intersect} describes, one cell per
 * subject, its rows in decimal; a row's subjects come in order, its pages one after the other.</li>
 * <li><b>Counts:</b> one cell per page in the family {@link #COUNT_FAMILY}, valued
 * {@code subjects,rows}, as Accumulo's iterator gives them.</li>
 * </ul>
 * A page's size comes from the connection's configuration: {@value #MAX_MATCHES} matches,
 * {@value #MAX_CELLS} cells read and {@value #MAX_MILLIS} milliseconds, unset, the endpoint's own.
 */
final class HBaseIntersection implements CellScanner {
    /** The family of a count cell. */
    static final byte[] COUNT_FAMILY = "count".getBytes(StandardCharsets.US_ASCII);
    /** Most matches per page. */
    static final String MAX_MATCHES = "tripatha.intersect.page.matches";
    /** Most cells a page reads. */
    static final String MAX_CELLS = "tripatha.intersect.page.cells";
    /** Most milliseconds a page takes. */
    static final String MAX_MILLIS = "tripatha.intersect.page.millis";
    /** Cells per scanner call in the endpoint. */
    static final String BATCH = "tripatha.intersect.batch";

    private static final byte[] EMPTY = new byte[0];

    private final AsyncTable<?> table;
    private final IntersectRequest template;
    private final boolean count;
    private final int concurrency;
    private final ArrayDeque<byte[]> rows;
    private final ArrayDeque<Next> resumes = new ArrayDeque<>();
    private final LinkedBlockingQueue<Object> done = new LinkedBlockingQueue<>();
    private int outstanding;
    private Iterator<Cell> page = Collections.emptyIterator();
    private boolean closed;

    private record Next(byte[] row, ByteString from) {
    }

    private record Page(byte[] row, IntersectResponse response) {
    }

    private record Failed(byte[] row, Throwable cause) {
    }

    HBaseIntersection(final AsyncTable<?> table, final Configuration conf, final List<byte[]> rows, final List<byte[]> families,
            final Authorizations labels, final ScanSpec spec, final boolean count) {
        this.table = table;
        this.count = count;
        this.concurrency = Math.max(1, spec.threads());
        this.rows = new ArrayDeque<>(rows);
        this.template = request(conf, families, labels, spec, count).setRow(ByteString.EMPTY).build();
    }

    /** A request without its row: the terms, labels, time range and page size. */
    static IntersectRequest.Builder request(final Configuration conf, final List<byte[]> families, final Authorizations labels,
            final ScanSpec spec, final boolean count) {
        final IntersectRequest.Builder request = IntersectRequest.newBuilder().setCount(count);
        for (final byte[] f : families) {
            request.addPrefixes(ByteString.copyFrom(HBaseCodec.familyPrefix(f)));
        }
        if (labels != null) {
            request.setLabelsSet(true).addAllLabels(labels.getLabels());
        }
        request.setFromTimestamp(HBaseStore.minTs(spec)).setToTimestamp(HBaseStore.maxTs(spec));
        if (conf.get(MAX_MATCHES) != null) {
            request.setMaxMatches(conf.getInt(MAX_MATCHES, 0));
        }
        if (conf.get(MAX_CELLS) != null) {
            request.setMaxCells(conf.getLong(MAX_CELLS, 0));
        }
        if (conf.get(MAX_MILLIS) != null) {
            request.setMaxMillis(conf.getInt(MAX_MILLIS, 0));
        }
        if (conf.get(BATCH) != null) {
            request.setBatch(conf.getInt(BATCH, 0));
        }
        return request;
    }

    /** Asks the region holding the row for one page; it lands in {@link #done}. */
    private void submit(final Next next) {
        final byte[] hbaseRow = HBaseCodec.row(next.row());
        final IntersectRequest request = template.toBuilder().setRow(ByteString.copyFrom(hbaseRow)).setFromSubject(next.from()).build();
        outstanding++;
        table.<EntityIntersection.Stub, IntersectResponse>coprocessorService(EntityIntersection::newStub,
                (stub, controller, callback) -> stub.intersect(controller, request, callback), hbaseRow).whenComplete((response, error) -> {
                    if (error != null || response == null) {
                        done.add(new Failed(next.row(), error != null ? error : new IllegalStateException("no response")));
                    } else {
                        done.add(new Page(next.row(), response));
                    }
                });
    }

    @Override
    public boolean hasNext() {
        while (!page.hasNext()) {
            if (closed) {
                return false;
            }
            // A started row's next page first, then new rows.
            while (outstanding < concurrency && (!resumes.isEmpty() || !rows.isEmpty())) {
                submit(!resumes.isEmpty() ? resumes.poll() : new Next(rows.poll(), ByteString.EMPTY));
            }
            if (outstanding == 0) {
                close();
                return false;
            }
            final Object result;
            try {
                result = done.take();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new RyaDAOException("Interrupted while intersecting in HBase's region servers", e);
            }
            outstanding--;
            if (result instanceof Failed f) {
                close();
                throw new RyaDAOException("HBase's region server could not intersect the shard "
                        + new String(f.row(), StandardCharsets.UTF_8) + " of " + table.getName(), f.cause());
            }
            final Page p = (Page) result;
            if (p.response().hasResume()) {
                resumes.add(new Next(p.row(), p.response().getResume()));
            }
            page = cells(p).iterator();
        }
        return true;
    }

    private List<Cell> cells(final Page p) {
        final IntersectResponse r = p.response();
        if (count) {
            return List.of(new Cell(p.row(), COUNT_FAMILY, EMPTY, EMPTY, 0,
                    (r.getSubjectCount() + "," + r.getRowCount()).getBytes(StandardCharsets.US_ASCII)));
        }
        final List<Cell> out = new ArrayList<>(r.getSubjectsCount());
        for (int i = 0; i < r.getSubjectsCount(); i++) {
            out.add(new Cell(p.row(), EMPTY, r.getSubjects(i).toByteArray(), EMPTY, 0,
                    Long.toString(r.getValues(i)).getBytes(StandardCharsets.US_ASCII)));
        }
        return out;
    }

    @Override
    public Cell next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return page.next();
    }

    /** Pages still in flight are dropped when they arrive: the endpoint holds nothing between calls. */
    @Override
    public void close() {
        closed = true;
        page = Collections.emptyIterator();
        rows.clear();
        resumes.clear();
    }
}
