# Copyright 2026 David Greco
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# A try-out image of the SPARQL endpoint and its query page. Not for production.
#
#   TRIPATHA_BACKEND=accumulo or hbase   the endpoint on a throwaway in-process cluster, on a JVM
#                                        (DevServer): the data is lost when the container stops.
#   TRIPATHA_BACKEND=rocksdb             the native executable of tripatha.standalone, with its
#                                        store in /var/lib/tripatha: mount a volume there.
#
# The build argument picks the last stage; BuildKit builds only the stages that one needs.
ARG TRIPATHA_BACKEND=accumulo
# The GraalVM that compiles the native executable.
ARG GRAALVM_VERSION=25.0.2

# ---- accumulo and hbase: the JVM, Maven and the test clusters -------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS jvm

WORKDIR /tripatha
COPY . .

# Builds every module and its test classes (DevServer and the in-process clusters are test code)
# without running the tests, then resolves what the run below needs so it can start offline.
RUN mvn -B -q install -DskipTests -DskipITs \
 && mvn -B -q -pl tripatha.sparql dependency:go-offline spring-boot:help

ENV TRIPATHA_BACKEND=accumulo
EXPOSE 8080

CMD ["sh", "-c", "exec mvn -B -o -q -pl tripatha.sparql spring-boot:test-run -Dspring-boot.run.main-class=org.tripatha.sparql.DevServer -Dspring-boot.run.jvmArguments=-Dtripatha.test.backend=${TRIPATHA_BACKEND}"]

# ---- rocksdb: compile the native executable... ---------------------------------------------------
FROM ghcr.io/graalvm/native-image-community:${GRAALVM_VERSION} AS native-build

ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
RUN curl -fsSL -o /tmp/maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
 && echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - \
 && mkdir /opt/maven && tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 && rm /tmp/maven.tar.gz

WORKDIR /tripatha
COPY . .

# The standalone module (the endpoint with the RocksDB backend only) and what it depends on.
RUN /opt/maven/bin/mvn -B -q -Pnative -pl tripatha.standalone -am package -DskipTests -DskipITs

# ---- ...and run it on a small base image ---------------------------------------------------------
# glibc at least as new as the build image's; curl is for the health check.
FROM debian:trixie-slim AS native

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --home-dir /var/lib/tripatha tripatha \
 && mkdir -p /var/lib/tripatha && chown tripatha:tripatha /var/lib/tripatha
COPY --from=native-build /tripatha/tripatha.standalone/target/tripatha /usr/local/bin/tripatha

USER tripatha
ENV TRIPATHA_BACKEND=rocksdb
ENV TRIPATHA_ROCKSDB_PATH=/var/lib/tripatha
VOLUME /var/lib/tripatha
EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/tripatha"]

# ---- the stage of the chosen backend ---------------------------------------------------------------
FROM jvm AS backend-accumulo
FROM jvm AS backend-hbase
FROM native AS backend-rocksdb
FROM backend-${TRIPATHA_BACKEND}
