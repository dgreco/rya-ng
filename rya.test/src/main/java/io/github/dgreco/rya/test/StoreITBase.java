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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;

import io.github.dgreco.rya.store.spi.Store;

/**
 * Base class for integration tests that need a store. Each test gets a {@link Store} on the
 * backend named by {@code -Drya.test.backend} (see {@link TestBackends}), and a unique table
 * prefix whose tables are deleted afterwards.
 */
public abstract class StoreITBase {
    private Store store;
    private String tablePrefix;

    @BeforeEach
    public void setUpStore() {
        tablePrefix = TablePrefixes.next();
        store = backend().openStore();
    }

    @AfterEach
    public void tearDownStore() {
        if (store != null) {
            try {
                backend().deleteTables(tablePrefix);
            } finally {
                store.close();
                store = null;
            }
        }
    }

    /** The backend the tests run on. */
    public static TestBackend backend() {
        return TestBackends.current();
    }

    /** Whether the tests run on this backend. */
    public static boolean onBackend(final String id) {
        return TestBackends.id().equals(id);
    }

    /** Skips the calling test unless the tests run on this backend. */
    public static void assumeBackend(final String id) {
        Assumptions.assumeTrue(onBackend(id), "runs on " + id + " only");
    }

    /** This test's store; closed after the test. */
    public Store getStore() {
        return store;
    }

    /** A table prefix unique to this test, such as {@code it7_}. */
    public String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * Gives the test account these labels. A real cluster rejects (Accumulo) or ignores (HBase)
     * labels the account doesn't hold, so every visibility test must call this.
     */
    public void grantAuthorizations(final String... auths) {
        backend().grantLabels(auths);
    }

    /** The test account's labels. */
    public List<String> getUserAuthorizations() {
        return backend().labels();
    }
}
