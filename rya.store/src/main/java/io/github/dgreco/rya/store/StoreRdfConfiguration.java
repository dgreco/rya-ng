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
package io.github.dgreco.rya.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConfiguration;
import io.github.dgreco.rya.store.spi.Auths;
import io.github.dgreco.rya.store.spi.ServerIterator;

/**
 * Settings of a rya-ng store, whatever its backend. Backend-only settings live in subclasses
 * such as {@code StoreRdfConfiguration}. The keys keep their historical {@code ac.} names.
 */
public class StoreRdfConfiguration extends RdfCloudTripleStoreConfiguration {

    public static final String MAXRANGES_SCANNER = "ac.query.maxranges";

    public static final String CONF_FLUSH_EACH_UPDATE = "ac.dao.flush";

    /** When true (the default), every write also updates the cardinality counters in the eval table. */
    public static final String CONF_MAINTAIN_STATS = "ac.stats.maintain";

    public static final String ITERATOR_SETTINGS_SIZE = "ac.iterators.size";
    public static final String ITERATOR_SETTINGS_BASE = "ac.iterators.%d.";
    public static final String ITERATOR_SETTINGS_NAME = ITERATOR_SETTINGS_BASE + "name";
    public static final String ITERATOR_SETTINGS_CLASS = ITERATOR_SETTINGS_BASE + "iteratorClass";
    public static final String ITERATOR_SETTINGS_PRIORITY = ITERATOR_SETTINGS_BASE + "priority";
    public static final String ITERATOR_SETTINGS_OPTIONS_SIZE = ITERATOR_SETTINGS_BASE + "optionsSize";
    public static final String ITERATOR_SETTINGS_OPTIONS_KEY = ITERATOR_SETTINGS_BASE + "option.%d.name";
    public static final String ITERATOR_SETTINGS_OPTIONS_VALUE = ITERATOR_SETTINGS_BASE + "option.%d.value";

    public StoreRdfConfiguration() {
        super();
    }

    public StoreRdfConfiguration(final Configuration other) {
        super(other);
    }

    @Override
    public StoreRdfConfiguration clone() {
        return new StoreRdfConfiguration(this);
    }

    /** The configured authorizations as a read's labels: none means unlabelled cells only. */
    public Auths getStoreAuths() {
        return Auths.of(getAuths());
    }

    public void setMaxRangesForScanner(final Integer max) {
        setInt(MAXRANGES_SCANNER, max);
    }

    public Integer getMaxRangesForScanner() {
        return getInt(MAXRANGES_SCANNER, 2);
    }

    public boolean flushEachUpdate() {
        return getBoolean(CONF_FLUSH_EACH_UPDATE, true);
    }

    public void setFlush(final boolean flush) {
        setBoolean(CONF_FLUSH_EACH_UPDATE, flush);
    }

    /** Stores extra server-side scan iterators (Accumulo only). */
    public void setServerIterators(final List<ServerIterator> iterators) {
        set(ITERATOR_SETTINGS_SIZE, Integer.toString(iterators.size()));
        int i = 0;
        for (final ServerIterator it : iterators) {
            set(String.format(ITERATOR_SETTINGS_NAME, i), it.name());
            set(String.format(ITERATOR_SETTINGS_CLASS, i), it.className());
            set(String.format(ITERATOR_SETTINGS_PRIORITY, i), Integer.toString(it.priority()));
            set(String.format(ITERATOR_SETTINGS_OPTIONS_SIZE, i), Integer.toString(it.options().size()));
            int j = 0;
            for (final Map.Entry<String, String> o : it.options().entrySet()) {
                set(String.format(ITERATOR_SETTINGS_OPTIONS_KEY, i, j), o.getKey());
                set(String.format(ITERATOR_SETTINGS_OPTIONS_VALUE, i, j), o.getValue());
                j++;
            }
            i++;
        }
    }

    /** The extra server-side scan iterators every read adds (Accumulo only); usually none. */
    public List<ServerIterator> getServerIterators() {
        final int size = Integer.parseInt(get(ITERATOR_SETTINGS_SIZE, "0"));
        final List<ServerIterator> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final String name = get(String.format(ITERATOR_SETTINGS_NAME, i));
            final String className = get(String.format(ITERATOR_SETTINGS_CLASS, i));
            final int priority = Integer.parseInt(get(String.format(ITERATOR_SETTINGS_PRIORITY, i)));
            final int optionsSize = Integer.parseInt(get(String.format(ITERATOR_SETTINGS_OPTIONS_SIZE, i)));
            final Map<String, String> options = new HashMap<>(optionsSize);
            for (int j = 0; j < optionsSize; j++) {
                options.put(get(String.format(ITERATOR_SETTINGS_OPTIONS_KEY, i, j)),
                        get(String.format(ITERATOR_SETTINGS_OPTIONS_VALUE, i, j)));
            }
            out.add(new ServerIterator(priority, name, className, options));
        }
        return out;
    }

    /**
     * @return true if writes keep the eval-table cardinality counters up to date. Default true.
     */
    public boolean isMaintainStats() {
        return getBoolean(CONF_MAINTAIN_STATS, true);
    }

    public void setMaintainStats(final boolean maintain) {
        setBoolean(CONF_MAINTAIN_STATS, maintain);
    }
}
