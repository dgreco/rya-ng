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
package org.tripatha.accumulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.ScanSpec;

/** What the store asks Accumulo to run on its servers, and how a trace shows it. */
public class AccumuloStoreTest {

    @Test
    public void aSpecsFiltersBecomeScanIteratorsThatATraceCanShow() {
        final ScanSpec spec = ScanSpec.builder(Auths.NONE).rowRegex("x\u0000y").qualifierRegex("q.*").timestamps(5L, null)
                .build();
        final List<String> shown = AccumuloStore.describe(AccumuloStore.iterators(spec));
        // In the order Accumulo runs them: by priority.
        assertEquals(3, shown.size(), shown.toString());
        assertTrue(shown.get(0).startsWith("8 riq (RegExFilter) colqRegex=q.*"), shown.get(0));
        assertTrue(shown.get(1).startsWith("9 fi (TimestampFilter) "), shown.get(1));
        assertTrue(shown.get(2).startsWith("11 ri (RegExFilter) ") && shown.get(2).endsWith("rowRegex=x⟨00⟩y"), shown.get(2));
        assertEquals(List.of(), AccumuloStore.describe(AccumuloStore.iterators(ScanSpec.of(Auths.NONE))));
    }
}
