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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The labels a read may use. As on Accumulo, a cell is visible when its visibility expression
 * is satisfied by these labels; an unlabelled cell is always visible.
 */
public final class Auths {
    /** No labels: only unlabelled cells. */
    public static final Auths NONE = new Auths(List.of(), false);
    /** Every label the account holds. */
    public static final Auths ALL_HELD = new Auths(List.of(), true);

    private final List<String> labels;
    private final boolean allHeld;

    private Auths(final List<String> labels, final boolean allHeld) {
        this.labels = labels;
        this.allHeld = allHeld;
    }

    /** These labels; none means {@link #NONE}. */
    public static Auths of(final String... labels) {
        if (labels == null || labels.length == 0) {
            return NONE;
        }
        return new Auths(List.copyOf(Arrays.asList(labels)), false);
    }

    /** These labels; none means {@link #NONE}. */
    public static Auths of(final List<String> labels) {
        return labels == null || labels.isEmpty() ? NONE : new Auths(List.copyOf(labels), false);
    }

    /** The labels asked for; empty for {@link #NONE} and {@link #ALL_HELD}. */
    public List<String> labels() {
        return labels;
    }

    /** Whether this means every label the account holds. */
    public boolean isAllHeld() {
        return allHeld;
    }

    /** Whether this means unlabelled cells only. */
    public boolean isNone() {
        return !allHeld && labels.isEmpty();
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Auths a && a.allHeld == allHeld && a.labels.equals(labels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(labels, allHeld);
    }

    @Override
    public String toString() {
        return allHeld ? "all held" : labels.isEmpty() ? "none" : String.join(",", labels);
    }
}
