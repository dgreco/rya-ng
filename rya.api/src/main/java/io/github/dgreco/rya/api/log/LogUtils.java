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
package io.github.dgreco.rya.api.log;

/**
 * Utility methods for logging.
 */
public final class LogUtils {
    /**
     * Private constructor to prevent instantiation.
     */
    private LogUtils() {
    }

    /**
     * Cleans the log message to prevent log forging. This will escape certain
     * characters such as carriage returns and line feeds and will replace
     * non-printable and other certain characters with their unicode value.<p>
     * This will turn the following string (which contains a CRLF):<pre>
     * ¿Hello
     * World?!
     * </pre>
     * into the escaped message:<pre>
     * \u00BFHello\r\nWorld?!
     * </pre>
     * @param message the message to clean.
     * @return the cleansed message.
     */
    public static String clean(final String message) {
        if (message == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            final char c = message.charAt(i);
            switch (c) {
                case '\r': sb.append("\\r"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\'': sb.append("\\'"); break;
                case '/': sb.append("\\/"); break;
                default:
                    if (c < 0x20 || c >= 0x7F) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}