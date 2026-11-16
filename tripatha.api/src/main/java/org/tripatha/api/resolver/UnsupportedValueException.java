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
package org.tripatha.api.resolver;

/**
 * An RDF value the store has no encoding for. It must never be converted to {@code null}: in a
 * pattern {@code null} matches anything, so a lookup would return, and a delete would remove,
 * statements that were not asked for.
 */
public class UnsupportedValueException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnsupportedValueException(final String message) {
        super(message);
    }
}
