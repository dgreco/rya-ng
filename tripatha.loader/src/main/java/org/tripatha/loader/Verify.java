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
package org.tripatha.loader;

import static org.tripatha.api.RdfCloudTripleStoreConstants.SUBJECT_CF;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import org.tripatha.store.EvalStatsMutations.CounterKey;
import org.tripatha.store.EvalStatsMutations.CounterPolicy;
import org.tripatha.store.StoreRdfConfiguration;
import org.tripatha.store.spi.Store;
import org.tripatha.store.visibility.VisibilityExpression;
import org.tripatha.store.RyaStoreEvalStatsDAO;
import org.tripatha.store.query.RyaStoreQueryEngine;
import org.tripatha.api.domain.RyaIRI;
import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.persist.RdfEvalStatsDAO.CARDINALITY_OF;
import org.tripatha.loader.gen.EntityGenerator;
import org.tripatha.loader.gen.SplitMix64;

/**
 * Regenerates a random sample of entities and compares them with the store: every statement the
 * reader may see must be there, nothing else may be, and each subject's statistics counter must
 * equal its number of visible statements (leaving out the label cells of 1 a load with
 * {@code --stats-ones skip} doesn't write).
 */
public final class Verify {
    private Verify() {
    }

    /** @return true when the sample matches the store exactly */
    public static boolean run(final Store store, final LoaderOptions o, final PrintStream out) throws Exception {
        StoreCheck.forLoad(store, o);
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final StoreRdfConfiguration conf = o.rdfConf();
        final List<String> readerLabels = o.auths();
        final RyaStoreQueryEngine engine = new RyaStoreQueryEngine(store, conf);
        final RyaStoreEvalStatsDAO stats = o.stats() ? new RyaStoreEvalStatsDAO(store, conf) : null;
        if (stats != null) {
            stats.init();
        }
        final long[] range = o.partRange();
        final long span = range[1] - range[0];
        final int sample = (int) Math.min(span, o.sample());
        final SplitMix64 random = new SplitMix64(o.genConfig().seed() ^ 0x7E51F1L);
        final Set<Long> entities = new HashSet<>();
        while (entities.size() < sample) {
            entities.add(sample == span ? range[0] + entities.size() : range[0] + random.nextLong(span));
        }
        long expectedTotal = 0;
        long missing = 0;
        long extra = 0;
        long counterMismatches = 0;
        final List<String> problems = new ArrayList<>();
        try {
            for (final long e : entities) {
                final Map<String, Set<String>> expected = new LinkedHashMap<>();
                // The same statements per subject and label: a counter cell is per label.
                final Map<String, Map<String, Set<String>>> byLabel = new HashMap<>();
                for (final RyaStatement st : generator.generate(e)) {
                    final byte[] cv = st.getColumnVisibility();
                    if (VisibilityExpression.parse(cv).isVisibleTo(readerLabels)) {
                        final String subject = st.getSubject().getData();
                        expected.computeIfAbsent(subject, k -> new HashSet<>()).add(key(st));
                        byLabel.computeIfAbsent(subject, k -> new HashMap<>())
                                .computeIfAbsent(cv == null ? "" : new String(cv, StandardCharsets.ISO_8859_1), k -> new HashSet<>())
                                .add(key(st));
                    }
                }
                for (final Map.Entry<String, Set<String>> s : expected.entrySet()) {
                    final Set<String> actual = new HashSet<>();
                    try (CloseableIteration<RyaStatement> it = engine.query(new RyaStatement(new RyaIRI(s.getKey()), null, null), conf)) {
                        while (it.hasNext()) {
                            actual.add(key(it.next()));
                        }
                    }
                    expectedTotal += s.getValue().size();
                    for (final String k : s.getValue()) {
                        if (!actual.contains(k)) {
                            missing++;
                            note(problems, "missing: " + k);
                        }
                    }
                    for (final String k : actual) {
                        if (!s.getValue().contains(k)) {
                            extra++;
                            note(problems, "unexpected: " + k);
                        }
                    }
                    if (stats != null) {
                        final double count = stats.getCardinality(conf, CARDINALITY_OF.SUBJECT,
                                List.of(SimpleValueFactory.getInstance().createIRI(s.getKey())));
                        final long expectedCount = expectedSubjectCounter(s.getKey(), byLabel.get(s.getKey()), o.statsOnes());
                        if (Math.round(count) != expectedCount) {
                            counterMismatches++;
                            note(problems, "subject counter of " + s.getKey() + " is " + Math.round(count) + ", expected "
                                    + expectedCount + (expectedCount < 0 ? " (none: every label's count is 1)" : ""));
                        }
                    }
                }
            }
        } finally {
            engine.close();
            if (stats != null) {
                stats.destroy();
            }
        }
        problems.forEach(out::println);
        out.printf(Locale.ROOT, "Verified %,d entities (%,d visible statements): %,d missing, %,d unexpected, %,d counter mismatches.%n",
                entities.size(), expectedTotal, missing, extra, counterMismatches);
        return missing == 0 && extra == 0 && counterMismatches == 0;
    }

    /**
     * What {@code getCardinality} should return for a subject, from its visible statements per
     * label: the sum of the label cells the load wrote, or -1 when it wrote none.
     */
    static long expectedSubjectCounter(final String subject, final Map<String, Set<String>> byLabel, final CounterPolicy policy) {
        long total = 0;
        boolean written = false;
        for (final Map.Entry<String, Set<String>> label : byLabel.entrySet()) {
            final long n = label.getValue().size();
            if (!policy.skips(new CounterKey(subject, SUBJECT_CF, "", label.getKey()), n)) {
                total += n;
                written = true;
            }
        }
        return written ? total : -1;
    }

    private static void note(final List<String> problems, final String problem) {
        if (problems.size() < 20) {
            problems.add(problem);
        }
    }

    /** A statement as text: subject, predicate, object (datatype, value, language), graph and visibility. */
    static String key(final RyaStatement st) {
        final byte[] cv = st.getColumnVisibility();
        return st.getSubject().getData() + " " + st.getPredicate().getData() + " " + st.getObject().getDataType() + " "
                + st.getObject().getData() + (st.getObject().getLanguage() == null ? "" : "@" + st.getObject().getLanguage())
                + " " + (st.getContext() == null ? "-" : st.getContext().getData()) + " ["
                + (cv == null ? "" : new String(cv, StandardCharsets.UTF_8)) + "]";
    }
}
