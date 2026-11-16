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

/**
 * What a backend does natively. A store refuses settings its backend can't honour, rather than
 * emulating them above the SPI: labels checked by the code that asks for the cells would let a
 * bug in it leak data. An embedded backend, which has no server, enforces them inside the store.
 *
 * @param labels cell visibility enforced by the backend: no read through the SPI returns a cell
 *        its labels don't satisfy
 * @param serverCombine counters added up by the server, so writing one is a blind write
 * @param serverRegex row and qualifier regular expressions evaluated by the server (Java syntax)
 * @param scanIterators arbitrary server-side scan iterators ({@link ScanSpec#iterators()})
 * @param maxRowBytes the longest row the backend accepts
 */
public record Capabilities(boolean labels, boolean serverCombine, boolean serverRegex, boolean scanIterators,
        int maxRowBytes) {
}
