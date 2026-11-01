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

import java.util.Map;

/**
 * An Accumulo scan iterator to add to a read (Accumulo's {@code IteratorSetting}). Only backends
 * with {@link Capabilities#scanIterators()} accept them.
 */
public record ServerIterator(int priority, String name, String className, Map<String, String> options) {
    public ServerIterator {
        options = Map.copyOf(options);
    }
}
