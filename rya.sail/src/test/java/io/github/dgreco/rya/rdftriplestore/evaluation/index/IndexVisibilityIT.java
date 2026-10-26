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
package io.github.dgreco.rya.rdftriplestore.evaluation.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.persist.RyaDAO;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStore;

/** Index searches never return a statement the reader may not see. */
class IndexVisibilityIT extends IndexITBase {
    private static final String TEXT = "SELECT ?d WHERE { ?d rdfs:label ?l FILTER(text:matches(?l, \"fox\")) }";
    private static final String GEO_QUERY = "SELECT ?d WHERE { ?d geo:asWKT ?w FILTER(geof:sfWithin(?w, "
            + "\"POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))\"^^geo:wktLiteral)) }";

    @BeforeEach
    void auths() throws Exception {
        grantAuthorizations("A", "B");
    }

    private SailRepository store(final String... auths) {
        final AccumuloRdfConfiguration conf = indexedConf();
        conf.setAuths(auths);
        final SailRepository repo = open(conf, false);
        final RyaDAO<?> dao = ((RdfCloudTripleStore<?>) repo.getSail()).getRyaDAO();
        add(dao, "urn:secret", RDFS.LABEL.stringValue(), new RyaType("secret fox"), "A");
        add(dao, "urn:public", RDFS.LABEL.stringValue(), new RyaType("public fox"), null);
        add(dao, "urn:shared", RDFS.LABEL.stringValue(), new RyaType("shared fox"), "A");
        add(dao, "urn:shared", RDFS.LABEL.stringValue(), new RyaType("shared fox"), "B");
        add(dao, "urn:p-secret", GEO.AS_WKT.stringValue(), new RyaType(GEO.WKT_LITERAL, "POINT(1 1)"), "A");
        add(dao, "urn:p-public", GEO.AS_WKT.stringValue(), new RyaType(GEO.WKT_LITERAL, "POINT(2 2)"), null);
        dao.flush();
        return repo;
    }

    private static void add(final RyaDAO<?> dao, final String s, final String p, final RyaType o, final String label) {
        dao.add(new RyaStatement(new RyaIRI(s), new RyaIRI(p), o, null, null,
                label == null ? null : label.getBytes(StandardCharsets.UTF_8), null));
    }

    private static Map<String, Integer> docs(final Map<Map<String, Value>, Integer> rows) {
        final Map<String, Integer> out = new HashMap<>();
        rows.forEach((row, n) -> out.merge(row.get("d").stringValue(), n, Integer::sum));
        return out;
    }

    @Test
    void perQueryAuthorizations() {
        final SailRepository repo = store("A", "B");
        assertEquals(Map.of("urn:public", 1, "urn:shared", 1), docs(assertAgrees(repo, TEXT, true, "B")));
        assertEquals(Map.of("urn:public", 1, "urn:shared", 1, "urn:secret", 1), docs(assertAgrees(repo, TEXT, true, "A")));
        assertEquals(Map.of("urn:public", 1, "urn:shared", 2, "urn:secret", 1),
                docs(assertAgrees(repo, TEXT, true, "A,B")), "one row per visible cell");
        assertEquals(Map.of("urn:p-public", 1), docs(assertAgrees(repo, GEO_QUERY, true, "B")));
        assertEquals(Map.of("urn:p-public", 1, "urn:p-secret", 1), docs(assertAgrees(repo, GEO_QUERY, true, "A")));
    }

    @Test
    void storeWideAuthorizations() {
        final SailRepository repo = store("B");
        assertEquals(Map.of("urn:public", 1, "urn:shared", 1), docs(assertAgrees(repo, TEXT, true)));
        assertEquals(Map.of("urn:p-public", 1), docs(assertAgrees(repo, GEO_QUERY, true)));
    }
}
