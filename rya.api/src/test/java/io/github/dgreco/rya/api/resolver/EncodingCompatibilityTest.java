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
package io.github.dgreco.rya.api.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TABLE_LAYOUT;
import io.github.dgreco.rya.api.domain.RyaIRI;
import io.github.dgreco.rya.api.domain.RyaStatement;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.resolver.triple.TripleRowResolver;
import io.github.dgreco.rya.api.resolver.triple.impl.WholeRowHashedTripleResolver;
import io.github.dgreco.rya.api.resolver.triple.impl.WholeRowTripleResolver;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the bytes rya-ng writes, so that a change to the on-disk format cannot go unnoticed: the
 * other encoding tests are round trips, which a changed format still passes.
 *
 * <p>The expected values are what the released code writes. Stores hold these bytes, so an
 * expected value here must never be edited to make a change pass; new encodings get new cases.
 */
public class EncodingCompatibilityTest {
    private static final IRI CUSTOM = SimpleValueFactory.getInstance().createIRI("urn:custom#type");
    private static final HexFormat HEX = HexFormat.of();

    /** One value of every resolver, as {@code data ++ type suffix}. */
    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("iri", new RyaIRI("http://example.org/a#b"), "687474703a2f2f6578616d706c652e6f72672f6123620102"),
                Arguments.of("anyURI", new RyaType(XMLSchema.ANYURI, "urn:a"), "75726e3a610102"),
                Arguments.of("string", new RyaType(XMLSchema.STRING, "hello world"), "68656c6c6f20776f726c640103"),
                Arguments.of("string-empty", new RyaType(XMLSchema.STRING, ""), "0103"),
                Arguments.of("string-unicode", new RyaType(XMLSchema.STRING, "✓ 日本 😀"), "e29c9320e697a5e69cac20f09f98800103"),
                Arguments.of("long", new RyaType(XMLSchema.LONG, "42"), "383030303030303030303030303032610104"),
                Arguments.of("long-negative", new RyaType(XMLSchema.LONG, "-1"), "376666666666666666666666666666660104"),
                Arguments.of("long-min", new RyaType(XMLSchema.LONG, String.valueOf(Long.MIN_VALUE)), "303030303030303030303030303030300104"),
                Arguments.of("integer", new RyaType(XMLSchema.INTEGER, "42"), "383030303030303132610105"),
                Arguments.of("integer-negative", new RyaType(XMLSchema.INTEGER, "-98765432109876543210"), "37666666666666376661613535616232633731616439383131360105"),
                Arguments.of("double", new RyaType(XMLSchema.DOUBLE, "-1.5"), "343030376666666666666666666666660106"),
                Arguments.of("double-zero", new RyaType(XMLSchema.DOUBLE, "0.0"), "383030303030303030303030303030300106"),
                Arguments.of("double-inf", new RyaType(XMLSchema.DOUBLE, "INF"), "666666303030303030303030303030300106"),
                Arguments.of("dateTime", new RyaType(XMLSchema.DATETIME, "2026-10-25T12:30:45.123Z"), "383030303031613137333061623938330107"),
                Arguments.of("custom", new RyaType(CUSTOM, "custom value"), "637573746f6d2076616c75650175726e3a637573746f6d23747970650108"),
                Arguments.of("byte", new RyaType(XMLSchema.BYTE, "-128"), "38300109"),
                Arguments.of("boolean-true", new RyaType(XMLSchema.BOOLEAN, "true"), "31010a"),
                Arguments.of("boolean-false", new RyaType(XMLSchema.BOOLEAN, "false"), "30010a"),
                Arguments.of("float", new RyaType(XMLSchema.FLOAT, "-1.5"), "3430336666666666010b"),
                Arguments.of("short", new RyaType(XMLSchema.SHORT, "-7"), "3766666666666639010c"),
                Arguments.of("int", new RyaType(XMLSchema.INT, "42"), "3830303030303261010d"),
                Arguments.of("int-min", new RyaType(XMLSchema.INT, String.valueOf(Integer.MIN_VALUE)), "3030303030303030010d"),
                Arguments.of("langString", new RyaType(RDF.LANGSTRING, "bonjour", "fr"), "626f6e6a6f757240667201687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108"),
                Arguments.of("langString-region", new RyaType(RDF.LANGSTRING, "colour", "en-GB"), "636f6c6f757240656e2d474201687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    public void valueBytesAreUnchanged(final String name, final RyaType value, final String expectedHex) throws Exception {
        assertEquals(expectedHex, HEX.formatHex(RyaContext.getInstance().serialize(value)), name);
    }

    private static final Map<String, RyaType> ROW_OBJECTS = new LinkedHashMap<>();
    static {
        ROW_OBJECTS.put("iri", new RyaIRI("urn:o"));
        ROW_OBJECTS.put("string", new RyaType(XMLSchema.STRING, "hello"));
        ROW_OBJECTS.put("int", new RyaType(XMLSchema.INT, "42"));
        ROW_OBJECTS.put("langString", new RyaType(RDF.LANGSTRING, "bonjour", "fr"));
        ROW_OBJECTS.put("custom", new RyaType(CUSTOM, "v"));
    }

    private static final Map<String, String> EXPECTED_ROWS = new LinkedHashMap<>();
    static {
        EXPECTED_ROWS.put("plain/SPO/iri", "75726e3a730075726e3a700075726e3a6f0102");
        EXPECTED_ROWS.put("plain/PO/iri", "75726e3a700075726e3a6f0075726e3a730102");
        EXPECTED_ROWS.put("plain/OSP/iri", "75726e3a6f0075726e3a730075726e3a700102");
        EXPECTED_ROWS.put("plain/SPO/string", "75726e3a730075726e3a700068656c6c6f0103");
        EXPECTED_ROWS.put("plain/PO/string", "75726e3a700068656c6c6f0075726e3a730103");
        EXPECTED_ROWS.put("plain/OSP/string", "68656c6c6f0075726e3a730075726e3a700103");
        EXPECTED_ROWS.put("plain/SPO/int", "75726e3a730075726e3a70003830303030303261010d");
        EXPECTED_ROWS.put("plain/PO/int", "75726e3a700038303030303032610075726e3a73010d");
        EXPECTED_ROWS.put("plain/OSP/int", "38303030303032610075726e3a730075726e3a70010d");
        EXPECTED_ROWS.put("plain/SPO/langString", "75726e3a730075726e3a7000626f6e6a6f757240667201687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108");
        EXPECTED_ROWS.put("plain/PO/langString", "75726e3a7000626f6e6a6f75724066720075726e3a7301687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108");
        EXPECTED_ROWS.put("plain/OSP/langString", "626f6e6a6f75724066720075726e3a730075726e3a7001687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108");
        EXPECTED_ROWS.put("plain/SPO/custom", "75726e3a730075726e3a7000760175726e3a637573746f6d23747970650108");
        EXPECTED_ROWS.put("plain/PO/custom", "75726e3a7000760075726e3a730175726e3a637573746f6d23747970650108");
        EXPECTED_ROWS.put("plain/OSP/custom", "760075726e3a730075726e3a700175726e3a637573746f6d23747970650108");
        EXPECTED_ROWS.put("hashed/SPO/iri", "65346639303539333961643261613831393666343666656235356239336134360075726e3a730075726e3a700075726e3a6f0102");
        EXPECTED_ROWS.put("hashed/PO/iri", "31396531396534306661313261323830303365396338343435376235666134320075726e3a700075726e3a6f0075726e3a730102");
        EXPECTED_ROWS.put("hashed/OSP/iri", "75726e3a6f0075726e3a730075726e3a700102");
        EXPECTED_ROWS.put("hashed/SPO/string", "65346639303539333961643261613831393666343666656235356239336134360075726e3a730075726e3a700068656c6c6f0103");
        EXPECTED_ROWS.put("hashed/PO/string", "31396531396534306661313261323830303365396338343435376235666134320075726e3a700068656c6c6f0075726e3a730103");
        EXPECTED_ROWS.put("hashed/OSP/string", "68656c6c6f0075726e3a730075726e3a700103");
        EXPECTED_ROWS.put("hashed/SPO/int", "65346639303539333961643261613831393666343666656235356239336134360075726e3a730075726e3a70003830303030303261010d");
        EXPECTED_ROWS.put("hashed/PO/int", "31396531396534306661313261323830303365396338343435376235666134320075726e3a700038303030303032610075726e3a73010d");
        EXPECTED_ROWS.put("hashed/OSP/int", "38303030303032610075726e3a730075726e3a70010d");
        EXPECTED_ROWS.put("hashed/SPO/langString", "65346639303539333961643261613831393666343666656235356239336134360075726e3a730075726e3a7000626f6e6a6f757240667201687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108");
        EXPECTED_ROWS.put("hashed/PO/langString", "31396531396534306661313261323830303365396338343435376235666134320075726e3a7000626f6e6a6f75724066720075726e3a7301687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108");
        EXPECTED_ROWS.put("hashed/OSP/langString", "626f6e6a6f75724066720075726e3a730075726e3a7001687474703a2f2f7777772e77332e6f72672f313939392f30322f32322d7264662d73796e7461782d6e73236c616e67537472696e670108");
        EXPECTED_ROWS.put("hashed/SPO/custom", "65346639303539333961643261613831393666343666656235356239336134360075726e3a730075726e3a7000760175726e3a637573746f6d23747970650108");
        EXPECTED_ROWS.put("hashed/PO/custom", "31396531396534306661313261323830303365396338343435376235666134320075726e3a7000760075726e3a730175726e3a637573746f6d23747970650108");
        EXPECTED_ROWS.put("hashed/OSP/custom", "760075726e3a730075726e3a700175726e3a637573746f6d23747970650108");
    }

    /** The rows of {@code <urn:s> <urn:p> object} in each table, in the plain and the hashed layout. */
    static Stream<Arguments> rows() {
        final List<Arguments> cases = new ArrayList<>();
        final Map<String, TripleRowResolver> layouts = new LinkedHashMap<>();
        layouts.put("plain", new WholeRowTripleResolver());
        layouts.put("hashed", new WholeRowHashedTripleResolver());
        layouts.forEach((layout, resolver) -> ROW_OBJECTS.forEach((kind, object) -> {
            for (final TABLE_LAYOUT table : TABLE_LAYOUT.values()) {
                cases.add(Arguments.of(layout + "/" + table + "/" + kind, resolver, table, object));
            }
        }));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    public void rowBytesAreUnchanged(final String name, final TripleRowResolver resolver, final TABLE_LAYOUT table,
            final RyaType object) throws Exception {
        final RyaStatement statement = new RyaStatement(new RyaIRI("urn:s"), new RyaIRI("urn:p"), object);
        assertEquals(EXPECTED_ROWS.get(name), HEX.formatHex(resolver.serialize(statement).get(table).getRow()), name);
    }
}
