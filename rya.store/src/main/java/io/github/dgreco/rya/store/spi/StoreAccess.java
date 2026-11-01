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
package io.github.dgreco.rya.store.spi;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The account a store connects as, and its labels. */
public interface StoreAccess {

    /** The account's name. */
    String whoami();

    /**
     * The labels the account holds, when the backend lets the account read them. HBase doesn't
     * tell a normal account its labels, so this is empty there.
     */
    Optional<List<String>> heldLabels();

    /** Whether the account may create tables. */
    boolean mayCreateTables();

    /**
     * Makes labels usable in writes. HBase needs labels registered before any cell uses them;
     * Accumulo accepts any label, so this does nothing there.
     *
     * @throws IllegalStateException if a label could not be registered
     */
    void registerLabels(Collection<String> labels);

    /**
     * Adds labels to an account's labels (registering them first where the backend needs it).
     * Labels are only ever added.
     *
     * @throws IllegalStateException if a label could not be granted
     */
    void grantLabels(String user, Collection<String> labels);
}
