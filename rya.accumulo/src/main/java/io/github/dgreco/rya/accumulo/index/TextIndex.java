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
package io.github.dgreco.rya.accumulo.index;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import io.github.dgreco.rya.accumulo.index.text.TextAnalyzer;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;

/** Files each text literal under its words. */
public final class TextIndex implements SecondaryIndex {
    private final Set<String> predicates;

    /** @param predicates the predicates to index; empty means all of them */
    public TextIndex(final Collection<String> predicates) {
        this.predicates = Set.copyOf(predicates);
    }

    /** Whether a literal of this datatype is text: what the index covers and {@code text:matches} accepts. */
    public static boolean isText(final IRI datatype) {
        return XSD.STRING.equals(datatype) || RDF.LANGSTRING.equals(datatype);
    }

    /** Whether statements with this predicate are indexed. */
    public boolean covers(final String predicate) {
        return predicates.isEmpty() || predicates.contains(predicate);
    }

    /** The indexed predicates; empty means all. */
    public Set<String> predicates() {
        return predicates;
    }

    @Override
    public IndexType type() {
        return IndexType.TEXT;
    }

    @Override
    public String definition() {
        return TextAnalyzer.VERSION + ";max=" + TextAnalyzer.MAX_TERM + ";preds=" + String.join(" ", new TreeSet<>(predicates));
    }

    @Override
    public Collection<String> terms(final RyaStatement st) {
        if (st.getObject() instanceof RyaIRI || !isText(st.getObject().getDataType())
                || !covers(st.getPredicate().getData()) || st.getObject().getData() == null) {
            return List.of();
        }
        return TextAnalyzer.terms(st.getObject().getData());
    }
}
