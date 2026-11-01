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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.security.visibility.CellVisibility;
import org.apache.hadoop.hbase.security.visibility.VisibilityClient;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.RegionActionResult;
import org.apache.hadoop.hbase.shaded.protobuf.generated.VisibilityLabelsProtos.VisibilityLabelsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dgreco.rya.api.persist.RyaDAOException;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.ScanSpec;
import io.github.dgreco.rya.store.visibility.VisibilityExpression;

/**
 * Label administration on HBase, and the checks that make HBase's labels behave like Accumulo's
 * (BACKENDS-STUDY.md, section 4).
 * <ul>
 *   <li>HBase only accepts registered labels, so labels are registered first ({@link #register}),
 *       by a superuser or a holder of HBase's {@code system} label.</li>
 *   <li>Each registered label gets a <em>canary</em>: one cell under that label in a small table
 *       ({@value #DEFAULT_TABLE} by default). A store reads the canaries with no labels when it
 *       opens ({@link #probe}): seeing any of them means labels aren't enforced for its account
 *       (a superuser, or {@code hbase.security.authorization} off), and the store refuses to open.</li>
 *   <li>HBase silently drops labels a reader doesn't hold, so {@link #missingCanaries} lets an
 *       endpoint check at start-up that it holds every label it is configured with.</li>
 * </ul>
 * HBase reports label failures inside its responses rather than by throwing, so every result is
 * checked here.
 */
public final class HBaseLabels {
    private static final Logger log = LoggerFactory.getLogger(HBaseLabels.class);

    /** The default canary table. */
    public static final String DEFAULT_TABLE = "rya_labels";

    private static final byte[] CANARY = "canary".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];

    private HBaseLabels() {
    }

    /**
     * Registers labels with HBase; labels already registered are fine.
     *
     * @throws IllegalStateException if a label couldn't be registered (for example, the account
     *         isn't a superuser)
     */
    public static void register(final Connection connection, final Collection<String> labels) {
        final Set<String> wanted = new LinkedHashSet<>(labels);
        if (wanted.isEmpty()) {
            return;
        }
        final VisibilityLabelsResponse response;
        try {
            response = VisibilityClient.addLabels(connection, wanted.toArray(new String[0]));
        } catch (final Throwable e) {
            throw new IllegalStateException("Could not register the labels " + wanted, e);
        }
        check(response, wanted, "register", "LabelAlreadyExistsException");
    }

    /**
     * Adds labels to an account's labels.
     *
     * @throws IllegalStateException if a label couldn't be granted
     */
    public static void grant(final Connection connection, final String user, final Collection<String> labels) {
        final Set<String> wanted = new LinkedHashSet<>(labels);
        if (wanted.isEmpty()) {
            return;
        }
        final VisibilityLabelsResponse response;
        try {
            response = VisibilityClient.setAuths(connection, wanted.toArray(new String[0]), user);
        } catch (final Throwable e) {
            throw new IllegalStateException("Could not grant the labels " + wanted + " to " + user, e);
        }
        check(response, wanted, "grant", null);
    }

    private static void check(final VisibilityLabelsResponse response, final Set<String> labels, final String what,
            final String tolerated) {
        final List<String> failures = new ArrayList<>();
        final List<String> names = new ArrayList<>(labels);
        for (int i = 0; i < response.getResultCount(); i++) {
            final RegionActionResult r = response.getResult(i);
            if (r.hasException()) {
                // The cause is often wrapped in a DoNotRetryIOException: its name is in the message.
                final String name = r.getException().getName();
                final String detail = r.getException().hasValue() ? r.getException().getValue().toStringUtf8() : "";
                if (tolerated == null || !(name.endsWith(tolerated) || detail.contains(tolerated))) {
                    final String firstLine = detail.lines().findFirst().orElse("");
                    failures.add((i < names.size() ? names.get(i) : "#" + i) + ": " + name + " " + firstLine);
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Could not " + what + " labels: " + failures);
        }
    }

    /**
     * Writes one canary cell per label into {@code table}, creating it if needed. The account must
     * hold, or be able to write, every label.
     */
    public static void writeCanaries(final Connection connection, final String table, final Collection<String> labels) {
        final TableName name = TableName.valueOf(table);
        try (Admin admin = connection.getAdmin()) {
            if (!admin.tableExists(name)) {
                try {
                    admin.createTable(TableDescriptorBuilder.newBuilder(name)
                            .setColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(HBaseCodec.FAMILY).setMaxVersions(1).build())
                            .build());
                } catch (final org.apache.hadoop.hbase.TableExistsException e) {
                    // created meanwhile
                }
            }
            final List<Put> puts = new ArrayList<>();
            for (final String label : labels) {
                final byte[] expression = VisibilityExpression.quoteIfNeeded(label).getBytes(StandardCharsets.UTF_8);
                final Put put = new Put(HBaseCodec.row(label.getBytes(StandardCharsets.UTF_8)))
                        .addColumn(HBaseCodec.FAMILY, HBaseCodec.qualifier(CANARY, EMPTY, expression), EMPTY);
                put.setCellVisibility(new CellVisibility(VisibilityExpression.parse(expression).toHBase()));
                puts.add(put);
            }
            try (Table t = connection.getTable(name)) {
                t.put(puts);
            }
        } catch (final IOException e) {
            throw new RyaDAOException(e);
        }
    }

    /**
     * Refuses a store whose account sees labelled canaries while asking for no labels: labels
     * aren't enforced for it.
     */
    static void probe(final HBaseStore store, final String table) {
        if (table == null || !store.tables().exists(table)) {
            log.warn("No label canaries ({}), so rya-ng can't check that HBase enforces labels for {}. "
                    + "Register the labels through rya-ng to create them.", table, store.access().whoami());
            return;
        }
        try (CellScanner cells = store.scan(table, KeyRange.all(), ScanSpec.of(Auths.NONE))) {
            while (cells.hasNext()) {
                final Cell c = cells.next();
                if (c.visibility().length > 0) {
                    throw new RyaDAOException("HBase doesn't enforce labels for " + store.access().whoami()
                            + ": asking for no labels, it still sees the canary of "
                            + new String(c.visibility(), StandardCharsets.UTF_8)
                            + ". Use an account that isn't an HBase superuser and doesn't hold the 'system' label, "
                            + "and set hbase.security.authorization=true on the cluster.");
                }
            }
        }
    }

    /** The labels whose canary the store can't see with those labels: not held, or not registered through rya-ng. */
    public static List<String> missingCanaries(final HBaseStore store, final String table, final Collection<String> labels) {
        final List<String> missing = new ArrayList<>();
        for (final String label : labels) {
            boolean seen = false;
            try (CellScanner cells = store.scan(table, KeyRange.row(label.getBytes(StandardCharsets.UTF_8)),
                    ScanSpec.of(Auths.of(label)))) {
                while (cells.hasNext()) {
                    seen |= cells.next().visibility().length > 0;
                }
            }
            if (!seen) {
                missing.add(label);
            }
        }
        return missing;
    }
}
