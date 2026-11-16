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

# A try-out image: the SPARQL endpoint and its query page on a throwaway in-process cluster
# (DevServer). Not for production: the data is lost when the container stops.
FROM maven:3.9-eclipse-temurin-25

WORKDIR /rya-ng
COPY . .

# Builds every module and its test classes (DevServer and the in-process clusters are test code)
# without running the tests, then resolves what the run below needs so it can start offline.
RUN mvn -B -q install -DskipTests -DskipITs \
 && mvn -B -q -pl rya.sparql dependency:go-offline spring-boot:help

ENV RYA_BACKEND=accumulo
EXPOSE 8080

CMD ["sh", "-c", "exec mvn -B -o -q -pl rya.sparql spring-boot:test-run -Dspring-boot.run.main-class=io.github.dgreco.rya.sparql.DevServer -Dspring-boot.run.jvmArguments=-Drya.test.backend=${RYA_BACKEND}"]
