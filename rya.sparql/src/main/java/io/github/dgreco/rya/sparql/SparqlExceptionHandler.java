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

import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryInterruptedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns failures into plain-text error responses with the right status. */
@RestControllerAdvice
public class SparqlExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(SparqlExceptionHandler.class);

    @ExceptionHandler(SparqlRequestException.class)
    ResponseEntity<String> refused(final SparqlRequestException e) {
        return text(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(MalformedQueryException.class)
    ResponseEntity<String> malformed(final MalformedQueryException e) {
        return text(HttpStatus.BAD_REQUEST, "Malformed query: " + e.getMessage());
    }

    @ExceptionHandler(QueryInterruptedException.class)
    ResponseEntity<String> timedOut(final QueryInterruptedException e) {
        return text(HttpStatus.SERVICE_UNAVAILABLE, "Query timed out: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> failed(final Exception e) {
        log.error("SPARQL request failed", e);
        return text(HttpStatus.INTERNAL_SERVER_ERROR, "Request failed: " + e.getMessage());
    }

    private static ResponseEntity<String> text(final HttpStatus status, final String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message + "\n");
    }
}
