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

package io.github.dgreco.rya.accumulo.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;

public class AccumuloTraceTextTest {

    @Test
    public void rangesAndIteratorsAreReadable() {
        assertEquals("[a, b)", AccumuloTraceText.range(new Range(new Text("a"), true, new Text("b"), false)));
        assertEquals("[a, b]", AccumuloTraceText.range(new Range(new Text("a"), new Text("b"))));
        assertEquals("[a, a]", AccumuloTraceText.range(Range.exact(new Text("a"))));
        assertEquals("[-∞, +∞)", AccumuloTraceText.range(new Range()).replace("+∞]", "+∞)"));
        final IteratorSetting s = new IteratorSetting(11, "ri", "org.apache.accumulo.core.iterators.user.RegExFilter");
        s.addOption("rowRegex", "x\u0000y");
        assertEquals("11 ri (RegExFilter) rowRegex=x⟨00⟩y", AccumuloTraceText.iterator(s));
    }
}
