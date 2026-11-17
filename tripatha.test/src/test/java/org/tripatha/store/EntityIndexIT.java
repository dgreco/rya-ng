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
package org.tripatha.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tripatha.api.RdfCloudTripleStoreConstants;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.domain.RyaType;
import org.tripatha.api.persist.RyaDAOException;
import org.tripatha.store.index.EntityIndex;
import org.tripatha.store.index.IndexType;
import org.tripatha.store.index.IndexVerifier;
import org.tripatha.store.spi.Auths;
import org.tripatha.store.spi.CellScanner;
import org.tripatha.store.spi.KeyRange;
import org.tripatha.store.spi.ScanSpec;
import org.tripatha.test.StoreITBase;

/**
 * The entity index (SCALE-STUDY.md O23) on the selected backend: one posting per stored
 * statement, kept so by adds, deletes, graph drops, purges and rebuilds, and the storage version
 * that keeps older code from writing without postings.
 */
public class EntityIndexIT extends StoreITBase {
    private static final String NS = "urn:test#";
    private static final RyaIRI WORKS_FOR = new RyaIRI(NS + "worksFor");
    private static final RyaIRI AGE = new RyaIRI(NS + "age");
    private static final RyaIRI ACME = new RyaIRI(NS + "acme");
    private static final RyaIRI G1 = new RyaIRI(NS + "g1");
    private static final RyaIRI G2 = new RyaIRI(NS + "g2");

    private final List<RyaStoreDAO> daos = new ArrayList<>();

    @AfterEach
    public void tearDown() {
        daos.forEach(RyaStoreDAO::destroy);
    }

    @Test
    public void everyStatementHasExactlyOnePosting() throws Exception {
        grantAuthorizations("A");
        final RyaStoreDAO dao = dao(true, 4, false);
        dao.add(statements().iterator());
        assertClean();
        // The same triple in the default graph, g1 and g2, and under label A: four postings.
        assertEquals(4, postings("s0", AGE));
        assertEquals(statements().size(), postings(null, null));
        assertEquals(RdfCloudTripleStoreConstants.VERSION_WITH_ENTITY_INDEX, dao.getVersion());
    }

    @Test
    public void deletesAndGraphDropsRemoveExactlyTheirPostings() throws Exception {
        grantAuthorizations("A");
        final RyaStoreDAO dao = dao(true, 4, false);
        dao.add(statements().iterator());
        dao.delete(new RyaStatement(new RyaIRI(NS + "s1"), WORKS_FOR, ACME), conf(true, 4, false));
        assertClean();
        dao.dropGraph(conf(true, 4, false), G1);
        assertClean();
        assertEquals(3, postings("s0", AGE), "g2, the default graph and the labelled one are left");
        dao.dropGraph(conf(true, 4, false), (RyaIRI) null);
        assertClean();
        assertEquals(1, postings("s0", AGE), "g2 only");
        dao.purge(conf(true, 4, false));
        assertEquals(0, postings(null, null));
        assertEquals(RdfCloudTripleStoreConstants.VERSION_WITH_ENTITY_INDEX, dao.getVersion());
    }

    @Test
    public void enablingTheIndexOnAStoreBuildsItAndRaisesTheVersion() throws Exception {
        final RyaStoreDAO plain = dao(false, 4, false);
        plain.add(statements().iterator());
        assertEquals(RdfCloudTripleStoreConstants.VERSION_WITH_MARKERS, plain.getVersion());
        plain.destroy();
        daos.remove(plain);

        final RyaStoreDAO building = dao(true, 4, false);
        assertFalse(building.getIndexCatalog().isReady(IndexType.ENTITY), "the statements aren't indexed yet");
        assertEquals(RdfCloudTripleStoreConstants.VERSION_WITH_ENTITY_INDEX, building.getVersion(),
                "code that doesn't keep the index must not write here any more");
        final RyaStoreDAO rebuilt = dao(true, 4, true);
        assertTrue(rebuilt.getIndexCatalog().isReady(IndexType.ENTITY));
        assertClean();
    }

    @Test
    public void aReaderTakesTheShardCountTheIndexWasBuiltWith() {
        dao(true, 7, false);
        final StoreRdfConfiguration reader = new StoreRdfConfiguration();
        reader.setTablePrefix(getTablePrefix());
        reader.setEntityIndex(true);
        final RyaStoreDAO dao = new RyaStoreDAO(getStore(), reader);
        dao.init();
        daos.add(dao);
        assertEquals(7, dao.getIndexCatalog().entityIndex().shards());
        assertThrows(RyaDAOException.class, () -> dao(true, 8, false), "another count is another index: a rebuild");
    }

    @Test
    public void aGraphOrQualifierWithTheSeparatorIsRefused() {
        final RyaStoreDAO dao = dao(true, 4, false);
        assertThrows(RyaDAOException.class, () -> dao.add(new RyaStatement(new RyaIRI(NS + "s"), AGE,
                new RyaType(XSD.INT, "1"), new RyaIRI(NS + "g\u0001x"))));
        assertEquals(0, postings(null, null));
    }

    @Test
    public void aSubjectsPostingsAreAllInOneShard() {
        final EntityIndex index = new EntityIndex(16);
        final byte[] subject = (NS + "s0").getBytes(StandardCharsets.UTF_8);
        assertEquals(new String(index.shardRow(subject), StandardCharsets.UTF_8),
                new String(index.shardRow(subject.clone()), StandardCharsets.UTF_8));
        assertEquals(16, index.shardRows().size());
        assertEquals("00", new String(index.shardRows().get(0), StandardCharsets.UTF_8));
        assertEquals("15", new String(index.shardRows().get(15), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    /** s0..s9 work for Acme; each has an age in the default graph, s0 also in g1, g2 and under label A. */
    private static List<RyaStatement> statements() {
        final List<RyaStatement> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final RyaIRI s = new RyaIRI(NS + "s" + i);
            out.add(new RyaStatement(s, WORKS_FOR, ACME));
            out.add(new RyaStatement(s, AGE, new RyaType(XSD.INT, Integer.toString(20 + i))));
        }
        final RyaIRI s0 = new RyaIRI(NS + "s0");
        final RyaType age = new RyaType(XSD.INT, "20");
        out.add(new RyaStatement(s0, AGE, age, G1));
        out.add(new RyaStatement(s0, AGE, age, G2));
        out.add(new RyaStatement(s0, AGE, age, null, null, "A".getBytes(StandardCharsets.UTF_8), null, 1L));
        return out;
    }

    private StoreRdfConfiguration conf(final boolean entity, final int shards, final boolean rebuild) {
        final StoreRdfConfiguration conf = new StoreRdfConfiguration();
        conf.setTablePrefix(getTablePrefix());
        conf.setEntityIndex(entity);
        conf.setEntityIndexShards(shards);
        conf.setRebuildIndexes(rebuild);
        conf.setAuths("A");
        return conf;
    }

    private RyaStoreDAO dao(final boolean entity, final int shards, final boolean rebuild) {
        final RyaStoreDAO dao = new RyaStoreDAO(getStore(), conf(entity, shards, rebuild));
        dao.init();
        daos.add(dao);
        return dao;
    }

    private void assertClean() throws Exception {
        final IndexVerifier.Report report = IndexVerifier.verify(getStore(), conf(true, 4, false), IndexType.ENTITY);
        assertTrue(report.isClean(), report.toString());
    }

    /** The postings of a subject (null: any) under a predicate (null: any), every label. */
    private long postings(final String subject, final RyaIRI predicate) {
        long n = 0;
        try (CellScanner cells = getStore().scan(conf(true, 4, false).getTableLayoutStrategy().getEntity(), KeyRange.all(),
                ScanSpec.of(Auths.ALL_HELD))) {
            while (cells.hasNext()) {
                final var cell = cells.next();
                final String s = new String(EntityIndex.subject(cell.qualifier()), StandardCharsets.UTF_8);
                final String term = new String(cell.family(), StandardCharsets.UTF_8);
                if ((subject == null || s.equals(NS + subject)) && (predicate == null || term.startsWith(predicate.getData() + "\u0001"))) {
                    n++;
                }
            }
        }
        return n;
    }
}
