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
package io.github.dgreco.rya.store.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.store.spi.Cell;
import io.github.dgreco.rya.store.spi.KeyRange;
import io.github.dgreco.rya.store.spi.RowMutation;

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
        final List<RowMutation> puts = IndexKeys.mutations(List.of("hello", "world"), st, false);
        assertEquals(2, puts.size());
        final RowMutation m = puts.get(0);
        final RowMutation.Update u = m.updates().get(0);
        assertEquals("urn:g", new String(u.family(), StandardCharsets.UTF_8));
        assertEquals("q1", new String(u.qualifier(), StandardCharsets.UTF_8));
        assertEquals("A&B", new String(u.visibility(), StandardCharsets.UTF_8));
        assertEquals(1234L, u.timestamp());
        assertEquals(RowMutation.Op.PUT, u.op());
        final Cell key = new Cell(m.row(), u.family(), u.qualifier(), new byte[0], 0, new byte[0]);
        assertEquals("hello", IndexKeys.term(key));
        assertEquals(IndexKeys.docKey(st), IndexKeys.docKey(key));

        final RowMutation.Update d = IndexKeys.mutations(List.of("hello"), st, true).get(0).updates().get(0);
        assertEquals(RowMutation.Op.DELETE, d.op());
        assertEquals(1234L, d.timestamp());
    }

    @Test
    void rangesSelectTheRightRows() {
        final DocKey doc = IndexKeys.docKey(new RyaStatement(S, P, new RyaType("x")));
        final byte[] exact = IndexKeys.row("alice", doc).getBytes(StandardCharsets.UTF_8);
        final byte[] longer = IndexKeys.row("alicedoe", doc).getBytes(StandardCharsets.UTF_8);
        assertTrue(IndexKeys.termRange("alice", null).containsRow(exact));
        assertTrue(IndexKeys.termRange("alice", "urn:p").containsRow(exact));
        assertFalse(IndexKeys.termRange("alice", "urn:other").containsRow(exact));
        assertFalse(IndexKeys.termRange("alice", null).containsRow(longer));
        assertTrue(IndexKeys.prefixRange("ali").containsRow(exact));
        assertTrue(IndexKeys.prefixRange("ali").containsRow(longer));
        final KeyRange entry = IndexKeys.entryRange("alice", doc);
        assertEquals(KeyRange.Kind.CELL, entry.kind());
        assertTrue(entry.containsRow(exact));
        assertEquals(0, entry.family().length);
        assertEquals(0, entry.qualifier().length);
        assertNotEquals(IndexKeys.entryRange("alice", doc), IndexKeys.entryRange("bob", doc));
    }

    @Test
    void stateRecordRoundTrips() {
        final IndexState state = new IndexState(IndexState.Status.BUILDING, "text/1;max=64;preds=");
        assertEquals(state, IndexState.decode(state.encode()));
    }
}
