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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * What a dataset is made of. Every generated statement depends only on these settings and the
 * entity index, so any process can generate any part of the dataset, and a resumed load writes
 * exactly what was written before. {@link #fingerprint()} identifies the dataset.
 *
 * @param seed seed of every random choice
 * @param entities how many entities the whole dataset has (all parts together); links point
 *        anywhere in this range
 * @param timestamp the timestamp of every statement, so rewriting a block gives the same keys
 * @param baseIri namespace of every generated IRI
 * @param graphs number of named graphs (0: everything in the default graph)
 * @param defaultGraphFraction share of entities in the default graph when there are graphs
 * @param labels visibility labels to use (empty: no labels)
 * @param labelledFraction share of entities that get a visibility expression
 * @param geoScale multiplies the chance of every geometry (0: no geometry)
 * @param polygonFraction share of city and landmark geometries that are polygons
 * @param textScale multiplies the length of descriptions (0: no descriptions)
 * @param linkSkew power-law exponent of link targets (0: uniform)
 * @param wordSkew Zipf exponent of word frequencies
 * @param classWeights per-mille share of each class
 */
public record GenConfig(long seed, long entities, long timestamp, String baseIri, int graphs,
        double defaultGraphFraction, List<String> labels, double labelledFraction, double geoScale,
        double polygonFraction, double textScale, double linkSkew, double wordSkew,
        Map<EntityClass, Integer> classWeights) {

    /** Bump when generation changes, so old progress files and datasets are not mixed up. */
    public static final String GENERATOR_VERSION = "rya-gen/1";

    public GenConfig {
        labels = List.copyOf(labels);
        classWeights = Map.copyOf(classWeights);
        if (entities < 1) {
            throw new IllegalArgumentException("the dataset needs at least one entity");
        }
    }

    public static Map<EntityClass, Integer> defaultWeights() {
        final Map<EntityClass, Integer> w = new EnumMap<>(EntityClass.class);
        for (final EntityClass c : EntityClass.values()) {
            w.put(c, c.defaultWeight());
        }
        return w;
    }

    /** Defaults for everything but the seed and size. */
    public static GenConfig defaults(final long seed, final long entities) {
        return new GenConfig(seed, entities, 1_767_225_600_000L, "urn:rya-gen:", 8, 0.2, List.of(), 0.1, 1.0, 0.25,
                1.0, 0.8, 1.0, defaultWeights());
    }

    public GenConfig withEntities(final long n) {
        return new GenConfig(seed, n, timestamp, baseIri, graphs, defaultGraphFraction, labels, labelledFraction,
                geoScale, polygonFraction, textScale, linkSkew, wordSkew, classWeights);
    }

    /** SHA-256 of every setting that changes the data, as hex. */
    public String fingerprint() {
        final StringBuilder b = new StringBuilder(GENERATOR_VERSION);
        b.append('|').append(seed).append('|').append(entities).append('|').append(timestamp).append('|').append(baseIri)
                .append('|').append(graphs).append('|').append(defaultGraphFraction).append('|').append(String.join(",", labels))
                .append('|').append(labelledFraction).append('|').append(geoScale).append('|').append(polygonFraction)
                .append('|').append(textScale).append('|').append(linkSkew).append('|').append(wordSkew);
        for (final EntityClass c : EntityClass.values()) {
            b.append('|').append(c.localName()).append('=').append(classWeights.getOrDefault(c, 0));
        }
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- IRIs ----

    /** A term of the generator's ontology. */
    public String ontology(final String localName) {
        return baseIri + "o#" + localName;
    }

    public String graph(final int k) {
        return baseIri + "g/" + k;
    }

    public String country(final int k) {
        return baseIri + "country/" + k;
    }

    public String category(final int k) {
        return baseIri + "category/" + k;
    }

    public String industry(final int k) {
        return baseIri + "industry/" + k;
    }

    public String observableProperty(final int k) {
        return baseIri + "property/" + k;
    }

    /** The statement-carrying description of the dataset itself. */
    public String datasetIri() {
        return baseIri + "dataset";
    }

    /** A 64-bit key per entity: a bijection of the index, so no two entities share an IRI. */
    public long entityKey(final long entity) {
        return SplitMix64.mix64(seed ^ (entity * 0x9E3779B97F4A7C15L));
    }

    public String entity(final long entity) {
        return baseIri + "e/" + HexFormat.of().toHexDigits(entityKey(entity));
    }
}
