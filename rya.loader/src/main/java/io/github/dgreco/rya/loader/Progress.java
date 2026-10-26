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

/**
 * Which blocks of a load are safely written: a block counts only after a flush that followed its
 * write. Saved atomically (write a temporary file, then rename), with a fingerprint of the options
 * so a resume never mixes two different loads.
 */
public final class Progress {
    private final Path file;
    private final String fingerprint;
    private final long blocks;
    private final BitSet committed;
    private long committedTriples;

    private Progress(final Path file, final String fingerprint, final long blocks, final BitSet committed,
            final long committedTriples) {
        this.file = file;
        this.fingerprint = fingerprint;
        this.blocks = blocks;
        this.committed = committed;
        this.committedTriples = committedTriples;
    }

    /**
     * @param resume continue from the file (it must match); otherwise start fresh, refusing to
     *        overwrite an existing file
     */
    public static Progress open(final Path file, final String fingerprint, final long blocks, final boolean resume)
            throws IOException {
        if (blocks > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("too many blocks for one process: use a larger --block-size or more --parts");
        }
        if (!resume) {
            if (Files.exists(file)) {
                throw new IllegalArgumentException("progress file " + file + " exists: add --resume to continue that "
                        + "load, or delete the file to start over");
            }
            final Progress p = new Progress(file, fingerprint, blocks, new BitSet(), 0);
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
        if (!fingerprint.equals(props.getProperty("fingerprint"))) {
            throw new IllegalArgumentException("progress file " + file + " belongs to a load with other options");
        }
        final BitSet done = BitSet.valueOf(Base64.getDecoder().decode(props.getProperty("committed", "")));
        return new Progress(file, fingerprint, blocks, done, Long.parseLong(props.getProperty("committedTriples", "0")));
    }

    public synchronized boolean isCommitted(final long block) {
        return committed.get((int) block);
    }

    /** Marks blocks as safely written (their writer was flushed) and saves. */
    public synchronized void commit(final Collection<Long> done, final long triples) throws IOException {
        for (final long b : done) {
            committed.set((int) b);
        }
        committedTriples += triples;
        save();
    }

    /** All blocks below this one are written. */
    public synchronized long watermark() {
        return committed.nextClearBit(0);
    }

    public synchronized long committedBlocks() {
        return committed.cardinality();
    }

    public synchronized long committedTriples() {
        return committedTriples;
    }

    public synchronized boolean complete() {
        return committed.nextClearBit(0) >= blocks;
    }

    public long blocks() {
        return blocks;
    }

    public Path file() {
        return file;
    }

    private void save() throws IOException {
        final Properties props = new Properties();
        props.setProperty("fingerprint", fingerprint);
        props.setProperty("blocks", Long.toString(blocks));
        props.setProperty("committed", Base64.getEncoder().encodeToString(committed.toByteArray()));
        props.setProperty("committedBlocks", Long.toString(committed.cardinality()));
        props.setProperty("committedTriples", Long.toString(committedTriples));
        props.setProperty("watermark", Long.toString(committed.nextClearBit(0)));
        final Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(w, "rya-ng loader progress");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
