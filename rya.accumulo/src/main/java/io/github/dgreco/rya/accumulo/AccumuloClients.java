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
package io.github.dgreco.rya.accumulo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;

/**
 * Builds Accumulo clients from an {@code accumulo-client.properties} file. The file need not hold
 * the password: with password authentication and no {@code auth.token}, the password given here
 * (for example from an environment variable) is used.
 */
public final class AccumuloClients {
    static final String AUTH_TYPE = "auth.type";
    static final String AUTH_TOKEN = "auth.token";

    private AccumuloClients() {
    }

    /**
     * Reads the file as Accumulo does (ISO-8859-1, non-Latin-1 characters as backslash-u escapes).
     *
     * @param password used when the file has password authentication but no {@code auth.token};
     *        may be null
     * @throws IllegalArgumentException if the file can't be read, or no password is available
     */
    public static Properties clientProperties(final Path file, final String password) {
        final Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (final IOException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getMessage(), e);
        }
        final String type = props.getProperty(AUTH_TYPE, "password").trim();
        final String token = props.getProperty(AUTH_TOKEN);
        if (type.equals("password") && (token == null || token.isEmpty())) {
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException(file + " has no auth.token: add the password to the file, "
                        + "or pass it in the RYA_ACCUMULO_PASSWORD environment variable");
            }
            props.setProperty(AUTH_TYPE, "password");
            props.setProperty(AUTH_TOKEN, password);
        }
        return props;
    }

    /** A client for the file's instance and credentials; see {@link #clientProperties(Path, String)}. */
    public static AccumuloClient fromFile(final Path file, final String password) {
        return Accumulo.newClient().from(clientProperties(file, password)).build();
    }
}
