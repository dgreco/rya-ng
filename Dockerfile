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

# A try-out image: the SPARQL endpoint and its query page (DevServer) on a throwaway in-process
# cluster, or on a RocksDB store in a volume. Not for production.
FROM maven:3.9-eclipse-temurin-25

WORKDIR /rya-ng
COPY . .

# Builds every module and its test classes (DevServer and the in-process clusters are test code)
# without running the tests, then resolves what the run below needs so it can start offline.
RUN mvn -B -q install -DskipTests -DskipITs \
 && mvn -B -q -pl rya.sparql dependency:go-offline spring-boot:help

# accumulo, hbase (both in-process clusters, emptied on every start) or rocksdb, whose data is
# kept in RYA_DATA: mount a volume there to keep it across containers.
ENV RYA_BACKEND=accumulo
ENV RYA_DATA=/var/lib/rya-ng
VOLUME /var/lib/rya-ng
EXPOSE 8080

CMD ["sh", "-c", "exec mvn -B -o -q -pl rya.sparql spring-boot:test-run -Dspring-boot.run.main-class=io.github.dgreco.rya.sparql.DevServer \"-Dspring-boot.run.jvmArguments=-Drya.test.backend=${RYA_BACKEND} -Drya.test.rocksdbPath=${RYA_DATA}\""]
