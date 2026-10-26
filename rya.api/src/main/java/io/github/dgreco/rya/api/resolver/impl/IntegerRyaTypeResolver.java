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

import java.math.BigInteger;

import io.github.dgreco.rya.api.resolver.RyaTypeResolverException;
import org.calrissian.mango.types.LexiTypeEncoders;
import org.calrissian.mango.types.TypeEncoder;
import org.calrissian.mango.types.exception.TypeDecodingException;
import org.calrissian.mango.types.exception.TypeEncodingException;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;

/**
 * Stores {@code xsd:integer} values with arbitrary precision.
 */
public class IntegerRyaTypeResolver extends RyaTypeResolverImpl {
    public static final int INTEGER_LITERAL_MARKER = 5;
    public static final TypeEncoder<BigInteger, String> INTEGER_STRING_TYPE_ENCODER = LexiTypeEncoders
            .bigIntegerEncoder();

    public IntegerRyaTypeResolver() {
        super((byte) INTEGER_LITERAL_MARKER, XMLSchema.INTEGER);
    }

    @Override
    protected String serializeData(String data) throws
                                                RyaTypeResolverException {
        try {
            return INTEGER_STRING_TYPE_ENCODER.encode(new BigInteger(data.trim()));
        } catch (NumberFormatException e) {
            throw new RyaTypeResolverException(
                    "Exception occurred serializing data[" + data + "]", e);
        } catch (TypeEncodingException e) {
            throw new RyaTypeResolverException(
                    "Exception occurred serializing data[" + data + "]", e);
        }
    }

    @Override
    protected String deserializeData(String value) throws RyaTypeResolverException {
        try {
            return INTEGER_STRING_TYPE_ENCODER.decode(value).toString();
        } catch (TypeDecodingException e) {
            throw new RyaTypeResolverException(
                    "Exception occurred deserializing data[" + value + "]", e);
        }
    }
}
