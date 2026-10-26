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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.index.geo.GeoCells;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;

class SecondaryIndexTest {
    private static final RyaIRI S = new RyaIRI("urn:s");

    private static RyaStatement st(final String predicate, final RyaType object) {
        return new RyaStatement(S, new RyaIRI(predicate), object);
    }

    @Test
    void textIndexCoversStringAndLanguageLiteralsOnly() {
        final TextIndex all = new TextIndex(List.of());
        assertEquals(Set.of("hello", "world"), Set.copyOf(all.terms(st("urn:p", new RyaType("Hello, world")))));
        assertEquals(Set.of("ciao"), Set.copyOf(all.terms(st("urn:p", new RyaType(RDF.LANGSTRING, "Ciao", "it")))));
        assertTrue(all.terms(st("urn:p", new RyaType(XSD.INTEGER, "42"))).isEmpty());
        assertTrue(all.terms(st("urn:p", new RyaType(XSD.TOKEN, "word"))).isEmpty());
        assertTrue(all.terms(st("urn:p", new RyaIRI("urn:hello"))).isEmpty());
    }

    @Test
    void textIndexHonoursThePredicateList() {
        final TextIndex labels = new TextIndex(List.of(RDFS.LABEL.stringValue()));
        assertEquals(Set.of("x"), Set.copyOf(labels.terms(st(RDFS.LABEL.stringValue(), new RyaType("x")))));
        assertTrue(labels.terms(st(RDFS.COMMENT.stringValue(), new RyaType("x"))).isEmpty());
        assertNotEquals(labels.definition(), new TextIndex(List.of()).definition());
        assertEquals(new TextIndex(List.of("urn:b", "urn:a")).definition(), new TextIndex(List.of("urn:a", "urn:b")).definition());
    }

    @Test
    void geoIndexCoversValidWktOnly() {
        final GeoIndex geo = new GeoIndex();
        assertEquals(1, geo.terms(st("urn:p", new RyaType(GEO.WKT_LITERAL, "POINT(1 2)"))).size());
        assertEquals(GeoCells.MAX_LEVEL, geo.terms(st("urn:p", new RyaType(GEO.WKT_LITERAL, "POINT(1 2)"))).iterator().next().length());
        assertTrue(geo.terms(st("urn:p", new RyaType(GEO.WKT_LITERAL, "POLYGON((0 0, 1 0, 1 1, 0 0))"))).size() > 1);
        assertTrue(geo.terms(st("urn:p", new RyaType(GEO.WKT_LITERAL, "NOT WKT"))).isEmpty());
        assertTrue(geo.terms(st("urn:p", new RyaType("POINT(1 2)"))).isEmpty());
    }
}
