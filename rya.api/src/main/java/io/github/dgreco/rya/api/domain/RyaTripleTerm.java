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
package io.github.dgreco.rya.api.domain;

import java.util.Objects;

import io.github.dgreco.rya.api.resolver.RyaTypeResolverException;
import io.github.dgreco.rya.api.resolver.UnsupportedValueException;
import io.github.dgreco.rya.api.resolver.impl.TripleTermResolver;

/**
 * An RDF 1.2 triple term, {@code <<( s p o )>>}: a triple used as the object of a statement or
 * of another triple term.
 *
 * <p>Its {@link #getData() data} is the encoded form the store writes (see
 * {@link TripleTermResolver}), so equality, ordering, the statistics keys and the row all agree.
 * It is immutable.
 */
public final class RyaTripleTerm extends RyaType {
    private final RyaIRI subject;
    private final RyaIRI predicate;
    private final RyaType object;

    /**
     * @param subject the inner subject; a blank node is an IRI in {@link RyaSchema#BNODE_NAMESPACE}.
     * @param predicate the inner predicate.
     * @param object the inner object, possibly another triple term.
     * @throws UnsupportedValueException if a part can't be encoded.
     */
    public RyaTripleTerm(final RyaIRI subject, final RyaIRI predicate, final RyaType object) {
        this(subject, predicate, object, encode(subject, predicate, object));
    }

    /** For {@link TripleTermResolver}, which already has the encoded form it read. */
    public RyaTripleTerm(final RyaIRI subject, final RyaIRI predicate, final RyaType object, final String data) {
        super(RyaSchema.TRIPLE_TERM, data);
        this.subject = Objects.requireNonNull(subject);
        this.predicate = Objects.requireNonNull(predicate);
        this.object = Objects.requireNonNull(object);
    }

    private static String encode(final RyaIRI subject, final RyaIRI predicate, final RyaType object) {
        if (subject == null || predicate == null || object == null || object instanceof RyaRange) {
            throw new UnsupportedValueException("A triple term needs a subject, a predicate and an object");
        }
        try {
            return TripleTermResolver.encode(subject, predicate, object);
        } catch (final RyaTypeResolverException e) {
            throw new UnsupportedValueException("A triple term cannot be stored: " + e.getMessage());
        }
    }

    public RyaIRI getSubject() {
        return subject;
    }

    public RyaIRI getPredicate() {
        return predicate;
    }

    public RyaType getObject() {
        return object;
    }

    @Override
    public void setData(final String data) {
        throw new UnsupportedOperationException("A triple term is immutable");
    }

    @Override
    public void setDataType(final org.eclipse.rdf4j.model.IRI dataType) {
        throw new UnsupportedOperationException("A triple term is immutable");
    }

    @Override
    public void setLanguage(final String language) {
        throw new UnsupportedOperationException("A triple term is immutable");
    }

    @Override
    public String toString() {
        return "RyaTripleTerm{" + subject.getData() + ' ' + predicate.getData() + ' ' + object + '}';
    }
}
