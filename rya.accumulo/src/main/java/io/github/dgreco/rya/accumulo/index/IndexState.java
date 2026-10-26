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
package io.github.dgreco.rya.accumulo.index;

/**
 * What is stored about an index in the ns table (row {@code rya}, family {@code index},
 * qualifier = the index id): whether it covers every statement, and the rules it was built with.
 *
 * @param status READY when the index covers every statement; BUILDING when it doesn't yet
 * @param definition {@link SecondaryIndex#definition()} at the time it was built
 */
public record IndexState(Status status, String definition) {

    public enum Status {
        /** Not usable for queries: enabled on existing data, or redefined, and not rebuilt yet. */
        BUILDING,
        /** Covers every statement; queries may use it. */
        READY
    }

    public String encode() {
        return status.name() + '\t' + definition;
    }

    public static IndexState decode(final String stored) {
        final int tab = stored.indexOf('\t');
        if (tab < 0) {
            throw new IllegalArgumentException("Bad index record: " + stored);
        }
        return new IndexState(Status.valueOf(stored.substring(0, tab)), stored.substring(tab + 1));
    }
}
