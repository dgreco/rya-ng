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

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.api.layout.TableLayoutStrategy;

/** The secondary indexes. */
public enum IndexType {
    TEXT("text"),
    GEO("geo");

    private final String id;

    IndexType(final String id) {
        this.id = id;
    }

    /** Short name, used in the stored index record and in messages. */
    public String id() {
        return id;
    }

    public String table(final TableLayoutStrategy tables) {
        return this == TEXT ? tables.getText() : tables.getGeo();
    }

    public boolean isEnabled(final RdfCloudTripleStoreConfiguration conf) {
        return this == TEXT ? conf.isTextIndex() : conf.isGeoIndex();
    }

    /** The setting that turns this index on. */
    public String setting() {
        return this == TEXT ? RdfCloudTripleStoreConfiguration.CONF_INDEX_TEXT : RdfCloudTripleStoreConfiguration.CONF_INDEX_GEO;
    }
}
