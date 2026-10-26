package io.github.dgreco.rya.rdftriplestore.inference;

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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;

/**
 * A small directed graph of RDF values, used for the subClassOf and subPropertyOf
 * hierarchies. An edge {@code a -> b} means "a is a subclass/subproperty of b".
 * Safe for concurrent reads and writes.
 */
public class ValueGraph {
    private final Map<Value, Set<Value>> out = new ConcurrentHashMap<>();
    private final Map<Value, Set<Value>> in = new ConcurrentHashMap<>();

    public void addEdge(final Value from, final Value to) {
        out.computeIfAbsent(from, k -> ConcurrentHashMap.newKeySet()).add(to);
        in.computeIfAbsent(to, k -> ConcurrentHashMap.newKeySet()).add(from);
    }

    /**
     * Collects the IRIs reachable from {@code start}, following edges forwards
     * ({@code outgoing}) or backwards. Only IRIs are collected and walked through;
     * blank nodes end a path. The start node is included only if a cycle leads back to it.
     */
    public Set<IRI> reachable(final IRI start, final boolean outgoing, final boolean recursive) {
        final Set<IRI> found = new HashSet<>();
        if (start == null) {
            return found;
        }
        final Map<Value, Set<Value>> edges = outgoing ? out : in;
        final Deque<Value> todo = new ArrayDeque<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            final Value node = todo.poll();
            for (final Value next : edges.getOrDefault(node, Collections.emptySet())) {
                if (next instanceof IRI && found.add((IRI) next) && recursive) {
                    todo.add(next);
                }
            }
        }
        return found;
    }
}
