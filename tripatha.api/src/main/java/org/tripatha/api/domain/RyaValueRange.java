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
package org.tripatha.api.domain;

import java.util.Arrays;
import java.util.Objects;

import org.eclipse.rdf4j.model.IRI;

/**
 * The stored values of one datatype between two bounds, both included, for a lookup
 * {@code ?s p range}: the rows of {@code po} for {@code p} whose object has this type and whose
 * data lies between the bounds (SCALE-STUDY.md O14).
 *
 * <p>The bounds are the data bytes the type's resolver writes, and either may be open, meaning
 * the type's first or last value. No lexical form encodes some of those: the NaN a double range
 * keeps above infinity, or an {@code xsd:integer} longer than any given one.
 *
 * <p>Only for callers that check every returned value themselves: a range may hold more values
 * than the condition it was made for.
 */
public final class RyaValueRange extends RyaType {
    private final byte marker;
    private final byte[] start;
    private final byte[] stop;

    /**
     * @param datatype the values' datatype
     * @param marker the marker byte its resolver writes after {@code 01}
     * @param start the first data included, or null from the type's first value
     * @param stop the last data included, or null up to the type's last value
     * @param description the bounds as text, for plans and traces
     */
    public RyaValueRange(final IRI datatype, final byte marker, final byte[] start, final byte[] stop,
            final String description) {
        super(Objects.requireNonNull(datatype), description);
        this.marker = marker;
        this.start = start == null ? null : start.clone();
        this.stop = stop == null ? null : stop.clone();
    }

    public byte getMarker() {
        return marker;
    }

    /** @return the first data included, or null from the type's first value */
    public byte[] getStartData() {
        return start == null ? null : start.clone();
    }

    /** @return the last data included, or null up to the type's last value */
    public byte[] getStopData() {
        return stop == null ? null : stop.clone();
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof RyaValueRange r && super.equals(r) && marker == r.marker && Arrays.equals(start, r.start)
                && Arrays.equals(stop, r.stop);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), marker, Arrays.hashCode(start), Arrays.hashCode(stop));
    }

    @Override
    public String toString() {
        return "RyaValueRange{" + getDataType() + " " + getData() + "}";
    }
}
