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
package io.github.dgreco.rya.api.resolver.impl;

import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.DELIM_BYTE;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TYPE_DELIM_BYTE;
import static io.github.dgreco.rya.api.RdfCloudTripleStoreConstants.TYPE_DELIM_BYTES;

import java.nio.charset.StandardCharsets;

import io.github.dgreco.rya.api.domain.RyaRange;
import io.github.dgreco.rya.api.domain.RyaType;
import io.github.dgreco.rya.api.resolver.RyaTypeResolver;
import io.github.dgreco.rya.api.resolver.RyaTypeResolverException;
import io.github.dgreco.rya.api.utils.LiteralLanguageUtils;
import org.calrissian.mango.types.LexiTypeEncoders;
import org.calrissian.mango.types.TypeEncoder;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.util.Literals;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;

import com.google.common.primitives.Bytes;

/**
 * Date: 7/16/12
 * Time: 12:42 PM
 */
public class RyaTypeResolverImpl implements RyaTypeResolver {
    public static final int PLAIN_LITERAL_MARKER = 3;
    public static final TypeEncoder<String, String> STRING_TYPE_ENCODER = LexiTypeEncoders
            .stringEncoder();

    protected byte markerByte;
    protected IRI dataType;
    protected byte[] markerBytes;

    public RyaTypeResolverImpl() {
        this((byte) PLAIN_LITERAL_MARKER, XMLSchema.STRING);
    }

    public RyaTypeResolverImpl(final byte markerByte, final IRI dataType) {
        setMarkerByte(markerByte);
        setRyaDataType(dataType);
    }

    public void setMarkerByte(final byte markerByte) {
        this.markerByte = markerByte;
        this.markerBytes = new byte[]{markerByte};
    }

    @Override
    public byte getMarkerByte() {
        return markerByte;
    }

    @Override
    public RyaRange transformRange(final RyaRange ryaRange) throws RyaTypeResolverException {
        return ryaRange;
    }

    @Override
    public byte[] serialize(final RyaType ryaType) throws RyaTypeResolverException {
        final byte[][] bytes = serializeType(ryaType);
        return Bytes.concat(bytes[0], bytes[1]);
    }

    @Override
    public byte[][] serializeType(final RyaType ryaType) throws RyaTypeResolverException {
        final StringBuilder dataBuilder = new StringBuilder();
        dataBuilder.append(ryaType.getData());
        final String validatedLanguage = LiteralLanguageUtils.validateLanguage(ryaType.getLanguage(), dataType);
        if (validatedLanguage != null) {
            dataBuilder.append(LiteralLanguageUtils.LANGUAGE_DELIMITER);
            dataBuilder.append(validatedLanguage);
        }
        final byte[] bytes = checkNoReservedBytes(serializeData(dataBuilder.toString()).getBytes(StandardCharsets.UTF_8));
        return new byte[][]{bytes, Bytes.concat(TYPE_DELIM_BYTES, markerBytes)};
    }

    @Override
    public IRI getRyaDataType() {
        return dataType;
    }

    public void setRyaDataType(final IRI dataType) {
        this.dataType = dataType;
    }

    @Override
    public RyaType newInstance() {
        return new RyaType();
    }

    @Override
    public boolean deserializable(final byte[] bytes) {
        return bytes != null && bytes.length >= 2 && bytes[bytes.length - 1] == getMarkerByte() && bytes[bytes.length - 2] == TYPE_DELIM_BYTE;
    }

    /**
     * Row keys use 0x00 to separate subject, predicate and object, and 0x01 to mark
     * the type. A value containing either byte would make the row unparseable, so it
     * is rejected instead of being stored corrupted.
     *
     * @param bytes the serialized value.
     * @return the same bytes.
     * @throws RyaTypeResolverException if the value contains U+0000 or U+0001.
     */
    public static byte[] checkNoReservedBytes(final byte[] bytes) throws RyaTypeResolverException {
        for (final byte b : bytes) {
            if (b == DELIM_BYTE || b == TYPE_DELIM_BYTE) {
                throw new RyaTypeResolverException(
                        "Values containing the control characters U+0000 or U+0001 cannot be stored");
            }
        }
        return bytes;
    }

    protected String serializeData(final String data) throws RyaTypeResolverException {
        return STRING_TYPE_ENCODER.encode(data);
    }

    @Override
    public RyaType deserialize(final byte[] bytes) throws RyaTypeResolverException {
        if (!deserializable(bytes)) {
            throw new RyaTypeResolverException("Bytes not deserializable");
        }
        final RyaType rt = newInstance();
        rt.setDataType(getRyaDataType());
        String data = new String(bytes, 0, bytes.length - 2, StandardCharsets.UTF_8);
        if (RDF.LANGSTRING.equals(rt.getDataType())) {
            final int langDelimiterPos = data.lastIndexOf(LiteralLanguageUtils.LANGUAGE_DELIMITER);
            final String parsedData = data.substring(0, langDelimiterPos);
            final String language = data.substring(langDelimiterPos + 1, data.length());
            if (language != null && Literals.isValidLanguageTag(language)) {
                rt.setLanguage(language);
            } else {
                rt.setLanguage(LiteralLanguageUtils.UNDETERMINED_LANGUAGE);
            }
            data = parsedData;
        }
        rt.setData(deserializeData(data));
        return rt;
    }

    protected String deserializeData(final String data) throws RyaTypeResolverException {
        return STRING_TYPE_ENCODER.decode(data);
    }
}
