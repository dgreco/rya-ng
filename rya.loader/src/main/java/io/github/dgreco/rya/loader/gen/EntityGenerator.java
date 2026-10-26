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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.vocabulary.GEO;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;

import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.loader.gen.Vocabulary.Language;

/**
 * Generates the statements of entity i, deterministically from the {@link GenConfig} and i.
 * Thread-safe: all state is read-only, and each entity uses its own random streams.
 * <p>
 * Each entity has separate streams for its graph and visibility, its anchor (the city it is in,
 * the country of a city…), its geometry, and its text, so another entity can recompute where a
 * city or landmark is without generating it: links and geometry stay consistent.
 */
public final class EntityGenerator {
    private static final long MAIN = 1;
    private static final long ANCHOR = 2;
    private static final long GEOMETRY = 3;
    private static final long TEXT = 4;
    private static final long GRAPH = 5;
    private static final int CATEGORIES = 50;
    private static final int INDUSTRIES = 30;
    private static final int OBSERVABLE_PROPERTIES = 12;
    private static final long YEAR_MS = 365L * 24 * 3600 * 1000;
    private static final long EPOCH_2010 = 1_262_304_000_000L;
    private static final long EPOCH_2020 = 1_577_836_800_000L;
    private static final long EPOCH_2026 = 1_767_225_600_000L;

    private final GenConfig config;
    private final ClassLayout layout;
    private final GeoGen geo;
    private final Vocabulary words = Vocabulary.get();
    private final Map<Language, Distributions.Zipf> wordRanks = new EnumMap<>(Language.class);
    private final Distributions.Zipf countries = new Distributions.Zipf(GeoGen.COUNTRIES, 1.0);
    private final Distributions.Zipf categories = new Distributions.Zipf(CATEGORIES, 0.8);
    private final Distributions.Zipf industries = new Distributions.Zipf(INDUSTRIES, 0.8);
    private final Distributions.Zipf observables = new Distributions.Zipf(OBSERVABLE_PROPERTIES, 0.8);
    private final Distributions.Zipf labelRanks;
    private final Map<EntityClass, RyaIRI> classIris = new EnumMap<>(EntityClass.class);
    /** Every predicate of the generator's ontology, built once (lookups happen for every statement). */
    static final String[] PREDICATES = {"givenName", "familyName", "birthDate", "email", "knows", "worksFor", "livesIn",
        "nationality", "foundingDate", "numberOfEmployees", "locatedIn", "industry", "stockPrice", "population",
        "inCountry", "elevation", "startDate", "endDate", "location", "performer", "organizer", "price", "sku",
        "category", "manufacturer", "releaseDate", "inStock", "reviewOf", "author", "rating", "datePublished", "about",
        "wordCount", "observes", "installedOn", "active", "madeBySensor", "observedProperty", "resultTime", "hasResult"};
    private final Map<String, RyaIRI> predicates;
    private final RyaIRI[] graphIris;
    private final RyaIRI type = new RyaIRI(RDF.TYPE.stringValue());
    private final RyaIRI label = new RyaIRI(RDFS.LABEL.stringValue());
    private final RyaIRI comment = new RyaIRI(RDFS.COMMENT.stringValue());
    private final RyaIRI hasGeometry = new RyaIRI(GEO.hasGeometry.stringValue());
    private final RyaIRI asWkt = new RyaIRI(GEO.AS_WKT.stringValue());

    public EntityGenerator(final GenConfig config) {
        this.config = config;
        this.layout = new ClassLayout(config.classWeights());
        this.geo = new GeoGen(config.seed());
        for (final Language l : Language.values()) {
            wordRanks.put(l, new Distributions.Zipf(words.size(l), config.wordSkew()));
        }
        for (final EntityClass c : EntityClass.values()) {
            classIris.put(c, new RyaIRI(config.ontology(c.localName())));
        }
        this.graphIris = new RyaIRI[Math.max(0, config.graphs())];
        for (int k = 0; k < graphIris.length; k++) {
            graphIris[k] = new RyaIRI(config.graph(k));
        }
        this.labelRanks = config.labels().isEmpty() ? null : new Distributions.Zipf(config.labels().size(), 1.0);
        final Map<String, RyaIRI> p = new java.util.HashMap<>();
        for (final String name : PREDICATES) {
            p.put(name, new RyaIRI(config.ontology(name)));
        }
        this.predicates = Map.copyOf(p);
    }

    public GenConfig config() {
        return config;
    }

    public ClassLayout layout() {
        return layout;
    }

    public GeoGen geo() {
        return geo;
    }

    public EntityClass classOf(final long entity) {
        return layout.classOf(entity);
    }

    // ---- streams and links ----

    private SplitMix64 stream(final long entity, final long salt) {
        return new SplitMix64(SplitMix64.mix64(config.entityKey(entity) ^ (salt * 0xD1B54A32D192ED03L)));
    }

    /** A member of class c, popular ones more often; -1 if the dataset has none. */
    private long pick(final EntityClass c, final SplitMix64 r, final double skew) {
        final long count = layout.count(c, config.entities());
        if (count == 0) {
            return -1;
        }
        return layout.indexOf(c, Distributions.powerLaw(r, count, skew));
    }

    /** An organization or a company, in proportion to their numbers. */
    private long pickOrganization(final SplitMix64 r) {
        final long orgs = layout.count(EntityClass.ORGANIZATION, config.entities());
        final long companies = layout.count(EntityClass.COMPANY, config.entities());
        if (orgs + companies == 0) {
            return -1;
        }
        return r.nextLong(orgs + companies) < orgs ? pick(EntityClass.ORGANIZATION, r, config.linkSkew())
                : pick(EntityClass.COMPANY, r, config.linkSkew());
    }

    /** The country of a city. */
    public int countryOfCity(final long city) {
        return countries.sample(stream(city, ANCHOR));
    }

    /** Where a city is. */
    public double[] cityPoint(final long city) {
        final int country = countryOfCity(city);
        return GeoGen.near(stream(city, GEOMETRY), geo.countryLon(country), geo.countryLat(country),
                geo.countryRadius(country) / 3);
    }

    /** The city a landmark, sensor or organization is in (-1 if there are no cities). */
    public long anchorCity(final long entity) {
        return pick(EntityClass.CITY, stream(entity, ANCHOR), 1.0);
    }

    /** Where an entity anchored in a city is: close to its city, or near a country if there are no cities. */
    private double[] anchoredPoint(final long entity, final SplitMix64 geometry) {
        final long city = anchorCity(entity);
        if (city < 0) {
            final int country = countries.sample(geometry);
            return GeoGen.near(geometry, geo.countryLon(country), geo.countryLat(country), geo.countryRadius(country) / 3);
        }
        final double[] c = cityPoint(city);
        return GeoGen.near(geometry, c[0], c[1], 0.02);
    }

    /** What a sensor observes (its second anchor draw, after its city). */
    public int sensorObserves(final long sensor) {
        final SplitMix64 r = stream(sensor, ANCHOR);
        pick(EntityClass.CITY, r, 1.0);
        return observables.sample(r);
    }

    // ---- statements ----

    /** The statements of one entity (a dozen at most, about 7.5 on average). */
    public List<RyaStatement> generate(final long entity) {
        final EntityClass c = layout.classOf(entity);
        final Entity e = new Entity(entity, c);
        e.add(type, classIris.get(c));
        switch (c) {
            case PERSON -> person(e);
            case ORGANIZATION -> organization(e, false);
            case COMPANY -> organization(e, true);
            case CITY -> city(e);
            case LANDMARK -> landmark(e);
            case EVENT -> event(e);
            case PRODUCT -> product(e);
            case REVIEW -> review(e);
            case ARTICLE -> article(e);
            case SENSOR -> sensor(e);
            case OBSERVATION -> observation(e);
        }
        return e.statements;
    }

    private void person(final Entity e) {
        final Language lang = e.language;
        final String given = Vocabulary.capitalize(word(lang, e.text));
        final String family = Vocabulary.capitalize(word(lang, e.text));
        e.add(label, LiteralForms.string(lang.unspaced() ? family + given : given + " " + family));
        if (e.main.chance(0.2)) {
            final Language other = words.language(e.text);
            e.add(label, LiteralForms.text(Vocabulary.capitalize(word(other, e.text)) + " " + Vocabulary.capitalize(word(other, e.text)), other.tag()));
        }
        e.add(o("givenName"), LiteralForms.string(given));
        e.add(o("familyName"), LiteralForms.string(family));
        if (e.main.chance(0.9)) {
            final int year = (int) Math.max(1925, Math.min(2010, Math.round(1982 + 18 * e.main.nextGaussian())));
            e.add(o("birthDate"), LiteralForms.date(LocalDate.ofYearDay(year, 1 + e.main.nextInt(365))));
        }
        if (e.main.chance(0.6)) {
            e.add(o("email"), LiteralForms.string("p" + HexFormat.of().toHexDigits(config.entityKey(e.index)).substring(0, 10) + "@example.org"));
        }
        final int knows = e.main.count(0, 6, 2);
        for (int i = 0; i < knows; i++) {
            e.link(o("knows"), pick(EntityClass.PERSON, e.main, config.linkSkew()));
        }
        if (e.main.chance(0.7)) {
            e.link(o("worksFor"), pickOrganization(e.main));
        }
        if (e.main.chance(0.9)) {
            e.link(o("livesIn"), pick(EntityClass.CITY, e.main, 1.0));
        }
        if (e.main.chance(0.8)) {
            e.add(o("nationality"), new RyaIRI(config.country(countries.sample(e.main))));
        }
    }

    private void organization(final Entity e, final boolean company) {
        final Language lang = e.language;
        e.add(label, LiteralForms.text(title(lang, e.text, 1, 3), lang.tag()));
        if (e.main.chance(0.4)) {
            final Language other = words.language(e.text);
            e.add(label, LiteralForms.text(title(other, e.text, 1, 3), other.tag()));
        }
        description(e, 0.6, 10, 30);
        if (e.main.chance(0.8)) {
            e.add(o("foundingDate"), LiteralForms.date(LocalDate.ofYearDay(e.main.between(1850, 2025), 1 + e.main.nextInt(365))));
        }
        if (e.main.chance(0.8)) {
            final double employees = 5 / Math.pow(1 - e.main.nextDouble(), 1 / 1.2);
            e.add(o("numberOfEmployees"), LiteralForms.integer(Math.min(1_000_000, (long) employees)));
        }
        e.link(o("locatedIn"), anchorCity(e.index));
        if (e.geometry(0.3)) {
            e.geometry(GeoGen.point(anchoredPoint(e.index, e.shape)));
        }
        if (company) {
            e.add(o("industry"), new RyaIRI(config.industry(industries.sample(e.main))));
            if (e.main.chance(0.3)) {
                e.add(o("stockPrice"), LiteralForms.decimal2(Math.exp(3 + 1.2 * e.main.nextGaussian())));
            }
        }
    }

    private void city(final Entity e) {
        final int country = countryOfCity(e.index);
        final double[] point = cityPoint(e.index);
        final Set<Language> named = new HashSet<>();
        final int names = e.main.between(2, 4);
        for (int i = 0; i < names; i++) {
            final Language l = i == 0 ? e.language : words.language(e.text);
            if (named.add(l)) {
                e.add(label, LiteralForms.text(Vocabulary.capitalize(word(l, e.text)), l.tag()));
            }
        }
        e.add(o("population"), LiteralForms.longValue(Math.max(100, Math.round(Math.exp(10.5 + 1.6 * e.main.nextGaussian())))));
        e.add(o("inCountry"), new RyaIRI(config.country(country)));
        if (e.geometry(1.0)) {
            // The city's geometry stream starts with its point (see cityPoint); polygons come after.
            final SplitMix64 g = stream(e.index, GEOMETRY);
            GeoGen.near(g, 0, 0, 1);
            e.geometry(g.chance(config.polygonFraction())
                    ? GeoGen.polygon(g, point[0], point[1], 0.02 + 0.13 * g.nextDouble())
                    : GeoGen.point(point));
        }
    }

    private void landmark(final Entity e) {
        final int labels = e.main.count(1, 2, 1.5);
        for (int i = 0; i < labels; i++) {
            final Language l = i == 0 ? e.language : words.language(e.text);
            e.add(label, LiteralForms.text(title(l, e.text, 2, 4), l.tag()));
        }
        description(e, 0.5, 8, 25);
        e.link(o("locatedIn"), anchorCity(e.index));
        if (e.geometry(1.0)) {
            final double[] p = anchoredPoint(e.index, e.shape);
            e.geometry(e.shape.chance(config.polygonFraction())
                    ? GeoGen.polygon(e.shape, p[0], p[1], 0.0003 + 0.0027 * e.shape.nextDouble())
                    : GeoGen.point(p));
        }
        if (e.main.chance(0.3)) {
            e.add(o("elevation"), LiteralForms.floatValue((float) Math.max(-400, Math.min(8800, Math.round(300 + 400 * e.main.nextGaussian())))));
        }
    }

    private void event(final Entity e) {
        final int labels = e.main.count(1, 2, 1.2);
        for (int i = 0; i < labels; i++) {
            final Language l = i == 0 ? e.language : words.language(e.text);
            e.add(label, LiteralForms.text(title(l, e.text, 2, 5), l.tag()));
        }
        final long start = EPOCH_2010 + e.main.nextLong(EPOCH_2026 - EPOCH_2010);
        e.add(o("startDate"), LiteralForms.dateTime(start));
        if (e.main.chance(0.7)) {
            e.add(o("endDate"), LiteralForms.dateTime(start + 3_600_000L + e.main.nextLong(5L * 24 * 3_600_000L)));
        }
        double[] where = null;
        if (e.main.chance(0.9)) {
            final boolean atLandmark = e.main.chance(0.7) && layout.count(EntityClass.LANDMARK, config.entities()) > 0;
            final long location = atLandmark ? pick(EntityClass.LANDMARK, e.main, config.linkSkew())
                    : pick(EntityClass.CITY, e.main, 1.0);
            if (location >= 0) {
                e.link(o("location"), location);
                where = atLandmark ? anchoredPoint(location, stream(location, GEOMETRY)) : cityPoint(location);
            }
        }
        final int performers = e.main.count(0, 3, 1);
        for (int i = 0; i < performers; i++) {
            e.link(o("performer"), pick(EntityClass.PERSON, e.main, config.linkSkew()));
        }
        if (e.main.chance(0.5)) {
            e.link(o("organizer"), pickOrganization(e.main));
        }
        if (where != null && e.geometry(0.4)) {
            e.geometry(GeoGen.point(where));
        }
    }

    private void product(final Entity e) {
        final int labels = e.main.count(1, 2, 1.3);
        for (int i = 0; i < labels; i++) {
            final Language l = i == 0 ? e.language : words.language(e.text);
            e.add(label, LiteralForms.text(title(l, e.text, 1, 3), l.tag()));
        }
        description(e, 0.8, 10, 40);
        e.add(o("price"), LiteralForms.decimal2(Math.exp(3 + 1.2 * e.main.nextGaussian())));
        e.add(o("sku"), LiteralForms.string("SKU-" + Vocabulary.code(HexFormat.of().toHexDigits(config.entityKey(e.index)).substring(0, 10))));
        e.add(o("category"), new RyaIRI(config.category(categories.sample(e.main))));
        if (e.main.chance(0.9)) {
            e.link(o("manufacturer"), pick(EntityClass.COMPANY, e.main, config.linkSkew()));
        }
        if (e.main.chance(0.5)) {
            e.add(o("releaseDate"), LiteralForms.date(LocalDate.ofYearDay(e.main.between(2000, 2025), 1 + e.main.nextInt(365))));
        }
        e.add(o("inStock"), LiteralForms.bool(e.main.chance(0.7)));
    }

    private void review(final Entity e) {
        e.link(o("reviewOf"), pick(EntityClass.PRODUCT, e.main, 1.0));
        e.link(o("author"), pick(EntityClass.PERSON, e.main, config.linkSkew()));
        final double u = e.main.nextDouble();
        final int rating = u < 0.05 ? 1 : u < 0.12 ? 2 : u < 0.25 ? 3 : u < 0.55 ? 4 : 5;
        e.add(o("rating"), LiteralForms.integer(rating));
        description(e, 0.9, 15, 60);
        e.add(o("datePublished"), LiteralForms.dateTime(EPOCH_2010 + e.main.nextLong(EPOCH_2026 - EPOCH_2010)));
    }

    private void article(final Entity e) {
        final int titles = e.main.count(1, 2, 1.1);
        for (int i = 0; i < titles; i++) {
            final Language l = i == 0 ? e.language : words.language(e.text);
            e.add(label, LiteralForms.text(Vocabulary.capitalize(phrase(l, e.text, 3, 8)), l.tag()));
        }
        description(e, 0.8, 30, 90);
        final int authors = e.main.count(1, 3, 1.6);
        for (int i = 0; i < authors; i++) {
            e.link(o("author"), pick(EntityClass.PERSON, e.main, config.linkSkew()));
        }
        final int about = e.main.count(0, 3, 1.2);
        for (int i = 0; i < about; i++) {
            e.link(o("about"), Distributions.powerLaw(e.main, config.entities(), 0.6));
        }
        e.add(o("datePublished"), LiteralForms.dateTime(EPOCH_2010 + e.main.nextLong(EPOCH_2026 - EPOCH_2010)));
        if (e.main.chance(0.8)) {
            e.add(o("wordCount"), LiteralForms.integer32((int) Math.min(100_000, Math.round(Math.exp(7 + 0.8 * e.main.nextGaussian())))));
        }
    }

    private void sensor(final Entity e) {
        e.add(label, LiteralForms.string("SN-" + Vocabulary.code(HexFormat.of().toHexDigits(config.entityKey(e.index)).substring(0, 8))));
        e.link(o("locatedIn"), anchorCity(e.index));
        e.add(o("observes"), new RyaIRI(config.observableProperty(sensorObserves(e.index))));
        if (e.geometry(1.0)) {
            e.geometry(GeoGen.point(anchoredPoint(e.index, e.shape)));
        }
        if (e.main.chance(0.8)) {
            e.add(o("installedOn"), LiteralForms.date(LocalDate.ofYearDay(e.main.between(2015, 2025), 1 + e.main.nextInt(365))));
        }
        e.add(o("active"), LiteralForms.bool(e.main.chance(0.9)));
    }

    private void observation(final Entity e) {
        final long sensor = pick(EntityClass.SENSOR, e.main, 1.0);
        if (sensor >= 0) {
            e.link(o("madeBySensor"), sensor);
            e.add(o("observedProperty"), new RyaIRI(config.observableProperty(sensorObserves(sensor))));
        }
        e.add(o("resultTime"), LiteralForms.dateTime(EPOCH_2020 + e.main.nextLong(EPOCH_2026 - EPOCH_2020)));
        e.add(o("hasResult"), LiteralForms.decimal2(20 + 15 * e.main.nextGaussian()));
    }

    // ---- text ----

    private String word(final Language lang, final SplitMix64 r) {
        return words.word(lang, wordRanks.get(lang).sample(r));
    }

    private String phrase(final Language lang, final SplitMix64 r, final int min, final int max) {
        final int n = r.between(min, max);
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0 && !lang.unspaced()) {
                b.append(' ');
            }
            b.append(word(lang, r));
        }
        return b.toString();
    }

    private String title(final Language lang, final SplitMix64 r, final int min, final int max) {
        final int n = r.between(min, max);
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0 && !lang.unspaced()) {
                b.append(' ');
            }
            b.append(Vocabulary.capitalize(word(lang, r)));
        }
        return b.toString();
    }

    /** An rdfs:comment of min–max words (scaled by textScale), with the given chance. */
    private void description(final Entity e, final double chance, final int min, final int max) {
        if (config.textScale() <= 0 || !e.main.chance(chance)) {
            return;
        }
        final int lo = Math.max(1, (int) Math.round(min * config.textScale()));
        final int hi = Math.max(lo, (int) Math.round(max * config.textScale()));
        final String sentence = Vocabulary.capitalize(phrase(e.language, e.text, lo, hi)) + (e.language.unspaced() ? "。" : ".");
        e.add(comment, LiteralForms.text(sentence, e.language.tag()));
    }

    private RyaIRI o(final String localName) {
        final RyaIRI iri = predicates.get(localName);
        if (iri == null) {
            throw new IllegalArgumentException("not in PREDICATES: " + localName);
        }
        return iri;
    }

    /** One entity being generated: its subject, graph, visibility and streams. */
    private final class Entity {
        final long index;
        final RyaIRI subject;
        final RyaIRI graph;
        final byte[] visibility;
        final SplitMix64 main;
        final SplitMix64 text;
        final SplitMix64 shape;
        final Language language;
        final List<RyaStatement> statements = new ArrayList<>(12);
        private final Set<String> seen = new HashSet<>();
        private boolean geometryWritten;

        Entity(final long index, final EntityClass c) {
            this.index = index;
            this.subject = new RyaIRI(config.entity(index));
            this.main = stream(index, MAIN);
            this.text = stream(index, TEXT);
            this.shape = stream(index, GEOMETRY);
            this.language = words.language(text);
            final SplitMix64 g = stream(index, GRAPH);
            this.graph = graphIris.length == 0 || g.chance(config.defaultGraphFraction()) ? null
                    : graphIris[g.nextInt(graphIris.length)];
            this.visibility = visibility(g);
        }

        private byte[] visibility(final SplitMix64 g) {
            if (labelRanks == null || !g.chance(config.labelledFraction())) {
                return null;
            }
            final List<String> labels = config.labels();
            final String x = labels.get(labelRanks.sample(g));
            final double u = g.nextDouble();
            String expression = x;
            if (u >= 0.7 && labels.size() > 1) {
                String y = labels.get(labelRanks.sample(g));
                for (int i = 0; y.equals(x) && i < 8; i++) {
                    y = labels.get(g.nextInt(labels.size()));
                }
                if (!y.equals(x)) {
                    expression = x + (u < 0.9 ? "&" : "|") + y;
                }
            }
            return expression.getBytes(StandardCharsets.UTF_8);
        }

        void add(final RyaIRI predicate, final RyaType object) {
            add(subject, predicate, object);
        }

        void add(final RyaIRI s, final RyaIRI predicate, final RyaType object) {
            final String key = s.getData() + '\u0000' + predicate.getData() + '\u0000' + object.getDataType() + '\u0000'
                    + object.getData() + '\u0000' + object.getLanguage();
            if (seen.add(key)) {
                statements.add(new RyaStatement(s, predicate, object, graph, null, visibility, null, config.timestamp()));
            }
        }

        /** A link to another entity; ignored when there is no target or it would point to itself. */
        void link(final RyaIRI predicate, final long target) {
            if (target >= 0 && target != index) {
                add(predicate, new RyaIRI(config.entity(target)));
            }
        }

        /** Whether this entity gets a geometry, with the given chance (scaled by geoScale). */
        boolean geometry(final double chance) {
            return config.geoScale() > 0 && main.chance(Math.min(1, chance * config.geoScale()));
        }

        /** geo:hasGeometry to a geometry node, which has the WKT. */
        void geometry(final String wkt) {
            if (geometryWritten) {
                return;
            }
            geometryWritten = true;
            final RyaIRI node = new RyaIRI(subject.getData() + "/geom");
            add(hasGeometry, node);
            add(node, asWkt, LiteralForms.wkt(wkt));
        }
    }
}
