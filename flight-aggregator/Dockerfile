# syntax=docker/dockerfile:1
# flight-aggregator image. Build context = repository root (multi-module Maven build):
#   docker build -f flight-aggregator/Dockerfile -t flight-aggregator .
# Both base images are multi-arch (amd64, arm64). Run with PostgreSQL and gds-mock by docker-compose.yml.

# --- Build: compile and package flight-aggregator with the Maven wrapper ----------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Poms first, so dependencies are cached until a pom changes
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY flight-aggregator/pom.xml flight-aggregator/
COPY gds-mock/pom.xml gds-mock/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl flight-aggregator -am dependency:go-offline

COPY flight-aggregator/src flight-aggregator/src
# Tests, coverage and formatting run in CI and in `./mvnw verify`, not in the image build
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl flight-aggregator -am package \
        -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true \
    && java -Djarmode=tools -jar flight-aggregator/target/flight-aggregator-*.jar extract --layers --launcher \
        --destination /workspace/extracted

# --- Runtime: JRE only, numeric non-root user, one layer per Spring Boot jar layer -----------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
# Least to most frequently changing, so a code change only rebuilds the last (small) layer
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001

EXPOSE 8080
# Heap measured at 1,000,000 itineraries (SOLUTION.md, spec 004): results now go to PostgreSQL day by day, so the heap
# only holds the ingestion working set (2 days in flight). A 1M run completes with 512 MB and runs out of memory with
# 384 MB; 768 MB leaves 1.5x headroom (container peak ~1 GB). On OutOfMemoryError the JVM exits so the container is
# restarted (docker-compose restart policy) instead of running in an unknown state.
ENV JAVA_TOOL_OPTIONS="-Xmx768m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
