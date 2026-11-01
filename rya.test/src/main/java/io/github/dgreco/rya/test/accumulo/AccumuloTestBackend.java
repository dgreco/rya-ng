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
package io.github.dgreco.rya.test.accumulo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;

import io.github.dgreco.rya.accumulo.AccumuloStore;
import io.github.dgreco.rya.store.spi.Store;
import io.github.dgreco.rya.test.TestBackend;

/** The shared {@link MiniAccumuloSingleton} cluster; tests run as its root user. */
public final class AccumuloTestBackend implements TestBackend {

    @Override
    public String id() {
        return "accumulo";
    }

    /** A new root client on the shared cluster; the caller closes it. */
    public static AccumuloClient newClient() {
        return Accumulo.newClient().from(MiniAccumuloSingleton.getClientProperties()).build();
    }

    @Override
    public Store openStore() {
        return AccumuloStore.owning(newClient());
    }

    @Override
    public void grantLabels(final String... labels) {
        try (AccumuloClient client = newClient()) {
            AccumuloStore.wrap(client).access().grantLabels(MiniAccumuloSingleton.ROOT_USER, Arrays.asList(labels));
        }
    }

    @Override
    public List<String> labels() {
        try (AccumuloClient client = newClient()) {
            final List<String> out = new ArrayList<>();
            for (final byte[] a : client.securityOperations().getUserAuthorizations(MiniAccumuloSingleton.ROOT_USER)
                    .getAuthorizations()) {
                out.add(new String(a, StandardCharsets.UTF_8));
            }
            return out;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void compact(final String table) {
        try (AccumuloClient client = newClient()) {
            client.tableOperations().compact(table, null, null, true, true);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void deleteTables(final String prefix) {
        try (AccumuloClient client = newClient()) {
            for (final String table : client.tableOperations().list()) {
                if (table.startsWith(prefix)) {
                    client.tableOperations().delete(table);
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
