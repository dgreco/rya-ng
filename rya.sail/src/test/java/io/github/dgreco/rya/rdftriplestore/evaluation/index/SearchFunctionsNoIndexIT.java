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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;

/** Without indexes, text:matches and the geof: functions still work, by checking every candidate. */
class SearchFunctionsNoIndexIT extends IndexITBase {

    @Test
    void functionsWorkWithoutIndexes() {
        final AccumuloRdfConfiguration conf = newConf();
        conf.setTextIndex(false);
        conf.setGeoIndex(false);
        final SailRepository repo = open(conf, false);
        update(repo, """
                INSERT DATA {
                  <urn:a> rdfs:label "The quick brown fox" ; geo:asWKT "POINT(12.49 41.89)"^^geo:wktLiteral .
                  <urn:b> rdfs:label "Lazy dog" ; geo:asWKT "POINT(2.35 48.85)"^^geo:wktLiteral .
                }""");
        assertEquals(Set.of("urn:a"), subjects(assertAgrees(repo,
                "SELECT ?s WHERE { ?s rdfs:label ?l FILTER(text:matches(?l, \"QUICK fox\")) }", false)));
        assertEquals(Set.of("urn:b"), subjects(assertAgrees(repo,
                "SELECT ?s WHERE { ?s geo:asWKT ?w FILTER(geof:sfWithin(?w, "
                        + "\"POLYGON((0 45, 5 45, 5 50, 0 50, 0 45))\"^^geo:wktLiteral)) }", false)));
        assertEquals(Set.of("urn:a"), subjects(assertAgrees(repo,
                "SELECT ?s WHERE { ?s geo:asWKT ?w FILTER(geof:distance(?w, \"POINT(12.5 41.9)\"^^geo:wktLiteral, "
                        + "uom:metre) < 5000) }", false)));
        assertFalse(getClient().tableOperations().exists(conf.getTableLayoutStrategy().getText()));
        final Exception e = assertThrows(Exception.class, () -> run(repo,
                "SELECT ?s WHERE { ?s rdfs:label ?l FILTER(text:matches(?l, \"(fox\")) }", false, null));
        assertTrue(causedBy(e, MalformedQueryException.class), e.toString());
    }

    private static Set<String> subjects(final Map<Map<String, Value>, Integer> rows) {
        return rows.keySet().stream().map(r -> r.get("s").stringValue()).collect(Collectors.toSet());
    }
}
