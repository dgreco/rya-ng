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
package org.tripatha.spark;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;

import org.tripatha.loader.LoaderOptions;
import org.tripatha.store.EvalStatsMutations.CounterPolicy;
import org.tripatha.store.StoreRdfConfiguration;

/**
 * What every task of a build needs, fixed by the driver and sent with the tasks: the loader's
 * options (each task parses them again, so its store configuration and dataset are the driver's),
 * the layout of the files, and how to write them. No connection to Accumulo: a task only writes
 * files.
 */
final class JobSpec implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The loader's arguments, command first; a dataset size given in triples is given in entities. */
    String[] loaderArgs;
    Shuffle.Layout layout;
    /** Every table of the store's prefix that exists: a task's DAO checks its index tables against its settings. */
    java.util.HashSet<String> existing;
    /** The timestamp of the job's counters, graph markers and file statements. */
    long jobTime;
    /** How each write call writes counters (the generator's {@code --stats-ones}; every counter for files). */
    CounterPolicy addPolicy;
    /** Which final totals stay out of the files ({@code SKIP_SUBJECT_ONES} only when the totals are the store's). */
    CounterPolicy leftOutPolicy;
    /** Every file statement's label expression, or null. */
    byte[] visibility;
    long maxBadLines;
    /** Each table's configuration, for its RFiles. */
    HashMap<String, HashMap<String, String>> tableProperties;
    /** The driver's Hadoop configuration: the work directory's file system settings ({@code fs.s3a.*}). */
    HashMap<String, String> hadoop;
    /** Where the files go: one directory per table under it. */
    String filesDir;
    /** The generator's entities, [first, last), in blocks. */
    long first;
    long last;
    int blockSize;

    private transient LoaderOptions options;

    synchronized LoaderOptions options() {
        if (options == null) {
            options = LoaderOptions.parse(loaderArgs, Map.of());
        }
        return options;
    }

    StoreRdfConfiguration conf() {
        return options().rdfConf();
    }

    Configuration hadoopConf() {
        final Configuration c = new Configuration(false);
        hadoop.forEach(c::set);
        return c;
    }

    static HashMap<String, String> entries(final Configuration conf) {
        final HashMap<String, String> m = new HashMap<>();
        for (final Map.Entry<String, String> e : conf) {
            m.put(e.getKey(), e.getValue());
        }
        return m;
    }
}
