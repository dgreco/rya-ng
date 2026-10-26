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
package io.github.dgreco.rya.accumulo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.accumulo.core.client.BatchWriter;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.index.IndexKeys;
import io.github.dgreco.rya.accumulo.index.IndexQuery;
import io.github.dgreco.rya.accumulo.index.IndexSearcher;
import io.github.dgreco.rya.accumulo.index.geo.GeoRegion;
import io.github.dgreco.rya.accumulo.index.geo.GeoSupport;
import io.github.dgreco.rya.accumulo.index.text.TextAnalyzer;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates;
import io.github.dgreco.rya.accumulo.index.text.TextQuery;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.test.accumulo.AccumuloITBase;

/** Index lookups return exactly the visible stored statements their candidates point to. */
class IndexSearcherIT extends AccumuloITBase {
    private static final RyaIRI LABEL = new RyaIRI(RDFS.LABEL.stringValue());
    private static final RyaIRI COMMENT = new RyaIRI(RDFS.COMMENT.stringValue());
    private static final RyaIRI AS_WKT = new RyaIRI(GEO.AS_WKT.stringValue());
    /** More documents than an operand may hold in memory, so the large-operand paths run. */
    private static final int MANY = 1500;

    private AccumuloRdfConfiguration conf;
    private AccumuloRyaDAO dao;
    private IndexSearcher searcher;
    private final List<RyaStatement> stored = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        grantAuthorizations("A", "B");
        conf = new AccumuloRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        conf.setTextIndex(true);
        conf.setGeoIndex(true);
        conf.setAuths("A", "B");
        dao = new AccumuloRyaDAO(getClient(), conf);
        dao.init();
        searcher = new IndexSearcher(dao);
        for (int i = 0; i < MANY; i++) {
            final String text = "common w" + (i % 7) + " item" + i + (i % 100 == 0 ? " rare" : "")
                    + (i % 250 == 0 ? " commonplace" : "");
            add(new RyaStatement(new RyaIRI("urn:d" + i), LABEL, new RyaType(text)));
        }
        add(new RyaStatement(new RyaIRI("urn:c1"), COMMENT, new RyaType("rare comment"), new RyaIRI("urn:g1")));
        add(labelled("urn:secret", "rare secret", "A"));
        add(labelled("urn:twin", "rare twin", "A"));
        add(labelled("urn:twin", "rare twin", "B"));
        add(new RyaStatement(new RyaIRI("urn:rome"), AS_WKT, new RyaType(GEO.WKT_LITERAL, "POINT(12.49 41.89)")));
        add(new RyaStatement(new RyaIRI("urn:paris"), AS_WKT, new RyaType(GEO.WKT_LITERAL, "POINT(2.35 48.85)")));
        add(new RyaStatement(new RyaIRI("urn:lazio"), AS_WKT,
                new RyaType(GEO.WKT_LITERAL, "POLYGON((11.4 40.8, 14.0 40.8, 14.0 42.9, 11.4 42.9, 11.4 40.8))")));
        dao.flush();
    }

    @AfterEach
    void tearDown() {
        dao.destroy();
    }

    private void add(final RyaStatement st) {
        dao.add(st);
        stored.add(st);
    }

    private static RyaStatement labelled(final String subject, final String text, final String label) {
        return new RyaStatement(new RyaIRI(subject), LABEL, new RyaType(text), null, null,
                label.getBytes(StandardCharsets.UTF_8), null);
    }

    private List<RyaStatement> search(final IndexQuery q, final String predicate, final Collection<String> graphs,
            final AccumuloRdfConfiguration c) {
        final List<RyaStatement> out = new ArrayList<>();
        try (CloseableIteration<RyaStatement> it = searcher.search(q, predicate, graphs, c)) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }

    private List<RyaStatement> text(final String query) {
        return search(new IndexQuery.Text(TextCandidates.of(TextQuery.parse(query)).orElseThrow()), null, null, conf);
    }

    /** Subject → count of the stored text statements whose words satisfy the candidates. */
    private Map<String, Integer> expected(final String query, final String predicate) {
        final TextCandidates candidates = TextCandidates.of(TextQuery.parse(query)).orElseThrow();
        final Map<String, Integer> out = new TreeMap<>();
        for (final RyaStatement st : stored) {
            if (st.getObject().getDataType().stringValue().endsWith("#string")
                    && (predicate == null || predicate.equals(st.getPredicate().getData()))
                    && candidates.satisfiedBy(TextAnalyzer.terms(st.getObject().getData()))) {
                out.merge(st.getSubject().getData(), 1, Integer::sum);
            }
        }
        return out;
    }

    private static Map<String, Integer> bySubject(final List<RyaStatement> results) {
        final Map<String, Integer> out = new TreeMap<>();
        for (final RyaStatement st : results) {
            out.merge(st.getSubject().getData(), 1, Integer::sum);
        }
        return out;
    }

    @Test
    void textQueriesReturnExactlyTheirCandidates() {
        for (final String q : List.of("common", "rare", "common rare", "w3 common", "rare OR w5", "item1*",
                "common (w1 OR w2) rare", "twin", "comment")) {
            assertEquals(expected(q, null), bySubject(text(q)), q);
        }
    }

    @Test
    void aLargePrefixInAnAndIsLeftToTheFilter() {
        // comm* matches every document: it cannot narrow "rare" down, so every rare document comes back.
        final Map<String, Integer> results = bySubject(text("rare comm*"));
        assertTrue(results.keySet().containsAll(expected("rare comm*", null).keySet()));
        assertEquals(expected("rare", null).keySet(), results.keySet());
    }

    @Test
    void predicateAndGraphRestrictions() {
        final IndexQuery rare = new IndexQuery.Text(new TextCandidates.Word("rare"));
        assertEquals(expected("rare", COMMENT.getData()), bySubject(search(rare, COMMENT.getData(), null, conf)));
        assertEquals(Set.of("urn:c1"), bySubject(search(rare, null, List.of("urn:g1"), conf)).keySet());
        assertTrue(bySubject(search(rare, null, List.of(""), conf)).keySet().stream().noneMatch("urn:c1"::equals));
        assertEquals(0, search(rare, null, List.of(), conf).size());
    }

    @Test
    void visibilityIsEnforcedAndEachVisibleCellCountsOnce() {
        final AccumuloRdfConfiguration onlyB = new AccumuloRdfConfiguration(conf);
        onlyB.setAuths("B");
        final Map<String, Integer> asB = bySubject(search(new IndexQuery.Text(new TextCandidates.Word("rare")), null, null, onlyB));
        assertTrue(!asB.containsKey("urn:secret"), "A-only statement leaked: " + asB);
        assertEquals(1, asB.get("urn:twin"));
        assertEquals(2, bySubject(text("twin")).get("urn:twin"), "one row per visible cell, as a plain lookup");
    }

    @Test
    void anOrphanIndexEntryReturnsNothing() throws Exception {
        final RyaStatement ghost = new RyaStatement(new RyaIRI("urn:ghost"), LABEL, new RyaType("phantom"));
        try (BatchWriter writer = getClient().createBatchWriter(conf.getTableLayoutStrategy().getText())) {
            writer.addMutations(IndexKeys.mutations(List.of("phantom"), ghost, false));
        }
        assertEquals(0, text("phantom").size());
    }

    @Test
    void geoRegionsFindPointsAndPolygons() {
        final GeoRegion aroundRome = GeoRegion.intersecting(
                GeoSupport.parse("POLYGON((12.3 41.7, 12.7 41.7, 12.7 42.1, 12.3 42.1, 12.3 41.7))").orElseThrow());
        assertEquals(Set.of("urn:rome", "urn:lazio"),
                bySubject(search(new IndexQuery.Geo(aroundRome), AS_WKT.getData(), null, conf)).keySet());
        final GeoRegion near = GeoRegion.pointsWithin(
                GeoSupport.context().getShapeFactory().pointXY(2.3, 48.8), 20_000,
                org.eclipse.rdf4j.model.vocabulary.GEOF.UOM_METRE).orElseThrow();
        assertEquals(Set.of("urn:paris"), bySubject(search(new IndexQuery.Geo(near), null, null, conf)).keySet());
    }

    @Test
    void everySearchIsCounted() {
        final long before = IndexSearcher.getSearchCount();
        text("rare");
        assertEquals(before + 1, IndexSearcher.getSearchCount());
    }
}
