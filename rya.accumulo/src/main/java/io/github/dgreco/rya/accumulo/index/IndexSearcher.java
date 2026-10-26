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
package io.github.dgreco.rya.accumulo.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;

import io.github.dgreco.rya.accumulo.AccumuloRdfConfiguration;
import io.github.dgreco.rya.accumulo.AccumuloRyaDAO;
import io.github.dgreco.rya.accumulo.index.geo.GeoRegion;
import io.github.dgreco.rya.accumulo.index.text.TextCandidates;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.persist.RyaDAOException;

/**
 * Runs an {@link IndexQuery} and returns the stored statements it points to.
 * <p>
 * Index entries are only candidates. Each one is resolved by reading the statement tables with
 * the query's own configuration (authorizations, TTL, …), so results are exactly the stored
 * cells the user can see, once each, as a plain statement lookup would return them. An index
 * entry left behind by a failure costs a lookup and returns nothing.
 * <p>
 * Text queries: an {@code AND} is driven by its smallest operand and the others are checked with
 * exact-key lookups; operand sizes are estimated by reading up to {@value #PROBE_LIMIT} entries.
 * Results are produced lazily, so {@code LIMIT} and {@code ASK} stop early.
 */
public final class IndexSearcher {
    /** Entries read per operand to estimate its size; smaller operands are kept in memory. */
    static final int PROBE_LIMIT = 1000;
    /** Candidates checked against the other operands of an AND at a time. */
    static final int VERIFY_CHUNK = 1000;
    /** Candidates resolved against the statement tables at a time. */
    static final int RESOLVE_CHUNK = 1000;
    private static final int THREADS = 4;
    private static final AtomicLong SEARCHES = new AtomicLong();

    private final AccumuloRyaDAO dao;

    public IndexSearcher(final AccumuloRyaDAO dao) {
        this.dao = dao;
    }

    /** How many index searches have run in this JVM (lets tests check that the index was used). */
    public static long getSearchCount() {
        return SEARCHES.get();
    }

    /**
     * Stored statements whose object the index lists as a candidate for {@code query}.
     *
     * @param predicate only statements with this predicate; null for any
     * @param graphs only these graphs ({@code ""} is the default graph); null for every graph
     * @param conf the query's configuration (its authorizations are used for every read)
     */
    public CloseableIteration<RyaStatement> search(final IndexQuery query, final String predicate,
            final Collection<String> graphs, final AccumuloRdfConfiguration conf) {
        SEARCHES.incrementAndGet();
        return new Search(query, predicate, graphs, conf);
    }

    /** One search: owns its scanners and closes them when the results are closed. */
    private final class Search extends LookAheadIteration<RyaStatement> {
        private final AccumuloClient client = dao.getClient();
        private final String table;
        private final Authorizations auths;
        private final String predicate;
        private final Collection<String> graphs;
        private final AccumuloRdfConfiguration conf;
        private final List<ScannerBase> scanners = new ArrayList<>();
        private final Map<TextCandidates, Leaf> leaves = new HashMap<>();
        private final Iterator<DocKey> candidates;
        private Iterator<RyaStatement> resolved = Collections.emptyIterator();

        Search(final IndexQuery query, final String predicate, final Collection<String> graphs,
                final AccumuloRdfConfiguration conf) {
            this.table = dao.getIndexCatalog().table(query.type());
            this.auths = conf.getAuthorizations();
            this.predicate = predicate;
            this.graphs = graphs;
            this.conf = conf;
            if (graphs != null && graphs.isEmpty()) {
                this.candidates = Collections.emptyIterator();
            } else {
                this.candidates = switch (query) {
                    case IndexQuery.Text t -> stream(t.candidates());
                    case IndexQuery.Geo g -> geo(g.region());
                };
            }
        }

        @Override
        protected RyaStatement getNextElement() {
            while (!resolved.hasNext()) {
                if (!candidates.hasNext()) {
                    return null;
                }
                resolved = resolveChunk();
            }
            return resolved.next();
        }

        @Override
        protected void handleClose() {
            for (final ScannerBase s : scanners) {
                s.close();
            }
            scanners.clear();
        }

        /** Reads the stored cells for the next candidates and keeps the ones they point to. */
        private Iterator<RyaStatement> resolveChunk() {
            final Set<DocKey> wanted = new HashSet<>();
            final Map<String, RyaStatement> patterns = new LinkedHashMap<>();
            while (wanted.size() < RESOLVE_CHUNK && candidates.hasNext()) {
                final DocKey doc = candidates.next();
                wanted.add(doc);
                patterns.computeIfAbsent(doc.subject() + IndexKeys.SEP + doc.predicate(),
                        k -> new RyaStatement(new RyaIRI(doc.subject()), new RyaIRI(doc.predicate()), null));
            }
            final List<RyaStatement> found = new ArrayList<>();
            try (CloseableIteration<RyaStatement> cells = dao.getQueryEngine().batchQuery(patterns.values(), conf)) {
                while (cells.hasNext()) {
                    final RyaStatement st = cells.next();
                    if (wanted.contains(IndexKeys.docKey(st))) {
                        found.add(st);
                    }
                }
            }
            return found.iterator();
        }

        // ---- geo ----

        private Iterator<DocKey> geo(final GeoRegion region) {
            final List<Range> ranges = new ArrayList<>();
            for (final String cell : region.prefixes()) {
                ranges.add(IndexKeys.prefixRange(cell));
            }
            for (final String cell : region.exactCells()) {
                ranges.add(IndexKeys.termRange(cell, predicate));
            }
            if (ranges.isEmpty()) {
                return Collections.emptyIterator();
            }
            final BatchScanner scanner = batchScanner();
            scanner.setRanges(Range.mergeOverlapping(ranges));
            return distinct(docKeys(scanner.iterator(), true));
        }

        // ---- text ----

        private Iterator<DocKey> stream(final TextCandidates node) {
            return switch (node) {
                case TextCandidates.Word w -> leaf(w).stream();
                case TextCandidates.Start s -> leaf(s).stream();
                case TextCandidates.AnyOf o -> distinct(concat(o.operands().stream().map(this::stream).toList()));
                case TextCandidates.AllOf a -> intersect(a);
            };
        }

        private double estimate(final TextCandidates node) {
            return switch (node) {
                case TextCandidates.Word w -> leaf(w).estimate();
                case TextCandidates.Start s -> leaf(s).estimate();
                case TextCandidates.AnyOf o -> o.operands().stream().mapToDouble(this::estimate).sum();
                case TextCandidates.AllOf a -> a.operands().stream().mapToDouble(this::estimate).min().orElse(0);
            };
        }

        private static int rank(final TextCandidates node) {
            return switch (node) {
                case TextCandidates.Word w -> 0;
                case TextCandidates.AllOf a -> 1;
                case TextCandidates.AnyOf o -> 2;
                case TextCandidates.Start s -> 3;
            };
        }

        private Iterator<DocKey> intersect(final TextCandidates.AllOf and) {
            final List<TextCandidates> operands = new ArrayList<>(and.operands());
            operands.sort(Comparator.comparingDouble(this::estimate).thenComparingInt(Search::rank));
            final Iterator<DocKey> driver = stream(operands.get(0));
            final List<TextCandidates> others = operands.subList(1, operands.size());
            return new Iterator<>() {
                private Iterator<DocKey> chunk = Collections.emptyIterator();

                @Override
                public boolean hasNext() {
                    while (!chunk.hasNext() && driver.hasNext()) {
                        Set<DocKey> keep = new LinkedHashSet<>();
                        while (keep.size() < VERIFY_CHUNK && driver.hasNext()) {
                            keep.add(driver.next());
                        }
                        for (final TextCandidates other : others) {
                            if (keep.isEmpty()) {
                                break;
                            }
                            keep = verify(other, keep);
                        }
                        chunk = keep.iterator();
                    }
                    return chunk.hasNext();
                }

                @Override
                public DocKey next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return chunk.next();
                }
            };
        }

        /** The documents of {@code docs} that satisfy {@code node} (or may: large prefixes pass). */
        private Set<DocKey> verify(final TextCandidates node, final Set<DocKey> docs) {
            return switch (node) {
                case TextCandidates.Word w -> {
                    final Leaf leaf = leaf(w);
                    yield leaf.isSmall() ? retain(docs, leaf.entries) : probe(w.word(), docs);
                }
                case TextCandidates.Start s -> {
                    final Leaf leaf = leaf(s);
                    yield leaf.isSmall() ? retain(docs, leaf.entries) : docs;
                }
                case TextCandidates.AllOf a -> {
                    Set<DocKey> keep = docs;
                    for (final TextCandidates operand : a.operands()) {
                        if (keep.isEmpty()) {
                            break;
                        }
                        keep = verify(operand, keep);
                    }
                    yield keep;
                }
                case TextCandidates.AnyOf o -> {
                    final Set<DocKey> any = new LinkedHashSet<>();
                    for (final TextCandidates operand : o.operands()) {
                        any.addAll(verify(operand, docs));
                        if (any.size() == docs.size()) {
                            break;
                        }
                    }
                    yield any;
                }
            };
        }

        private static Set<DocKey> retain(final Set<DocKey> docs, final Set<DocKey> allowed) {
            final Set<DocKey> keep = new LinkedHashSet<>();
            for (final DocKey d : docs) {
                if (allowed.contains(d)) {
                    keep.add(d);
                }
            }
            return keep;
        }

        /** Which of {@code docs} have an entry for {@code word}: one exact-key lookup each. */
        private Set<DocKey> probe(final String word, final Set<DocKey> docs) {
            final List<Range> ranges = new ArrayList<>(docs.size());
            for (final DocKey d : docs) {
                ranges.add(IndexKeys.entryRange(word, d));
            }
            final Set<DocKey> present = new HashSet<>();
            try (BatchScanner scanner = client.createBatchScanner(table, auths, THREADS)) {
                scanner.setRanges(ranges);
                for (final Map.Entry<Key, Value> e : scanner) {
                    present.add(IndexKeys.docKey(e.getKey()));
                }
            } catch (final Exception e) {
                throw new RyaDAOException(e);
            }
            return retain(docs, present);
        }

        private Leaf leaf(final TextCandidates node) {
            return leaves.computeIfAbsent(node, Leaf::new);
        }

        /** One word or prefix: its entries, kept in memory when there are few of them. */
        private final class Leaf {
            private final Range range;
            private final boolean prefix;
            private Set<DocKey> entries;
            private boolean probed;

            Leaf(final TextCandidates node) {
                if (node instanceof TextCandidates.Word w) {
                    this.range = IndexKeys.termRange(w.word(), predicate);
                    this.prefix = false;
                } else {
                    this.range = IndexKeys.prefixRange(((TextCandidates.Start) node).prefix());
                    this.prefix = true;
                }
            }

            boolean isSmall() {
                probe();
                return entries != null;
            }

            double estimate() {
                return isSmall() ? entries.size() : Double.POSITIVE_INFINITY;
            }

            Iterator<DocKey> stream() {
                return isSmall() ? entries.iterator() : scan();
            }

            private void probe() {
                if (probed) {
                    return;
                }
                probed = true;
                final Set<DocKey> seen = new LinkedHashSet<>();
                try (Scanner scanner = client.createScanner(table, auths)) {
                    scanner.setRange(range);
                    restrictGraphs(scanner);
                    final Iterator<DocKey> it = docKeys(scanner.iterator(), prefix);
                    while (it.hasNext() && seen.size() <= PROBE_LIMIT) {
                        seen.add(it.next());
                    }
                    if (seen.size() <= PROBE_LIMIT) {
                        entries = seen;
                    }
                } catch (final Exception e) {
                    throw new RyaDAOException(e);
                }
            }

            private Iterator<DocKey> scan() {
                final Scanner scanner = scanner();
                scanner.setRange(range);
                final Iterator<DocKey> keys = docKeys(scanner.iterator(), prefix);
                // Entries of one word that differ only in visibility are adjacent; a prefix spans words.
                return prefix ? distinct(keys) : withoutAdjacentRepeats(keys);
            }
        }

        // ---- plumbing ----

        private Scanner scanner() {
            try {
                final Scanner scanner = client.createScanner(table, auths);
                restrictGraphs(scanner);
                scanners.add(scanner);
                return scanner;
            } catch (final Exception e) {
                throw new RyaDAOException(e);
            }
        }

        private BatchScanner batchScanner() {
            try {
                final BatchScanner scanner = client.createBatchScanner(table, auths, THREADS);
                restrictGraphs(scanner);
                scanners.add(scanner);
                return scanner;
            } catch (final Exception e) {
                throw new RyaDAOException(e);
            }
        }

        private void restrictGraphs(final ScannerBase scanner) {
            if (graphs != null) {
                for (final String g : graphs) {
                    scanner.fetchColumnFamily(new Text(g));
                }
            }
        }

        /** Index entries as document keys, dropping other predicates when the range can't. */
        private Iterator<DocKey> docKeys(final Iterator<Map.Entry<Key, Value>> entries, final boolean checkPredicate) {
            return new Iterator<>() {
                private DocKey next;

                @Override
                public boolean hasNext() {
                    while (next == null && entries.hasNext()) {
                        final DocKey d = IndexKeys.docKey(entries.next().getKey());
                        if (!checkPredicate || predicate == null || predicate.equals(d.predicate())) {
                            next = d;
                        }
                    }
                    return next != null;
                }

                @Override
                public DocKey next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    final DocKey d = next;
                    next = null;
                    return d;
                }
            };
        }
    }

    private static Iterator<DocKey> distinct(final Iterator<DocKey> in) {
        final Set<DocKey> seen = new HashSet<>();
        return filter(in, seen::add);
    }

    private static Iterator<DocKey> withoutAdjacentRepeats(final Iterator<DocKey> in) {
        final DocKey[] last = new DocKey[1];
        return filter(in, d -> {
            final boolean fresh = !d.equals(last[0]);
            last[0] = d;
            return fresh;
        });
    }

    private static Iterator<DocKey> filter(final Iterator<DocKey> in, final java.util.function.Predicate<DocKey> keep) {
        return new Iterator<>() {
            private DocKey next;

            @Override
            public boolean hasNext() {
                while (next == null && in.hasNext()) {
                    final DocKey d = in.next();
                    if (keep.test(d)) {
                        next = d;
                    }
                }
                return next != null;
            }

            @Override
            public DocKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                final DocKey d = next;
                next = null;
                return d;
            }
        };
    }

    private static Iterator<DocKey> concat(final List<Iterator<DocKey>> parts) {
        final Iterator<Iterator<DocKey>> outer = parts.iterator();
        return new Iterator<>() {
            private Iterator<DocKey> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && outer.hasNext()) {
                    current = outer.next();
                }
                return current.hasNext();
            }

            @Override
            public DocKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
    }
}
