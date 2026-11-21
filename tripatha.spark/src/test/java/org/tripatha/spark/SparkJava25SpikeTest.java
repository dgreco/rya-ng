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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.junit.jupiter.api.Test;

import org.tripatha.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.resolver.RyaTripleContext;
import org.tripatha.api.resolver.triple.TripleRow;

/**
 * The spike of SCALE-STUDY.md D5 and M6: Spark 4.2, in local mode on Java 25 with Hadoop 3.5, runs
 * Tripatha's own encoding in its tasks.
 */
class SparkJava25SpikeTest {
    @Test
    void sparkTasksRunTripathaCodeOnJava25() {
        final SparkConf conf = new SparkConf().setMaster("local[2]").setAppName("tripatha-spike").set("spark.ui.enabled", "false");
        try (JavaSparkContext sc = new JavaSparkContext(conf)) {
            final List<Integer> ids = IntStream.range(0, 1000).boxed().toList();
            final long rows = sc.parallelize(ids, 4).mapPartitions(it -> {
                final RyaTripleContext context = RyaTripleContext.getInstance(new org.tripatha.store.StoreRdfConfiguration());
                final List<Integer> lengths = new ArrayList<>();
                while (it.hasNext()) {
                    final int i = it.next();
                    final RyaStatement st = new RyaStatement(new RyaIRI("urn:s" + i), new RyaIRI("urn:p"), new RyaType("v" + i));
                    final TripleRow row = context.serializeTriple(st).get(TABLE_LAYOUT.SPO);
                    lengths.add(row.getRow().length);
                }
                return lengths.iterator();
            }).count();
            assertEquals(1000, rows);
            assertEquals("25", System.getProperty("java.specification.version"));
        }
    }
}
