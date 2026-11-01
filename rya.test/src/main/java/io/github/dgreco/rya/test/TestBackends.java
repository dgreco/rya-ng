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

import io.github.dgreco.rya.test.accumulo.AccumuloTestBackend;
import io.github.dgreco.rya.test.hbase.HBaseTestBackend;

/** The backend the integration tests run on: {@code -Drya.test.backend=accumulo} (the default) or {@code hbase}. */
public final class TestBackends {
    /** The system property naming the backend. */
    public static final String PROPERTY = "rya.test.backend";

    private static TestBackend current;

    private TestBackends() {
    }

    /** The id of the backend the tests run on. */
    public static String id() {
        final String id = System.getProperty(PROPERTY, "accumulo").trim();
        return id.isEmpty() ? "accumulo" : id;
    }

    /** The backend the tests run on; its cluster starts on first use. */
    public static synchronized TestBackend current() {
        if (current == null) {
            current = switch (id()) {
                case "accumulo" -> new AccumuloTestBackend();
                case "hbase" -> new HBaseTestBackend();
                default -> throw new IllegalArgumentException("Unknown test backend '" + id() + "' (" + PROPERTY + ")");
            };
        }
        return current;
    }
}
