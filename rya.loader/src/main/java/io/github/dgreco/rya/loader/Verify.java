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
package io.github.dgreco.rya.loader;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.security.VisibilityEvaluator;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRdfEvalStatsDAO;
import io.github.dgreco.rya.accumulo.query.AccumuloRyaQueryEngine;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.persist.RdfEvalStatsDAO.CARDINALITY_OF;
import io.github.dgreco.rya.loader.gen.EntityGenerator;
import io.github.dgreco.rya.loader.gen.SplitMix64;

/**
 * Regenerates a random sample of entities and compares them with the store: every statement the
 * reader may see must be there, nothing else may be, and each subject's statistics counter must
 * equal its number of visible statements.
 */
public final class Verify {
    private Verify() {
    }

    /** @return true when the sample matches the store exactly */
    public static boolean run(final AccumuloClient client, final LoaderOptions o, final PrintStream out) throws Exception {
        StoreCheck.forLoad(client, o);
        final EntityGenerator generator = new EntityGenerator(o.genConfig());
        final AccumuloRdfConfiguration conf = o.rdfConf();
        final VisibilityEvaluator visible = new VisibilityEvaluator(new Authorizations(o.auths().toArray(new String[0])));
        final AccumuloRyaQueryEngine engine = new AccumuloRyaQueryEngine(client, conf);
        final AccumuloRdfEvalStatsDAO stats = o.stats() ? new AccumuloRdfEvalStatsDAO(client, conf) : null;
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
                for (final RyaStatement st : generator.generate(e)) {
                    final byte[] cv = st.getColumnVisibility();
                    if (cv == null || visible.evaluate(new ColumnVisibility(cv))) {
                        expected.computeIfAbsent(st.getSubject().getData(), k -> new HashSet<>()).add(key(st));
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
                        if (Math.round(count) != s.getValue().size()) {
                            counterMismatches++;
                            note(problems, "subject counter of " + s.getKey() + " is " + Math.round(count) + ", expected "
                                    + s.getValue().size());
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
