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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.loader.gen.Vocabulary.Language;

/**
 * The statements written once per dataset (by {@code prepare}): the ontology (class and property
 * hierarchy, for inference), the countries, categories, industries and observed properties that
 * entities link to, and a description of the dataset itself, which later commands check.
 */
public final class ReferenceData {
    private ReferenceData() {
    }

    /** Local names of the dataset description's properties. */
    public static final String GENERATOR = "generator";
    public static final String FINGERPRINT = "fingerprint";
    public static final String ENTITIES = "entities";
    public static final String SEED = "seed";

    public static List<RyaStatement> statements(final EntityGenerator generator) {
        final GenConfig c = generator.config();
        final List<RyaStatement> out = new ArrayList<>();
        final Adder add = (s, p, o) -> out.add(new RyaStatement(new RyaIRI(s), new RyaIRI(p), o, null, null, null, null, c.timestamp()));
        final String type = RDF.TYPE.stringValue();
        final String label = RDFS.LABEL.stringValue();

        // Ontology: every class, its superclass, and a few property axioms.
        final String[][] superclasses = {
            {"Person", "Agent"}, {"Organization", "Agent"}, {"Company", "Organization"}, {"City", "Place"},
            {"Landmark", "Place"}, {"Review", "CreativeWork"}, {"Article", "CreativeWork"}, {"Sensor", "Device"},
            {"Event", "Thing"}, {"Product", "Thing"}, {"Observation", "Thing"}, {"Agent", "Thing"}, {"Place", "Thing"},
            {"CreativeWork", "Thing"}, {"Device", "Thing"}, {"Country", "Place"}, {"Category", "Thing"},
            {"Industry", "Thing"}, {"ObservableProperty", "Thing"}};
        for (final String[] sc : superclasses) {
            add.add(c.ontology(sc[0]), type, iri(OWL.CLASS.stringValue()));
            add.add(c.ontology(sc[0]), RDFS.SUBCLASSOF.stringValue(), iri(c.ontology(sc[1])));
        }
        add.add(c.ontology("Thing"), type, iri(OWL.CLASS.stringValue()));
        add.add(c.ontology("inCountry"), RDFS.SUBPROPERTYOF.stringValue(), iri(c.ontology("locatedIn")));
        add.add(c.ontology("worksFor"), RDFS.SUBPROPERTYOF.stringValue(), iri(c.ontology("memberOf")));
        add.add(c.ontology("locatedIn"), type, iri(OWL.TRANSITIVEPROPERTY.stringValue()));
        add.add(c.ontology("knows"), type, iri(OWL.SYMMETRICPROPERTY.stringValue()));
        add.add(c.ontology("reviewOf"), RDFS.DOMAIN.stringValue(), iri(c.ontology("Review")));
        add.add(c.ontology("reviewOf"), RDFS.RANGE.stringValue(), iri(c.ontology("Product")));
        add.add(c.ontology("author"), RDFS.RANGE.stringValue(), iri(c.ontology("Person")));

        // Countries: labels in three languages, a code, a population and a boundary polygon.
        final Vocabulary words = Vocabulary.get();
        final GeoGen geo = generator.geo();
        for (int k = 0; k < GeoGen.COUNTRIES; k++) {
            final SplitMix64 r = new SplitMix64(SplitMix64.mix64(c.seed() ^ 0xC0DE_0000L ^ k));
            final String country = c.country(k);
            add.add(country, type, iri(c.ontology("Country")));
            for (final Language l : new Language[] {Language.EN, Language.FR, Language.ZH}) {
                add.add(country, label, LiteralForms.text(Vocabulary.capitalize(words.word(l, r.nextInt(2000))), l.tag()));
            }
            add.add(country, c.ontology("code"), LiteralForms.string(String.format(java.util.Locale.ROOT, "C%03d", k)));
            add.add(country, c.ontology("population"), LiteralForms.longValue(Math.round(Math.exp(15 + 1.5 * r.nextGaussian()))));
            final String geometry = country + "/geom";
            add.add(country, GEO.hasGeometry.stringValue(), iri(geometry));
            add.add(geometry, GEO.AS_WKT.stringValue(), LiteralForms.wkt(
                    GeoGen.polygon(r, geo.countryLon(k), geo.countryLat(k), geo.countryRadius(k))));
        }
        final String[][] simple = {{"Category", "50"}, {"Industry", "30"}, {"ObservableProperty", "12"}};
        for (final String[] kind : simple) {
            final int n = Integer.parseInt(kind[1]);
            for (int k = 0; k < n; k++) {
                final String iri = switch (kind[0]) {
                    case "Category" -> c.category(k);
                    case "Industry" -> c.industry(k);
                    default -> c.observableProperty(k);
                };
                add.add(iri, type, iri(c.ontology(kind[0])));
                add.add(iri, label, LiteralForms.text(Vocabulary.capitalize(words.word(Language.EN, 10 + k * 7 + kind[0].length())), "en"));
            }
        }

        // The dataset itself: what generated it, so later commands can check they match.
        final String dataset = c.datasetIri();
        add.add(dataset, c.ontology(GENERATOR), LiteralForms.string(GenConfig.GENERATOR_VERSION));
        add.add(dataset, c.ontology(FINGERPRINT), LiteralForms.string(c.fingerprint()));
        add.add(dataset, c.ontology(ENTITIES), LiteralForms.integer(c.entities()));
        add.add(dataset, c.ontology(SEED), LiteralForms.integer(c.seed()));
        return out;
    }

    private static RyaIRI iri(final String iri) {
        return new RyaIRI(iri);
    }

    @FunctionalInterface
    private interface Adder {
        void add(String subject, String predicate, RyaType object);
    }
}
