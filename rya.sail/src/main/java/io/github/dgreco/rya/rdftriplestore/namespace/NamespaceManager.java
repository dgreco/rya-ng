package io.github.dgreco.rya.rdftriplestore.namespace;

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

import java.time.Duration;

import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.api.persist.RyaNamespaceManager;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.sail.SailException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Namespace prefixes stored by the DAO, with a small in-memory cache for lookups by prefix.
 * Entries expire after a while so that changes made by other processes are picked up.
 */
public class NamespaceManager {
    private final RyaNamespaceManager<?> namespaceManager;
    private final Cache<String, String> cache = CacheBuilder.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public NamespaceManager(final RyaDAO<?> ryaDAO) {
        this.namespaceManager = ryaDAO.getNamespaceManager();
    }

    public void shutdown() {
        cache.invalidateAll();
    }

    public void addNamespace(final String pfx, final String namespace) {
        if (namespace.equals(getNamespace(pfx))) {
            return;
        }
        try {
            namespaceManager.addNamespace(pfx, namespace);
        } catch (final Exception e) {
            throw new SailException(e);
        }
        cache.put(pfx, namespace);
    }

    public String getNamespace(final String pfx) {
        final String cached = cache.getIfPresent(pfx);
        if (cached != null) {
            return cached;
        }
        try {
            final String namespace = namespaceManager.getNamespace(pfx);
            if (namespace != null) {
                cache.put(pfx, namespace);
            }
            return namespace;
        } catch (final Exception e) {
            throw new SailException(e);
        }
    }

    public void removeNamespace(final String pfx) {
        cache.invalidate(pfx);
        try {
            namespaceManager.removeNamespace(pfx);
        } catch (final Exception e) {
            throw new SailException(e);
        }
    }

    public CloseableIteration<? extends Namespace> iterateNamespace() {
        try {
            return namespaceManager.iterateNamespace();
        } catch (final Exception e) {
            throw new SailException(e);
        }
    }
}
