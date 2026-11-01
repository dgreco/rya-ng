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
package io.github.dgreco.rya.test;

import java.util.List;

import io.github.dgreco.rya.store.spi.Store;

/** One backend's shared test cluster, as {@link StoreITBase} uses it. */
public interface TestBackend {

    /** The backend's id ({@code accumulo}, {@code hbase}). */
    String id();

    /**
     * A new store, connected as the account rya-ng code runs as in tests. On HBase that account is
     * not a superuser, so labels are enforced as in production. Closing the store releases it.
     */
    Store openStore();

    /** Gives the test account these labels, registering them first where the backend needs it. Only ever adds. */
    void grantLabels(String... labels);

    /** The labels the test account holds. */
    List<String> labels();

    /** Deletes every table whose name starts with {@code prefix}. */
    void deleteTables(String prefix);

    /** Writes a table's memory to disk and fully compacts it, and waits until it is done. */
    void compact(String table);
}
