# syntax=docker/dockerfile:1
# gds-mock image. Build context = repository root (multi-module Maven build):
#   docker build -f gds-mock/Dockerfile -t gds-mock .
# Both base images are multi-arch (amd64, arm64).

# --- Build: compile and package gds-mock with the Maven wrapper -------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Poms first, so dependencies are cached until a pom changes
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY flight-aggregator/pom.xml flight-aggregator/
COPY gds-mock/pom.xml gds-mock/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl gds-mock -am dependency:go-offline

COPY gds-mock/src gds-mock/src
# Tests, coverage and formatting run in CI and in `./mvnw verify`, not in the image build
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl gds-mock -am package \
        -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true \
    && java -Djarmode=tools -jar gds-mock/target/gds-mock-*.jar extract --layers --launcher --destination /workspace/extracted

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

EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# The healthcheck lives in docker-compose.yml (busybox wget ships with the Alpine base image)
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
