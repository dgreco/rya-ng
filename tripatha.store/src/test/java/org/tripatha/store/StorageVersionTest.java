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
package org.tripatha.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which stored versions make a DAO refuse to write ({@link RyaStoreDAO#isNewer}). */
public class StorageVersionTest {

    @Test
    public void versionsCompareNumberByNumber() {
        assertFalse(RyaStoreDAO.isNewer(null, "1.1.0"), "no version: an older store");
        assertFalse(RyaStoreDAO.isNewer("1.0.0", "1.1.0"));
        assertFalse(RyaStoreDAO.isNewer("1.1.0", "1.1.0"));
        assertFalse(RyaStoreDAO.isNewer("1.1", "1.1.0"));
        assertFalse(RyaStoreDAO.isNewer(" 1.1.0 ", "1.1.0"));
        assertFalse(RyaStoreDAO.isNewer("1.9.0", "1.10.0"), "9 is less than 10, though not as text");
        assertTrue(RyaStoreDAO.isNewer("1.10.0", "1.9.0"));
        assertTrue(RyaStoreDAO.isNewer("1.1.1", "1.1.0"));
        assertTrue(RyaStoreDAO.isNewer("2", "1.1.0"));
        assertTrue(RyaStoreDAO.isNewer("1.1.0-SNAPSHOT", "1.1.0"), "not numbers: taken as newer");
    }
}
