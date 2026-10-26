package io.github.dgreco.rya.accumulo;

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
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.impl.SimpleNamespace;

/**
 * Iterates the namespace rows of the ns table: row = prefix, value = namespace IRI.
 */
public class AccumuloNamespaceTableIterator implements CloseableIteration<Namespace> {
    private final ScannerBase scanner;
    private final Iterator<Entry<Key, Value>> result;
    private boolean open = true;

    public AccumuloNamespaceTableIterator(final ScannerBase scanner) {
        this.scanner = scanner;
        this.result = scanner.iterator();
    }

    @Override
    public boolean hasNext() {
        return open && result.hasNext();
    }

    @Override
    public Namespace next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        final Entry<Key, Value> next = result.next();
        return new SimpleNamespace(next.getKey().getRow().toString(),
                new String(next.getValue().get(), StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (open) {
            open = false;
            scanner.close();
        }
    }
}
