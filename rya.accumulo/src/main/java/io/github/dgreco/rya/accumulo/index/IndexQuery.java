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

import io.github.dgreco.rya.accumulo.index.geo.GeoRegion;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates;

/** A lookup in one secondary index. Its results are candidates: the query's filter checks them. */
public sealed interface IndexQuery {

    IndexType type();

    /** Text literals that may satisfy a text query. */
    record Text(TextCandidates candidates) implements IndexQuery {
        @Override
        public IndexType type() {
            return IndexType.TEXT;
        }
    }

    /** Geometries that may lie in a region. */
    record Geo(GeoRegion region) implements IndexQuery {
        @Override
        public IndexType type() {
            return IndexType.GEO;
        }
    }
}
