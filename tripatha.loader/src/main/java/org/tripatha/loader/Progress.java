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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collection;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Which blocks of a load are safely written: a block counts only after a flush that followed its
 * write. Saved atomically (write a temporary file, then rename), with a fingerprint of the options
 * so a resume never mixes two different loads.
 * <p>
 * Workers claim blocks in increasing order, so the written blocks are every block below a
 * watermark plus the few above it that finished early. That is what the file holds, a few bytes
 * at any size: one bit per block would be 17 MB at 1T, rewritten at every commit. Files of the
 * earlier form (a bit set under {@code fingerprint}) are read and rewritten in this one, under
 * {@code load}, which an older loader doesn't know and so refuses instead of starting over: that
 * would write the counters of the blocks already done a second time.
 */
public final class Progress {
    private final Path file;
    private final String fingerprint;
    private final long blocks;
    /** Every block below it is written. */
    private long watermark;
    /** Written blocks above the watermark. */
    private final TreeSet<Long> above;
    private long committedTriples;

    private Progress(final Path file, final String fingerprint, final long blocks, final long watermark,
            final TreeSet<Long> above, final long committedTriples) {
        this.file = file;
        this.fingerprint = fingerprint;
        this.blocks = blocks;
        this.watermark = watermark;
        this.above = above;
        this.committedTriples = committedTriples;
        advance();
    }

    /**
     * @param resume continue from the file (it must match); otherwise start fresh, refusing to
     *        overwrite an existing file
     */
    public static Progress open(final Path file, final String fingerprint, final long blocks, final boolean resume)
            throws IOException {
        if (!resume) {
            if (Files.exists(file)) {
                throw new IllegalArgumentException("progress file " + file + " exists: add --resume to continue that "
                        + "load, or delete the file to start over");
            }
            final Progress p = new Progress(file, fingerprint, blocks, 0, new TreeSet<>(), 0);
            p.save();
            return p;
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("--resume: no progress file " + file);
        }
        final Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        final String load = props.getProperty("load");
        if (load == null && props.getProperty("fingerprint") != null) {
            // The earlier form: one bit per block.
            if (!fingerprint.equals(props.getProperty("fingerprint"))) {
                throw new IllegalArgumentException("progress file " + file + " belongs to a load with other options");
            }
            final BitSet done = BitSet.valueOf(Base64.getDecoder().decode(props.getProperty("committed", "")));
            final long mark = done.nextClearBit(0);
            final TreeSet<Long> rest = new TreeSet<>();
            for (int b = done.nextSetBit((int) mark); b >= 0; b = done.nextSetBit(b + 1)) {
                rest.add((long) b);
            }
            final Progress p = new Progress(file, fingerprint, blocks, mark, rest,
                    Long.parseLong(props.getProperty("committedTriples", "0")));
            p.save();
            return p;
        }
        if (!fingerprint.equals(load)) {
            throw new IllegalArgumentException("progress file " + file + " belongs to a load with other options");
        }
        final TreeSet<Long> rest = new TreeSet<>();
        for (final String b : props.getProperty("above", "").split(",")) {
            if (!b.isBlank()) {
                rest.add(Long.parseLong(b.trim()));
            }
        }
        return new Progress(file, fingerprint, blocks, Long.parseLong(props.getProperty("watermark", "0")), rest,
                Long.parseLong(props.getProperty("committedTriples", "0")));
    }

    public synchronized boolean isCommitted(final long block) {
        return block < watermark || above.contains(block);
    }

    /** Marks blocks as safely written (their writer was flushed) and saves. */
    public synchronized void commit(final Collection<Long> done, final long triples) throws IOException {
        for (final long b : done) {
            if (b >= watermark) {
                above.add(b);
            }
        }
        advance();
        committedTriples += triples;
        save();
    }

    private void advance() {
        while (!above.isEmpty() && above.first() == watermark) {
            above.pollFirst();
            watermark++;
        }
    }

    /** All blocks below this one are written. */
    public synchronized long watermark() {
        return watermark;
    }

    public synchronized long committedBlocks() {
        return watermark + above.size();
    }

    public synchronized long committedTriples() {
        return committedTriples;
    }

    public synchronized boolean complete() {
        return watermark >= blocks;
    }

    public long blocks() {
        return blocks;
    }

    public Path file() {
        return file;
    }

    private void save() throws IOException {
        final Properties props = new Properties();
        props.setProperty("load", fingerprint);
        props.setProperty("blocks", Long.toString(blocks));
        props.setProperty("watermark", Long.toString(watermark));
        final StringJoiner rest = new StringJoiner(",");
        above.forEach(b -> rest.add(Long.toString(b)));
        props.setProperty("above", rest.toString());
        props.setProperty("committedBlocks", Long.toString(watermark + above.size()));
        props.setProperty("committedTriples", Long.toString(committedTriples));
        final Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(w, "Tripatha loader progress");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
