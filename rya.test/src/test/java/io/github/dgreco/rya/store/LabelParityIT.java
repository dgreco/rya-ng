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
package io.github.dgreco.rya.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.test.StoreITBase;

/**
 * Labels behave as on Accumulo on every backend, including where HBase's own rules differ: a key
 * under two labels stays two cells, no labels means unlabelled cells only, a delete removes one
 * label's copy, and expressions mixing and/or keep their meaning.
 */
class LabelParityIT extends StoreITBase {
    private static final RyaIRI S = new RyaIRI("urn:s");
    private static final RyaIRI P = new RyaIRI("urn:p");
    private static final RyaIRI G = new RyaIRI("urn:g");

    private RyaStoreDAO dao;
    private StoreRdfConfiguration conf;

    @BeforeEach
    void setUp() {
        grantAuthorizations("A", "B", "C");
        conf = new StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        dao = new RyaStoreDAO(getStore(), conf);
        dao.init();
    }

    @AfterEach
    void tearDown() {
        dao.destroy();
    }

    private static RyaStatement st(final String object, final String label, final long ts) {
        return new RyaStatement(S, P, new RyaType(object), G, null, label == null ? null : label.getBytes(StandardCharsets.UTF_8),
                null, ts);
    }

    private Set<String> read(final String... auths) {
        final StoreRdfConfiguration c = conf.clone();
        c.setAuths(auths);
        final Set<String> out = new HashSet<>();
        try (CloseableIteration<RyaStatement> it = dao.getQueryEngine().query(new RyaStatement(S, P, null), c)) {
            while (it.hasNext()) {
                final RyaStatement st = it.next();
                final byte[] cv = st.getColumnVisibility();
                out.add(st.getObject().getData() + "/" + (cv == null ? "" : new String(cv, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    @Test
    void oneStatementUnderTwoLabelsStaysTwoCells() {
        dao.add(List.of(st("x", "A", 5), st("x", "B", 5), st("open", null, 5)).iterator());
        assertEquals(Set.of("open/"), read());
        assertEquals(Set.of("x/A", "open/"), read("A"));
        assertEquals(Set.of("x/B", "open/"), read("B"));
        assertEquals(Set.of("x/A", "x/B", "open/"), read("A", "B"));
    }

    @Test
    void aDeleteRemovesOnlyTheCopiesItCanSee() {
        dao.add(List.of(st("x", "A", 5), st("x", "B", 5)).iterator());
        final StoreRdfConfiguration onlyA = conf.clone();
        onlyA.setAuths("A");
        dao.delete(new RyaStatement(S, P, new RyaType("x")), onlyA);
        assertEquals(Set.of("x/B"), read("A", "B"));
    }

    @Test
    void mixedExpressionsKeepTheirMeaning() {
        dao.add(List.of(st("ab-or-c", "(A&B)|C", 5), st("a-and-bc", "A&(B|C)", 5)).iterator());
        assertEquals(Set.of(), read("A"));
        assertEquals(Set.of("ab-or-c/(A&B)|C", "a-and-bc/A&(B|C)"), read("A", "B"));
        assertEquals(Set.of("ab-or-c/(A&B)|C"), read("C"));
        assertEquals(Set.of("a-and-bc/A&(B|C)", "ab-or-c/(A&B)|C"), read("A", "C"));
    }

    @Test
    void graphMarkersFollowLabels() {
        dao.add(List.of(st("x", "A", 5)).iterator());
        final StoreRdfConfiguration asB = conf.clone();
        asB.setAuths("B");
        final StoreRdfConfiguration asA = conf.clone();
        asA.setAuths("A");
        assertEquals(Set.of(), contexts(asB));
        assertEquals(Set.of(G), contexts(asA));
    }

    private Set<RyaIRI> contexts(final StoreRdfConfiguration c) {
        final Set<RyaIRI> out = new HashSet<>();
        try (CloseableIteration<RyaIRI> it = dao.getContextIDs(c)) {
            it.forEachRemaining(out::add);
        }
        return out;
    }
}
