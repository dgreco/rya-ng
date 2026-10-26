package io.github.dgreco.rya.api.resolver.impl;

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

import io.github.dgreco.rya.api.resolver.RyaTypeResolverException;
import org.calrissian.mango.types.LexiTypeEncoders;
import org.calrissian.mango.types.TypeEncoder;
import org.calrissian.mango.types.exception.TypeDecodingException;
import org.calrissian.mango.types.exception.TypeEncodingException;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;

/**
 * Stores {@code xsd:int} (32-bit) values. Kept separate from {@code xsd:integer}
 * so the datatype survives a round trip.
 */
public class IntRyaTypeResolver extends RyaTypeResolverImpl {
    public static final int INT_LITERAL_MARKER = 13;
    public static final TypeEncoder<Integer, String> INT_STRING_TYPE_ENCODER = LexiTypeEncoders.integerEncoder();

    public IntRyaTypeResolver() {
        super((byte) INT_LITERAL_MARKER, XMLSchema.INT);
    }

    @Override
    protected String serializeData(final String data) throws RyaTypeResolverException {
        try {
            return INT_STRING_TYPE_ENCODER.encode(Integer.parseInt(data.trim()));
        } catch (final NumberFormatException | TypeEncodingException e) {
            throw new RyaTypeResolverException("Exception occurred serializing data[" + data + "]", e);
        }
    }

    @Override
    protected String deserializeData(final String value) throws RyaTypeResolverException {
        try {
            return INT_STRING_TYPE_ENCODER.decode(value).toString();
        } catch (final TypeDecodingException e) {
            throw new RyaTypeResolverException("Exception occurred deserializing data[" + value + "]", e);
        }
    }
}
