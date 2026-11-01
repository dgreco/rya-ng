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
package io.github.dgreco.rya.store.spi;

import java.util.ArrayList;
import java.util.List;

/**
 * How to read: with which labels, which columns, and which server-side filters. Immutable;
 * build one with {@link #builder(Auths)}.
 */
public final class ScanSpec {

    /** One column: a family and a qualifier. */
    public record Column(byte[] family, byte[] qualifier) {
    }

    private final Auths auths;
    private final List<byte[]> families;
    private final List<Column> columns;
    private final String rowRegex;
    private final String qualifierRegex;
    private final Long minTimestamp;
    private final Long maxTimestamp;
    private final Integer batchSize;
    private final int threads;
    private final List<ServerIterator> iterators;

    private ScanSpec(final Builder b) {
        this.auths = b.auths;
        this.families = List.copyOf(b.families);
        this.columns = List.copyOf(b.columns);
        this.rowRegex = b.rowRegex;
        this.qualifierRegex = b.qualifierRegex;
        this.minTimestamp = b.minTimestamp;
        this.maxTimestamp = b.maxTimestamp;
        this.batchSize = b.batchSize;
        this.threads = b.threads;
        this.iterators = List.copyOf(b.iterators);
    }

    /** A spec with these labels and nothing else. */
    public static ScanSpec of(final Auths auths) {
        return builder(auths).build();
    }

    public static Builder builder(final Auths auths) {
        return new Builder(auths);
    }

    /** The labels the read may use. */
    public Auths auths() {
        return auths;
    }

    /** Only cells of these families (Accumulo's {@code fetchColumnFamily}); empty for any. */
    public List<byte[]> families() {
        return families;
    }

    /** Only cells of these columns (Accumulo's {@code fetchColumn}); empty for any. */
    public List<Column> columns() {
        return columns;
    }

    /** Only rows matching this Java regular expression as a whole; null for any. */
    public String rowRegex() {
        return rowRegex;
    }

    /** Only cells whose qualifier matches this Java regular expression as a whole; null for any. */
    public String qualifierRegex() {
        return qualifierRegex;
    }

    /** Only cells at or after this timestamp; null for no bound. */
    public Long minTimestamp() {
        return minTimestamp;
    }

    /** Only cells at or before this timestamp; null for no bound. */
    public Long maxTimestamp() {
        return maxTimestamp;
    }

    /** Cells fetched per round trip, a hint; null for the backend's default. */
    public Integer batchSize() {
        return batchSize;
    }

    /** Threads reading the ranges of a batch scan. */
    public int threads() {
        return threads;
    }

    /** Extra server-side iterators (backends with {@link Capabilities#scanIterators()} only). */
    public List<ServerIterator> iterators() {
        return iterators;
    }

    public Builder toBuilder() {
        final Builder b = new Builder(auths);
        b.families.addAll(families);
        b.columns.addAll(columns);
        b.rowRegex = rowRegex;
        b.qualifierRegex = qualifierRegex;
        b.minTimestamp = minTimestamp;
        b.maxTimestamp = maxTimestamp;
        b.batchSize = batchSize;
        b.threads = threads;
        b.iterators.addAll(iterators);
        return b;
    }

    /** Builds a {@link ScanSpec}. */
    public static final class Builder {
        private final Auths auths;
        private final List<byte[]> families = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();
        private String rowRegex;
        private String qualifierRegex;
        private Long minTimestamp;
        private Long maxTimestamp;
        private Integer batchSize;
        private int threads = 4;
        private final List<ServerIterator> iterators = new ArrayList<>();

        private Builder(final Auths auths) {
            this.auths = auths == null ? Auths.NONE : auths;
        }

        public Builder family(final byte[] family) {
            families.add(family);
            return this;
        }

        public Builder column(final byte[] family, final byte[] qualifier) {
            columns.add(new Column(family, qualifier));
            return this;
        }

        public Builder rowRegex(final String regex) {
            this.rowRegex = regex;
            return this;
        }

        public Builder qualifierRegex(final String regex) {
            this.qualifierRegex = regex;
            return this;
        }

        public Builder timestamps(final Long min, final Long max) {
            this.minTimestamp = min;
            this.maxTimestamp = max;
            return this;
        }

        public Builder batchSize(final Integer n) {
            this.batchSize = n;
            return this;
        }

        public Builder threads(final int n) {
            this.threads = n;
            return this;
        }

        public Builder iterator(final ServerIterator it) {
            iterators.add(it);
            return this;
        }

        public ScanSpec build() {
            return new ScanSpec(this);
        }
    }
}
