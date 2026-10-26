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
package io.github.dgreco.rya.accumulo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.hadoop.io.Text;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;

class IndexKeysTest {
    private static final RyaIRI S = new RyaIRI("urn:s");
    private static final RyaIRI P = new RyaIRI("urn:p");

    @Test
    void hashSeparatesLanguagesAndDatatypes() {
        final String plain = IndexKeys.objectHash(new RyaType(XSD.STRING, "chat"));
        final String english = IndexKeys.objectHash(new RyaType(RDF.LANGSTRING, "chat", "en"));
        final String french = IndexKeys.objectHash(new RyaType(RDF.LANGSTRING, "chat", "fr"));
        final String token = IndexKeys.objectHash(new RyaType(XSD.TOKEN, "chat"));
        assertEquals(16, plain.length());
        assertTrue(plain.matches("[0-9a-f]{16}"));
        assertEquals(4, List.of(plain, english, french, token).stream().distinct().count());
        assertEquals(plain, IndexKeys.objectHash(new RyaType(XSD.STRING, "chat")));
    }

    @Test
    void rowsParseBackAndCarryLabelAndTimestamp() {
        final RyaStatement st = new RyaStatement(S, P, new RyaType("hello world"), new RyaIRI("urn:g"), "q1",
                "A&B".getBytes(StandardCharsets.UTF_8), null, 1234L);
        final List<Mutation> puts = IndexKeys.mutations(List.of("hello", "world"), st, false);
        assertEquals(2, puts.size());
        final Mutation m = puts.get(0);
        final ColumnUpdate u = m.getUpdates().get(0);
        assertEquals("urn:g", new String(u.getColumnFamily(), StandardCharsets.UTF_8));
        assertEquals("q1", new String(u.getColumnQualifier(), StandardCharsets.UTF_8));
        assertEquals("A&B", new String(u.getColumnVisibility(), StandardCharsets.UTF_8));
        assertEquals(1234L, u.getTimestamp());
        assertFalse(u.isDeleted());
        final Key key = new Key(new Text(m.getRow()), new Text(u.getColumnFamily()), new Text(u.getColumnQualifier()));
        assertEquals("hello", IndexKeys.term(key));
        assertEquals(IndexKeys.docKey(st), IndexKeys.docKey(key));

        final ColumnUpdate d = IndexKeys.mutations(List.of("hello"), st, true).get(0).getUpdates().get(0);
        assertTrue(d.isDeleted());
        assertEquals(1234L, d.getTimestamp());
    }

    @Test
    void rangesSelectTheRightRows() {
        final DocKey doc = IndexKeys.docKey(new RyaStatement(S, P, new RyaType("x")));
        final Key exact = new Key(new Text(IndexKeys.row("alice", doc)));
        final Key longer = new Key(new Text(IndexKeys.row("alicedoe", doc)));
        assertTrue(IndexKeys.termRange("alice", null).contains(exact));
        assertTrue(IndexKeys.termRange("alice", "urn:p").contains(exact));
        assertFalse(IndexKeys.termRange("alice", "urn:other").contains(exact));
        assertFalse(IndexKeys.termRange("alice", null).contains(longer));
        assertTrue(IndexKeys.prefixRange("ali").contains(exact));
        assertTrue(IndexKeys.prefixRange("ali").contains(longer));
        assertTrue(IndexKeys.entryRange("alice", doc).contains(
                new Key(new Text(IndexKeys.row("alice", doc)), new Text(""), new Text(""), new Text("A"))));
        assertNotEquals(IndexKeys.entryRange("alice", doc), IndexKeys.entryRange("bob", doc));
    }

    @Test
    void stateRecordRoundTrips() {
        final IndexState state = new IndexState(IndexState.Status.BUILDING, "text/1;max=64;preds=");
        assertEquals(state, IndexState.decode(state.encode()));
    }
}
