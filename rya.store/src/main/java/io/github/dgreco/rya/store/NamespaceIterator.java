package io.github.dgreco.rya.store;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.impl.SimpleNamespace;

import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.CellScanner;

/** The namespaces of the ns table (row = prefix, value = namespace). */
public class NamespaceIterator implements CloseableIteration<Namespace> {
    private final CellScanner scanner;
    private boolean open = true;

    public NamespaceIterator(final CellScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public boolean hasNext() {
        return open && scanner.hasNext();
    }

    @Override
    public Namespace next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        final Cell next = scanner.next();
        return new SimpleNamespace(new String(next.row(), StandardCharsets.UTF_8),
                new String(next.value(), StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (open) {
            open = false;
            scanner.close();
        }
    }
}
