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
package org.tripatha.store.index;

import org.tripatha.api.RdfCloudTripleStoreConfiguration;
import org.tripatha.api.layout.TableLayoutStrategy;

/** The secondary indexes. */
public enum IndexType {
    TEXT("text"),
    GEO("geo"),
    /** The entity index (SCALE-STUDY.md O23): its matches are answers, not candidates checked against spo. */
    ENTITY("entity");

    private final String id;

    IndexType(final String id) {
        this.id = id;
    }

    /** Short name, used in the stored index record and in messages. */
    public String id() {
        return id;
    }

    public String table(final TableLayoutStrategy tables) {
        return switch (this) {
            case TEXT -> tables.getText();
            case GEO -> tables.getGeo();
            case ENTITY -> tables.getEntity();
        };
    }

    public boolean isEnabled(final RdfCloudTripleStoreConfiguration conf) {
        return switch (this) {
            case TEXT -> conf.isTextIndex();
            case GEO -> conf.isGeoIndex();
            case ENTITY -> conf.isEntityIndex();
        };
    }

    /** The setting that turns this index on. */
    public String setting() {
        return switch (this) {
            case TEXT -> RdfCloudTripleStoreConfiguration.CONF_INDEX_TEXT;
            case GEO -> RdfCloudTripleStoreConfiguration.CONF_INDEX_GEO;
            case ENTITY -> RdfCloudTripleStoreConfiguration.CONF_INDEX_ENTITY;
        };
    }
}
