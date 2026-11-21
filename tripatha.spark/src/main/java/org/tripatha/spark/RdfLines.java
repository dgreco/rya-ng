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

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.hadoop.fs.FileStatus;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;

import org.tripatha.api.domain.RyaStatement;
import org.tripatha.api.resolver.RdfToRyaConversions;

/**
 * Statements of N-Triples and N-Quads lines (RDF 1.2 included), one line at a time, so any part
 * of a file can be read on its own.
 *
 * <p>A blank node label is scoped to its file, but a file is read in parts by different tasks: each
 * label is kept (RDF4J's {@code PRESERVE_BNODE_IDS}) and prefixed with the file's id, so the
 * parts of one file agree on a label and two files never share one. The id depends on the file's
 * path, length and modification time, so a task run again, or the job run again on the same file,
 * gives the same nodes.
 */
public final class RdfLines {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private final String fileId;
    private final long timestamp;
    private final byte[] visibility;
    private final RDFParser parser;
    private final List<Statement> parsed = new ArrayList<>();

    /**
     * @param fileId the file's id ({@link #fileId(FileStatus)})
     * @param timestamp every statement's timestamp: the job's, so a task run twice writes the same cells
     * @param visibility every statement's label expression, or null for none
     */
    public RdfLines(final String fileId, final long timestamp, final byte[] visibility) {
        this.fileId = fileId;
        this.timestamp = timestamp;
        this.visibility = visibility;
        this.parser = Rio.createParser(RDFFormat.NQUADS);
        parser.getParserConfig().set(BasicParserSettings.PRESERVE_BNODE_IDS, true);
        parser.setRDFHandler(new AbstractRDFHandler() {
            @Override
            public void handleStatement(final Statement st) {
                parsed.add(st);
            }
        });
    }

    /** A file's id: its path, length and modification time, hashed. */
    public static String fileId(final FileStatus status) {
        return fileId(status.getPath().toString(), status.getLen(), status.getModificationTime());
    }

    static String fileId(final String path, final long length, final long modified) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((path + "|" + length + "|" + modified).getBytes(StandardCharsets.UTF_8));
            return "f" + HexFormat.of().formatHex(d, 0, 8);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether a line holds no statement: blank, or a comment. */
    public static boolean isBlank(final String line) {
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '#') {
                return true;
            }
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds the statements of one line to {@code out}.
     *
     * @throws org.eclipse.rdf4j.rio.RDFParseException if the line isn't N-Triples or N-Quads
     */
    public void parse(final String line, final List<RyaStatement> out) {
        if (isBlank(line)) {
            return;
        }
        parse(new StringReader(line), out);
    }

    /**
     * Adds the statements of several lines to {@code out}, in one call of the parser (a call
     * per line costs more than the parsing). If one line is bad, nothing is added and the parser's
     * exception is thrown: the caller reads them again one at a time to find it.
     */
    public void parseAll(final List<String> lines, final List<RyaStatement> out) {
        final StringBuilder text = new StringBuilder(lines.size() * 128);
        for (final String line : lines) {
            text.append(line).append('\n');
        }
        parse(new StringReader(text.toString()), out);
    }

    private void parse(final StringReader reader, final List<RyaStatement> out) {
        parsed.clear();
        try {
            parser.parse(reader);
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        for (final Statement st : parsed) {
            final RyaStatement r = RdfToRyaConversions.convertStatement(VF.createStatement((Resource) scope(st.getSubject()),
                    st.getPredicate(), scope(st.getObject()), st.getContext() == null ? null : (Resource) scope(st.getContext())));
            r.setTimestamp(timestamp);
            if (visibility != null) {
                r.setColumnVisibility(visibility.clone());
            }
            out.add(r);
        }
    }

    private Value scope(final Value v) {
        if (v instanceof BNode b) {
            return VF.createBNode(fileId + "-" + b.getID());
        }
        if (v instanceof TripleTerm t) {
            return VF.createTripleTerm((Resource) scope(t.getSubject()), t.getPredicate(), scope(t.getObject()));
        }
        return v;
    }
}
