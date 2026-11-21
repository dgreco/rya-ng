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
package org.tripatha.spark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The job's own options are taken out first; everything else is the loader's, values included. */
class SparkBuildOptionsTest {
    private static SparkBuild.Options parse(final String... args) {
        return SparkBuild.Options.parse(args, Map.of());
    }

    @Test
    void aLoaderValueThatNamesAnOptionOfTheJobStaysTheLoaders() {
        final SparkBuild.Options o = parse("files", "--table-prefix", "input", "--input", "s3a://b/in", "--work-dir", "tasks",
                "--phase", "write");
        assertEquals("input", o.loader.tablePrefix());
        assertEquals("tasks", o.loader.workDir());
        assertEquals(List.of("s3a://b/in"), o.input);
        assertEquals(Set.of("write"), o.phases);
    }

    @Test
    void optionsTakeTheirValueWithAnEqualsSignOrAfterThem() {
        final SparkBuild.Options o = parse("files", "--table-prefix=t_", "--input=a.nt,b.nq", "--file-size", "0.5", "--distinct",
                "--max-bad-lines=3");
        assertEquals(List.of("a.nt", "b.nq"), o.input);
        assertEquals(0.5, o.fileSizeMb);
        assertTrue(o.distinct);
        assertEquals(3, o.maxBadLines);
    }

    @Test
    void theTasksGetTheDatasetSizeInEntities() {
        final SparkBuild.Options o = parse("generate", "--table-prefix", "t_", "--triples", "10K", "--seed", "7");
        final String[] args = o.executorArgs(o.loader);
        assertEquals("build", args[0]);
        assertTrue(!String.join(" ", args).contains("--triples"));
        assertArrayEquals(new String[] {"--entities", Long.toString(o.loader.genConfig().entities())},
                new String[] {args[args.length - 2], args[args.length - 1]});
    }

    @Test
    void fileOptionsAreForFiles() {
        assertThrows(IllegalArgumentException.class, () -> parse("generate", "--table-prefix", "t_", "--input", "x.nt"));
        assertThrows(IllegalArgumentException.class, () -> parse("files", "--table-prefix", "t_", "--triples", "1M"));
        assertThrows(IllegalArgumentException.class, () -> parse("files", "--table-prefix", "t_", "--phase", "sort"));
    }
}
