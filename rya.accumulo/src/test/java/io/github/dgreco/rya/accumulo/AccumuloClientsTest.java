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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccumuloClientsTest {
    @TempDir
    Path dir;

    private Path file(final String content) throws Exception {
        final Path f = dir.resolve("accumulo-client.properties");
        Files.writeString(f, content, StandardCharsets.ISO_8859_1);
        return f;
    }

    @Test
    void keepsThePasswordOfTheFile() throws Exception {
        final Properties p = AccumuloClients.clientProperties(file("""
                instance.name=acc
                instance.zookeepers=zk:2181
                auth.type=password
                auth.principal=rya
                auth.token=p\\\\a\\u00e9ss
                """), "from-env");
        assertEquals("p\\aéss", p.getProperty("auth.token"), "escapes are read as Accumulo reads them");
        assertEquals("rya", p.getProperty("auth.principal"));
    }

    @Test
    void usesTheGivenPasswordWhenTheFileHasNone() throws Exception {
        final Properties p = AccumuloClients.clientProperties(file("""
                instance.name=acc
                instance.zookeepers=zk:2181
                auth.principal=rya
                """), "from-env");
        assertEquals("from-env", p.getProperty("auth.token"));
        assertEquals("password", p.getProperty("auth.type"));
    }

    @Test
    void explainsAMissingPassword() throws Exception {
        final Path f = file("auth.type=password\nauth.principal=rya\nauth.token=\n");
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AccumuloClients.clientProperties(f, null));
        assertTrue(e.getMessage().contains("RYA_ACCUMULO_PASSWORD"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AccumuloClients.clientProperties(f, ""));
    }

    @Test
    void leavesOtherAuthenticationAlone() throws Exception {
        final Properties p = AccumuloClients.clientProperties(file("auth.type=kerberos\nauth.principal=rya@EXAMPLE\n"), "x");
        assertNull(p.getProperty("auth.token"));
    }

    @Test
    void explainsAMissingFile() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AccumuloClients.clientProperties(dir.resolve("nope.properties"), "x"));
        assertTrue(e.getMessage().contains("nope.properties"), e.getMessage());
    }
}
