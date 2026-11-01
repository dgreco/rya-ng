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

/** What a table holds, so a backend can choose its physical settings. */
public enum TableKind {
    /** spo, po, osp: one cell per statement. */
    STATEMENTS,
    /** text, geo: secondary index entries. */
    INDEX,
    /** eval: counters, added up by the server (see {@link RowMutation#addToCounter}). */
    COUNTERS,
    /** ns, graphs: small records. */
    METADATA
}
