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

/** How hard a write is made durable before it counts as written (Accumulo's names). */
public enum Durability {
    /** The table's or the server's own setting. */
    DEFAULT,
    /** Not logged: lost if a server dies before its next flush to disk. */
    NONE,
    /** Logged, not flushed. */
    LOG,
    /** Logged and flushed to the file system. */
    FLUSH,
    /** Logged and synced to disk. */
    SYNC
}
