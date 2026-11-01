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
package io.github.dgreco.rya.loader;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.github.dgreco.rya.hbase.HBaseLabels;
import io.github.dgreco.rya.hbase.HBaseStore;
import io.github.dgreco.rya.store.spi.Store;

/**
 * The {@code labels} command, and label grants for {@code prepare}: gives an account labels.
 * On HBase it also registers them (HBase only accepts registered labels), grants the reserved
 * label {@value HBaseStore#NONE_LABEL} every reader needs, and writes each label's canary, which
 * the endpoint reads at startup to check its labels (BACKENDS-STUDY.md, D3). Both need an HBase
 * superuser, or an account holding HBase's {@code system} label.
 */
final class Labels {
    private Labels() {
    }

    static void run(final Store store, final LoaderOptions o, final PrintStream out) {
        final String user = o.grantTo() != null ? o.grantTo() : store.access().whoami();
        grant(store, user, o.labelList(), o.hbaseLabelsTable());
        out.println("Gave " + user + " the labels " + o.labelList()
                + (store instanceof HBaseStore ? " (registered with HBase, canaries in " + o.hbaseLabelsTable() + ")" : "") + ".");
    }

    static void grant(final Store store, final String user, final Collection<String> labels) {
        grant(store, user, labels, HBaseLabels.DEFAULT_TABLE);
    }

    static void grant(final Store store, final String user, final Collection<String> labels, final String canaryTable) {
        final List<String> all = new ArrayList<>(labels);
        if (store instanceof HBaseStore hbase) {
            all.add(HBaseStore.NONE_LABEL);
            try {
                store.access().grantLabels(user, all);
            } catch (final IllegalStateException e) {
                throw new IllegalArgumentException(e.getMessage() + ". On HBase only a superuser can register and grant "
                        + "labels: run the labels command as one (the loading account itself must not be a superuser)", e);
            }
            HBaseLabels.writeCanaries(hbase.connection(), canaryTable, labels);
        } else {
            store.access().grantLabels(user, all);
        }
    }
}
