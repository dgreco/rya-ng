/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.dgreco.rya.test.accumulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.junit.jupiter.api.Test;

public class AccumuloITBaseIT extends AccumuloITBase {

    @Test
    public void writesAndScansWithVisibility() throws Exception {
        final String table = getTablePrefix() + "t";
        getClient().tableOperations().create(table);
        try (BatchWriter bw = getClient().createBatchWriter(table)) {
            final Mutation m = new Mutation("row");
            m.put("cf", "open", "v1");
            m.put("cf", "secret", new ColumnVisibility("A"), "v2");
            bw.addMutation(m);
        }

        assertEquals(1, count(table, Authorizations.EMPTY));
        grantAuthorizations("A");
        assertTrue(getUserAuthorizations().contains("A"));
        assertEquals(2, count(table, new Authorizations("A")));
    }

    private int count(final String table, final Authorizations auths) throws Exception {
        int n = 0;
        try (Scanner s = getClient().createScanner(table, auths)) {
            for (final Map.Entry<Key, Value> ignored : s) {
                n++;
            }
        }
        return n;
    }
}
