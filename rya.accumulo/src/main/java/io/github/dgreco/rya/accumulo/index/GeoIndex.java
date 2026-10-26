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

import java.util.Collection;
import java.util.List;

import io.github.dgreco.rya.accumulo.index.geo.GeoCells;
import io.github.dgreco.rya.accumulo.index.geo.GeoSupport;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;

/** Files each {@code geo:wktLiteral} under the geohash cells covering its geometry. */
public final class GeoIndex implements SecondaryIndex {

    @Override
    public IndexType type() {
        return IndexType.GEO;
    }

    @Override
    public String definition() {
        return GeoCells.DEFINITION;
    }

    @Override
    public Collection<String> terms(final RyaStatement st) {
        if (st.getObject() instanceof RyaIRI || !GeoSupport.isWkt(st.getObject().getDataType())
                || st.getObject().getData() == null) {
            return List.of();
        }
        // WKT that doesn't parse is stored but not indexed; geof: functions reject it too.
        return GeoSupport.parse(st.getObject().getData()).map(GeoCells::store).orElse(List.of());
    }
}
