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
package io.github.dgreco.rya.rdftriplestore.function;

import java.util.concurrent.ExecutionException;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

import io.github.dgreco.rya.accumulo.index.TextIndex;
import io.github.dgreco.rya.accumulo.index.text.TextQuery;
import io.github.dgreco.rya.accumulo.index.text.TextQueryException;

/**
 * {@code text:matches(?literal, "query")}: true when a string literal satisfies a full-text query
 * (see {@link TextQuery} for the syntax). This is the exact check; the text index, when enabled,
 * only narrows down which literals are checked.
 * <p>
 * Only {@code xsd:string} and language-tagged literals are text; anything else is an evaluation
 * error, which filters the row out.
 */
public class TextMatchesFunction implements Function {
    private static final LoadingCache<String, TextQuery> QUERIES = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .build(CacheLoader.from(TextQuery::parse));

    @Override
    public String getURI() {
        return RyaText.MATCHES.stringValue();
    }

    @Override
    public Value evaluate(final ValueFactory vf, final Value... args) throws ValueExprEvaluationException {
        if (args.length != 2) {
            throw new ValueExprEvaluationException("text:matches takes 2 arguments, got " + args.length);
        }
        final String query = textOf(args[1], "query");
        final String text = textOf(args[0], "first argument");
        try {
            return vf.createLiteral(parse(query).matches(text));
        } catch (final TextQueryException e) {
            throw new ValueExprEvaluationException(e.getMessage(), e);
        }
    }

    /** Parses a query, as {@code text:matches} would. */
    public static TextQuery parse(final String query) {
        try {
            return QUERIES.get(query);
        } catch (final ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof TextQueryException t) {
                throw t;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static String textOf(final Value value, final String what) {
        if (value instanceof Literal literal && TextIndex.isText(literal.getDatatype())) {
            return literal.getLabel();
        }
        throw new ValueExprEvaluationException("text:matches: the " + what + " must be a string literal, got " + value);
    }
}
