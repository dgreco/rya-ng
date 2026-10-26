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
package io.github.dgreco.rya.loader.gen;

/** The classes of generated entities, with their default share per thousand entities. */
public enum EntityClass {
    PERSON("Person", 260),
    ORGANIZATION("Organization", 50),
    COMPANY("Company", 40),
    CITY("City", 20),
    LANDMARK("Landmark", 30),
    EVENT("Event", 60),
    PRODUCT("Product", 100),
    REVIEW("Review", 140),
    ARTICLE("Article", 80),
    SENSOR("Sensor", 40),
    OBSERVATION("Observation", 180);

    private final String localName;
    private final int defaultWeight;

    EntityClass(final String localName, final int defaultWeight) {
        this.localName = localName;
        this.defaultWeight = defaultWeight;
    }

    /** Local name of the class IRI in the generator's ontology namespace. */
    public String localName() {
        return localName;
    }

    public int defaultWeight() {
        return defaultWeight;
    }

    public static EntityClass byName(final String name) {
        for (final EntityClass c : values()) {
            if (c.localName.equalsIgnoreCase(name) || c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown class " + name);
    }
}
