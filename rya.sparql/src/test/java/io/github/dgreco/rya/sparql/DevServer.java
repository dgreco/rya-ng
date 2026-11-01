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

import org.springframework.boot.SpringApplication;

import io.github.dgreco.rya.test.TestBackends;

/**
 * Runs the endpoint on a throwaway in-process cluster, for trying it out locally:
 * <pre>
 * mvn -pl rya.sparql -am install -DskipTests
 * mvn -pl rya.sparql spring-boot:test-run -Dspring-boot.run.main-class=io.github.dgreco.rya.sparql.DevServer
 * </pre>
 * The cluster is a MiniAccumuloCluster, or an HBase mini cluster with
 * {@code -Dspring-boot.run.jvmArguments=-Drya.test.backend=hbase}. Data is lost when the process exits.
 */
public final class DevServer {
    private DevServer() {
    }

    public static void main(final String[] args) {
        // Starts the backend's in-process cluster and points the endpoint at it, as the test account.
        TestBackends.current().endpointProperties().forEach(System::setProperty);
        System.setProperty("rya.inference", "true");
        SpringApplication.run(RyaSparqlApplication.class, args);
    }
}
