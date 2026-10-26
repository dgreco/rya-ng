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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void refusesToMixLoads() throws Exception {
        final Path file = dir.resolve("load.progress");
        assertThrows(IllegalArgumentException.class, () -> Progress.open(file, "fp", 10, true));
        Progress.open(file, "fp", 10, false);
        assertThrows(IllegalArgumentException.class, () -> Progress.open(file, "fp", 10, false));
        assertThrows(IllegalArgumentException.class, () -> Progress.open(file, "other", 10, true));
    }
}
