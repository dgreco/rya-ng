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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Properties;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressTest {
    @TempDir
    Path dir;

    @Test
    void recordsCommittedBlocksAndResumes() throws Exception {
        final Path file = dir.resolve("sub/load.progress");
        final Progress p = Progress.open(file, "fp", 10, false);
        assertTrue(Files.exists(file));
        assertEquals(0, p.watermark());
        p.commit(List.of(0L, 1L, 3L), 30);
        assertEquals(2, p.watermark());
        assertEquals(3, p.committedBlocks());
        assertFalse(p.complete());

        final Progress resumed = Progress.open(file, "fp", 10, true);
        assertTrue(resumed.isCommitted(3));
        assertFalse(resumed.isCommitted(2));
        assertEquals(2, resumed.watermark());
        assertEquals(30, resumed.committedTriples());
        resumed.commit(LongStream.range(0, 10).boxed().toList(), 70);
        assertTrue(resumed.complete());
        assertEquals(10, Progress.open(file, "fp", 10, true).committedBlocks());
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList(), "no temporary file is left behind");
        }
    }

    @Test
    void blocksFinishingEarlyWaitAboveTheWatermark() throws Exception {
        final Progress p = Progress.open(dir.resolve("load.progress"), "fp", 10, false);
        p.commit(List.of(5L, 3L), 20);
        assertEquals(0, p.watermark());
        assertTrue(p.isCommitted(3));
        assertFalse(p.isCommitted(4));
        p.commit(List.of(0L, 1L, 2L), 30);
        assertEquals(4, p.watermark(), "up to the first missing block");
        p.commit(List.of(4L), 10);
        assertEquals(6, p.watermark(), "5 was already written");
        assertEquals(6, p.committedBlocks());
        p.commit(List.of(2L), 0);
        assertEquals(6, p.committedBlocks(), "a block written twice counts once");
    }

    @Test
    void theFileStaysSmallWhateverTheNumberOfBlocks() throws Exception {
        final Path file = dir.resolve("load.progress");
        final Progress p = Progress.open(file, "fp", 2_000_000, false);
        for (long b = 0; b < 1_000_000; b += 10_000) {
            p.commit(LongStream.range(b, b + 10_000).boxed().toList(), 1);
        }
        p.commit(List.of(1_500_000L), 1);
        assertEquals(1_000_000, p.watermark());
        assertTrue(Files.size(file) < 1_000, "one bit per block would be 125 KB here: " + Files.size(file) + " bytes");
        final Progress resumed = Progress.open(file, "fp", 2_000_000, true);
        assertEquals(1_000_001, resumed.committedBlocks());
        assertTrue(resumed.isCommitted(1_500_000));
        assertFalse(resumed.isCommitted(1_000_000));
    }

    @Test
    void moreBlocksThanAnIntCanCount() throws Exception {
        final long blocks = 3_000_000_000L;
        final Path file = dir.resolve("load.progress");
        final Progress p = Progress.open(file, "fp", blocks, false);
        p.commit(List.of(2_500_000_000L, 0L), 2);
        final Progress resumed = Progress.open(file, "fp", blocks, true);
        assertTrue(resumed.isCommitted(2_500_000_000L));
        assertEquals(1, resumed.watermark());
        assertFalse(resumed.complete());
    }

    @Test
    void readsTheEarlierFormAndRewritesItSoOlderLoadersRefuseIt() throws Exception {
        final Path file = dir.resolve("load.progress");
        final BitSet done = new BitSet();
        done.set(0, 4);
        done.set(7);
        final Properties old = new Properties();
        old.setProperty("fingerprint", "fp");
        old.setProperty("blocks", "10");
        old.setProperty("committed", Base64.getEncoder().encodeToString(done.toByteArray()));
        old.setProperty("committedTriples", "50");
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            old.store(w, null);
        }
        assertThrows(IllegalArgumentException.class, () -> Progress.open(file, "other", 10, true));
        final Progress p = Progress.open(file, "fp", 10, true);
        assertEquals(4, p.watermark());
        assertTrue(p.isCommitted(7));
        assertEquals(5, p.committedBlocks());
        assertEquals(50, p.committedTriples());
        final Properties now = new Properties();
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            now.load(r);
        }
        assertEquals("fp", now.getProperty("load"));
        assertEquals(null, now.getProperty("fingerprint"), "an older loader looks for this key, and refuses the file without it");
        assertEquals("7", now.getProperty("above"));
    }

    @Test
    void refusesToMixLoads() throws Exception {
        final Path file = dir.resolve("load.progress");
        assertThrows(IllegalArgumentException.class, () -> Progress.open(file, "fp", 10, true));
        Progress.open(file, "fp", 10, false);
        assertThrows(IllegalArgumentException.class, () -> Progress.open(file, "fp", 10, false));
        assertThrows(IllegalArgumentException.class, () -> Progress.open(file, "other", 10, true));
    }
}
