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
package io.github.dgreco.rya.loader.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import io.github.dgreco.rya.store.index.geo.GeoCells;
import io.github.dgreco.rya.store.index.geo.GeoSupport;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaTripleTerm;
import io.github.dgreco.rya.api.resolver.RyaContext;

class EntityGeneratorTest {
    private static final long N = 50_000;
    private final GenConfig config = new GenConfig(7, N, 1_767_225_600_000L, "urn:rya-gen:", 4, 0.2,
            List.of("A", "B", "C"), 0.2, 1.0, 0.25, 1.0, 0.8, 1.0, GenConfig.defaultWeights());
    private final EntityGenerator generator = new EntityGenerator(config);

    private static String key(final RyaStatement s) {
        return s.getSubject().getData() + " " + s.getPredicate().getData() + " " + s.getObject().getDataType() + " "
                + s.getObject().getData() + "@" + s.getObject().getLanguage() + " " + s.getContext();
    }

    /** With an RDF 1.2 share, the same dataset plus annotated links, and some labels with a base direction. */
    @Test
    void rdf12FractionAddsAnnotationsAndDirections() throws Exception {
        final GenConfig modernConfig = config.withRdf12Fraction(0.25);
        final EntityGenerator modern = new EntityGenerator(modernConfig);
        assertEquals(config.fingerprint(), config.withRdf12Fraction(0).fingerprint(), "RDF 1.1 fingerprints are unchanged");
        assertFalse(config.fingerprint().equals(modernConfig.fingerprint()));

        int links = 0;
        int annotated = 0;
        int languageLabels = 0;
        int directed = 0;
        int rightToLeft = 0;
        for (long i = 0; i < 20_000; i++) {
            final List<RyaStatement> plain = generator.generate(i);
            final List<RyaStatement> statements = modern.generate(i);
            final String entity = config.entity(i);
            // Without the claims, and with directions dropped, it is the RDF 1.1 entity.
            final List<String> withoutModern = new ArrayList<>();
            final Set<String> ownLinks = new HashSet<>();
            for (final RyaStatement s : statements) {
                assertNotNull(RyaContext.getInstance().serializeType(s.getObject()));
                final boolean claim = s.getSubject().getData().startsWith(entity + "/claim/");
                if (claim && RDF.REIFIES.stringValue().equals(s.getPredicate().getData())) {
                    final RyaTripleTerm term = (RyaTripleTerm) s.getObject();
                    assertEquals(entity, term.getSubject().getData());
                    assertTrue(ownLinks.contains(term.getPredicate().getData() + " " + term.getObject().getData()),
                            "the annotated link is one of the entity's, written before it");
                    assertEquals(statements.get(0).getContext(), s.getContext());
                    annotated++;
                } else if (claim) {
                    assertTrue(List.of(config.ontology("certainty"), config.ontology("assertedOn"))
                            .contains(s.getPredicate().getData()));
                } else if (RDF.DIRLANGSTRING.equals(s.getObject().getDataType())) {
                    final String language = s.getObject().getLanguage();
                    assertTrue(language.endsWith("--ltr") || language.equals("ar--rtl"), language);
                    directed++;
                    if (language.endsWith("--rtl")) {
                        rightToLeft++;
                    }
                    final String tag = language.substring(0, language.indexOf("--"));
                    withoutModern.add(key(new RyaStatement(s.getSubject(), s.getPredicate(),
                            LiteralForms.text(s.getObject().getData(), tag), s.getContext())));
                } else {
                    withoutModern.add(key(s));
                    if (s.getObject() instanceof RyaIRI iri && s.getSubject().getData().equals(entity)) {
                        ownLinks.add(s.getPredicate().getData() + " " + iri.getData());
                    }
                }
            }
            assertEquals(plain.stream().map(EntityGeneratorTest::key).toList(), withoutModern);
            for (final RyaStatement s : plain) {
                if (s.getObject() instanceof RyaIRI iri && iri.getData().startsWith(config.baseIri() + "e/")
                        && !iri.getData().endsWith("/geom")) {
                    links++;
                }
                if (RDF.LANGSTRING.equals(s.getObject().getDataType())
                        && RDFS.LABEL.stringValue().equals(s.getPredicate().getData())) {
                    languageLabels++;
                }
            }
        }
        assertTrue(annotated > links * 0.2 && annotated < links * 0.3, annotated + " of " + links + " links");
        assertTrue(directed > languageLabels * 0.2 && directed < languageLabels * 0.3,
                directed + " of " + languageLabels + " labels");
        assertTrue(rightToLeft > 0 && rightToLeft < directed, "right to left: " + rightToLeft);
    }

    @Test
    void sameIndexSameStatementsEvenInParallel() {
        final List<String> serial = IntStream.range(0, 2000).mapToObj(i -> generator.generate(i).toString()).toList();
        final List<String> parallel = IntStream.range(0, 2000).parallel()
                .mapToObj(i -> new EntityGenerator(config).generate(i).toString()).toList();
        assertEquals(serial, parallel);
    }

    @Test
    void statementsAreUniqueValidAndConsistent() throws Exception {
        final Set<String> seen = new HashSet<>();
        final Map<String, Integer> predicates = new HashMap<>();
        long total = 0;
        int geometries = 0;
        int polygons = 0;
        int labelled = 0;
        for (long i = 0; i < 20_000; i++) {
            final List<RyaStatement> statements = generator.generate(i);
            assertFalse(statements.isEmpty());
            final RyaStatement first = statements.get(0);
            assertEquals(RDF.TYPE.stringValue(), first.getPredicate().getData());
            assertEquals(config.ontology(generator.classOf(i).localName()), first.getObject().getData());
            if (first.getColumnVisibility() != null) {
                labelled++;
            }
            for (final RyaStatement s : statements) {
                assertTrue(seen.add(key(s)), "duplicate " + key(s));
                assertNotNull(RyaContext.getInstance().serializeType(s.getObject()));
                assertEquals(config.timestamp(), s.getTimestamp());
                assertEquals(first.getContext(), s.getContext(), "one graph per entity");
                predicates.merge(s.getPredicate().getData(), 1, Integer::sum);
                if (GEO.AS_WKT.stringValue().equals(s.getPredicate().getData())) {
                    geometries++;
                    if (s.getObject().getData().startsWith("POLYGON")) {
                        polygons++;
                    }
                    final var shape = GeoSupport.parse(s.getObject().getData());
                    assertTrue(shape.isPresent(), s.getObject().getData());
                    assertFalse(GeoCells.store(shape.get()).isEmpty());
                }
                if (s.getObject() instanceof RyaIRI iri && iri.getData().startsWith(config.baseIri() + "e/")) {
                    assertTrue(iri.getData().length() == (config.baseIri() + "e/").length() + 16
                            || iri.getData().endsWith("/geom"), iri.getData());
                }
            }
            total += statements.size();
        }
        final double perEntity = total / 20_000.0;
        assertTrue(perEntity > 6.5 && perEntity < 9.5, "triples per entity: " + perEntity);
        assertTrue(geometries > 1_500 && geometries < 5_000, "geometries: " + geometries);
        assertTrue(polygons > 50 && polygons < geometries / 3, "polygons: " + polygons);
        assertTrue(labelled > 3_000 && labelled < 5_000, "labelled entities: " + labelled);
        assertTrue(predicates.get(config.ontology("knows")) > 5_000);
        assertTrue(predicates.containsKey(config.ontology("madeBySensor")));
    }

    @Test
    void linksPointAtTheRightClass() {
        final String knows = config.ontology("knows");
        final String madeBy = config.ontology("madeBySensor");
        final Map<String, Long> indexOf = new HashMap<>();
        for (long i = 0; i < N; i++) {
            indexOf.put(config.entity(i), i);
        }
        for (long i = 0; i < 5_000; i++) {
            for (final RyaStatement s : generator.generate(i)) {
                final String p = s.getPredicate().getData();
                if (p.equals(knows) || p.equals(madeBy)) {
                    final Long target = indexOf.get(s.getObject().getData());
                    assertNotNull(target, "link outside the dataset: " + s);
                    assertEquals(p.equals(knows) ? EntityClass.PERSON : EntityClass.SENSOR, generator.classOf(target));
                }
            }
        }
    }

    @Test
    void observationsMeasureWhatTheirSensorObserves() {
        final String madeBy = config.ontology("madeBySensor");
        final String observed = config.ontology("observedProperty");
        final String observes = config.ontology("observes");
        final Map<String, Long> indexOf = new HashMap<>();
        for (long i = 0; i < N; i++) {
            indexOf.put(config.entity(i), i);
        }
        int checked = 0;
        for (long i = 0; i < 5_000; i++) {
            if (generator.classOf(i) != EntityClass.OBSERVATION) {
                continue;
            }
            final List<RyaStatement> st = generator.generate(i);
            final String sensor = value(st, madeBy);
            final String property = value(st, observed);
            assertEquals(value(generator.generate(indexOf.get(sensor)), observes), property);
            checked++;
        }
        assertTrue(checked > 500);
    }

    @Test
    void referenceDataDescribesTheDataset() {
        final List<RyaStatement> reference = ReferenceData.statements(generator);
        assertTrue(reference.size() > 1_500, "reference statements: " + reference.size());
        assertTrue(reference.stream().anyMatch(s -> s.getObject().getData().equals(config.fingerprint())));
        assertEquals(reference.size(), reference.stream().map(EntityGeneratorTest::key).distinct().count());
        assertTrue(reference.stream().allMatch(s -> s.getContext() == null && s.getColumnVisibility() == null));
    }

    private static String value(final List<RyaStatement> statements, final String predicate) {
        return statements.stream().filter(s -> s.getPredicate().getData().equals(predicate)).findFirst()
                .map(s -> s.getObject().getData()).orElseThrow();
    }
}
