package io.github.dgreco.rya.sparql;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.eclipse.rdf4j.common.lang.FileFormat;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/** Picks a result format from an HTTP {@code Accept} header. */
final class ContentNegotiation {

    private ContentNegotiation() {
    }

    /**
     * @param accept the Accept header, may be null.
     * @param supported formats the server can write; the first one is the default.
     * @return the best supported format, the default when anything is acceptable, or empty
     *         when nothing supported is acceptable.
     */
    static <F extends FileFormat> Optional<F> choose(final String accept, final List<F> supported) {
        final F preferred = supported.get(0);
        if (accept == null || accept.isBlank()) {
            return Optional.of(preferred);
        }
        final List<MediaType> requested;
        try {
            requested = new ArrayList<>(MediaType.parseMediaTypes(accept));
        } catch (final InvalidMediaTypeException e) {
            return Optional.of(preferred);
        }
        // Highest quality first; List.sort is stable, so equal qualities keep the client's order.
        requested.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
        for (final MediaType range : requested) {
            if (range.getQualityValue() <= 0) {
                continue;
            }
            if (accepts(range, preferred)) {
                return Optional.of(preferred);
            }
            for (final F format : supported) {
                if (accepts(range, format)) {
                    return Optional.of(format);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean accepts(final MediaType range, final FileFormat format) {
        for (final String mime : format.getMIMETypes()) {
            try {
                if (range.includes(MediaType.parseMediaType(mime))) {
                    return true;
                }
            } catch (final InvalidMediaTypeException e) {
                // ignore malformed registry entries
            }
        }
        return false;
    }

    /** @return the Content-Type to send for a format, with a charset for text formats. */
    static String contentType(final FileFormat format) {
        return format.hasCharset()
                ? format.getDefaultMIMEType() + ";charset=" + format.getCharset().name()
                : format.getDefaultMIMEType();
    }
}
