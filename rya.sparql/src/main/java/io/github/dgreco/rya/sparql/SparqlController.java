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

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.accumulo.core.security.ColumnVisibility;
import io.github.dgreco.rya.rdftriplestore.RdfCloudTripleStoreConnection;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.Operation;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat;
import org.eclipse.rdf4j.query.resultio.QueryResultIO;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

/**
 * SPARQL 1.1 Protocol endpoint (https://www.w3.org/TR/sparql11-protocol/).
 *
 * <ul>
 *   <li>Query: {@code GET /sparql?query=...}, {@code POST} with a {@code query} form field, or
 *       {@code POST} with an {@code application/sparql-query} body.</li>
 *   <li>Update: {@code POST} with an {@code update} form field, or {@code POST} with an
 *       {@code application/sparql-update} body.</li>
 *   <li>Dataset: {@code default-graph-uri} / {@code named-graph-uri} for queries,
 *       {@code using-graph-uri} / {@code using-named-graph-uri} for updates.</li>
 *   <li>Rya extensions: {@code auths} (comma-separated) narrows the configured read
 *       authorizations; {@code visibility} labels inserted statements when enabled;
 *       {@code timeout} (seconds) replaces the default time limit, up to the configured maximum.</li>
 * </ul>
 */
@RestController
@RequestMapping("/sparql")
public class SparqlController {
    static final String SPARQL_QUERY = "application/sparql-query";
    static final String SPARQL_UPDATE = "application/sparql-update";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final List<TupleQueryResultFormat> TUPLE_FORMATS = List.of(
            TupleQueryResultFormat.JSON, TupleQueryResultFormat.SPARQL, TupleQueryResultFormat.CSV,
            TupleQueryResultFormat.TSV);
    private static final List<BooleanQueryResultFormat> BOOLEAN_FORMATS = List.of(
            BooleanQueryResultFormat.JSON, BooleanQueryResultFormat.SPARQL);
    private static final List<RDFFormat> GRAPH_FORMATS = List.of(
            RDFFormat.TURTLE, RDFFormat.NTRIPLES, RDFFormat.NQUADS, RDFFormat.JSONLD, RDFFormat.RDFXML,
            RDFFormat.TRIG);

    private final SailRepository repository;
    private final RyaProperties.Sparql settings;

    public SparqlController(final SailRepository repository, final RyaProperties props) {
        this.repository = repository;
        this.settings = props.sparql();
    }

    @GetMapping
    public void get(@RequestParam final MultiValueMap<String, String> params,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) final String accept,
            final HttpServletResponse response) throws IOException {
        if (params.containsKey("update")) {
            throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "Updates must be sent with POST");
        }
        query(single(params, "query"), params, accept, response);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> postForm(@RequestParam final MultiValueMap<String, String> params,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) final String accept,
            final HttpServletResponse response) throws IOException {
        final boolean hasQuery = params.containsKey("query");
        final boolean hasUpdate = params.containsKey("update");
        if (hasQuery == hasUpdate) {
            throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "Send exactly one of 'query' or 'update'");
        }
        if (hasQuery) {
            query(single(params, "query"), params, accept, response);
            return null;
        }
        update(single(params, "update"), params);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(consumes = SPARQL_QUERY)
    public void postQuery(@RequestBody final String query, @RequestParam final MultiValueMap<String, String> params,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) final String accept,
            final HttpServletResponse response) throws IOException {
        query(query, params, accept, response);
    }

    @PostMapping(consumes = SPARQL_UPDATE)
    public ResponseEntity<Void> postUpdate(@RequestBody final String update,
            @RequestParam final MultiValueMap<String, String> params) {
        update(update, params);
        return ResponseEntity.noContent().build();
    }

    private void query(final String queryString, final MultiValueMap<String, String> params, final String accept,
            final HttpServletResponse response) throws IOException {
        try (SailRepositoryConnection conn = repository.getConnection()) {
            applySecurity(conn, params, false);
            final Query query = conn.prepareQuery(QueryLanguage.SPARQL, queryString);
            limitTime(query, params);
            final SimpleDataset dataset = dataset(params, "default-graph-uri", "named-graph-uri");
            if (dataset != null) {
                query.setDataset(dataset);
            }

            if (query instanceof TupleQuery tuple) {
                final TupleQueryResultFormat format = ContentNegotiation.choose(accept, TUPLE_FORMATS)
                        .orElseThrow(() -> notAcceptable(TUPLE_FORMATS));
                response.setContentType(ContentNegotiation.contentType(format));
                final OutputStream out = response.getOutputStream();
                tuple.evaluate(BlankNodeOutput.wrap(QueryResultIO.createTupleWriter(format, out)));
            } else if (query instanceof BooleanQuery ask) {
                final BooleanQueryResultFormat format = ContentNegotiation.choose(accept, BOOLEAN_FORMATS)
                        .orElseThrow(() -> notAcceptable(BOOLEAN_FORMATS));
                final boolean result = ask.evaluate();
                response.setContentType(ContentNegotiation.contentType(format));
                QueryResultIO.writeBoolean(result, format, response.getOutputStream());
            } else if (query instanceof GraphQuery graph) {
                final RDFFormat format = ContentNegotiation.choose(accept, GRAPH_FORMATS)
                        .orElseThrow(() -> notAcceptable(GRAPH_FORMATS));
                response.setContentType(ContentNegotiation.contentType(format));
                graph.evaluate(BlankNodeOutput.wrap(Rio.createWriter(format, response.getOutputStream())));
            } else {
                throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "Unsupported query form");
            }
            response.flushBuffer();
        }
    }

    private void update(final String updateString, final MultiValueMap<String, String> params) {
        try (SailRepositoryConnection conn = repository.getConnection()) {
            applySecurity(conn, params, true);
            final Update update = conn.prepareUpdate(QueryLanguage.SPARQL, updateString);
            limitTime(update, params);
            final SimpleDataset dataset = dataset(params, "using-graph-uri", "using-named-graph-uri");
            if (dataset != null) {
                update.setDataset(dataset);
            }
            update.execute();
        }
    }

    /** Applies the read authorizations and, for updates, the visibility of written statements. */
    private void applySecurity(final SailRepositoryConnection conn, final MultiValueMap<String, String> params,
            final boolean write) {
        if (!(conn.getSailConnection() instanceof RdfCloudTripleStoreConnection<?> rya)) {
            throw new IllegalStateException("Not a Rya repository");
        }
        final String auths = params.getFirst("auths");
        if (auths != null) {
            final List<String> requested = new ArrayList<>();
            for (final String a : auths.split(",")) {
                if (!a.isBlank()) {
                    requested.add(a.trim());
                }
            }
            if (!settings.authorizations().containsAll(requested)) {
                throw new SparqlRequestException(HttpStatus.FORBIDDEN,
                        "'auths' may only narrow the endpoint's configured authorizations");
            }
            rya.getConf().setAuths(requested.toArray(new String[0]));
        }
        final String visibility = params.getFirst("visibility");
        if (visibility != null) {
            if (!write) {
                throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "'visibility' only applies to updates");
            }
            if (!settings.allowRequestVisibility()) {
                throw new SparqlRequestException(HttpStatus.FORBIDDEN, "Per-request 'visibility' is disabled");
            }
            try {
                new ColumnVisibility(visibility);
            } catch (final IllegalArgumentException e) {
                throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "Invalid visibility expression: " + visibility);
            }
            rya.getConf().setCv(visibility);
        }
    }

    /**
     * Sets the time limit: the request's {@code timeout} parameter (whole seconds, at most the
     * configured maximum) or else the configured default.
     */
    private void limitTime(final Operation operation, final MultiValueMap<String, String> params) {
        long seconds = settings.queryTimeout().toSeconds();
        final String requested = params.getFirst("timeout");
        if (requested != null && !requested.isBlank()) {
            try {
                seconds = Long.parseLong(requested.trim());
            } catch (final NumberFormatException e) {
                seconds = 0;
            }
            if (seconds < 1) {
                throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "'timeout' must be a whole number of seconds, at least 1");
            }
            final long max = settings.maxQueryTimeout().toSeconds();
            if (max > 0 && seconds > max) {
                throw new SparqlRequestException(HttpStatus.FORBIDDEN, "'timeout' may be at most " + max + " seconds");
            }
        }
        if (seconds > 0) {
            operation.setMaxExecutionTime((int) Math.min(seconds, Integer.MAX_VALUE));
        }
    }

    private static SimpleDataset dataset(final MultiValueMap<String, String> params, final String defaultParam,
            final String namedParam) {
        final List<String> defaults = params.getOrDefault(defaultParam, List.of());
        final List<String> named = params.getOrDefault(namedParam, List.of());
        if (defaults.isEmpty() && named.isEmpty()) {
            return null;
        }
        final SimpleDataset dataset = new SimpleDataset();
        for (final String g : defaults) {
            dataset.addDefaultGraph(iri(g, defaultParam));
        }
        for (final String g : named) {
            dataset.addNamedGraph(iri(g, namedParam));
        }
        return dataset;
    }

    private static IRI iri(final String value, final String param) {
        try {
            return VF.createIRI(value);
        } catch (final IllegalArgumentException e) {
            throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "'" + param + "' is not an absolute IRI: " + value);
        }
    }

    private static String single(final MultiValueMap<String, String> params, final String name) {
        final List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "Missing '" + name + "' parameter");
        }
        if (values.size() > 1) {
            throw new SparqlRequestException(HttpStatus.BAD_REQUEST, "Only one '" + name + "' parameter is allowed");
        }
        return values.get(0);
    }

    private static SparqlRequestException notAcceptable(final List<? extends org.eclipse.rdf4j.common.lang.FileFormat> formats) {
        final StringBuilder sb = new StringBuilder("None of the requested media types is available. Supported:");
        for (final org.eclipse.rdf4j.common.lang.FileFormat f : formats) {
            sb.append(' ').append(f.getDefaultMIMEType());
        }
        return new SparqlRequestException(HttpStatus.NOT_ACCEPTABLE, sb.toString());
    }
}
