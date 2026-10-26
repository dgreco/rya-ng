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
package io.github.dgreco.rya.loader;

import java.util.Locale;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connects to Accumulo from the options; the password only ever comes from the environment. */
public final class ClientFactory {
    private static final Logger log = LoggerFactory.getLogger(ClientFactory.class);

    private ClientFactory() {
    }

    public static AccumuloClient create(final LoaderOptions o) {
        if (o.clientProperties() != null) {
            return Accumulo.newClient().from(o.clientProperties()).build();
        }
        if (o.zookeepers() == null || o.instance() == null) {
            throw new IllegalArgumentException("give --client-properties, or --zookeepers and --instance "
                    + "(or RYA_ACCUMULO_ZOOKEEPERS and RYA_ACCUMULO_INSTANCE)");
        }
        if (o.password() == null || o.password().isEmpty()) {
            throw new IllegalArgumentException("set the RYA_ACCUMULO_PASSWORD environment variable");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") && o.zookeepers().contains(".local")
                && !Boolean.getBoolean("java.net.preferIPv4Stack")) {
            log.warn("On macOS, *.local names can take 5 s each to resolve over IPv6: run java with "
                    + "-Djava.net.preferIPv4Stack=true (run.sh does)");
        }
        return Accumulo.newClient().to(o.instance(), o.zookeepers()).as(o.user(), o.password()).build();
    }
}
