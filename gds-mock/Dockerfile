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
    && cp gds-mock/target/gds-mock-*.jar /workspace/app.jar

# --- Runtime: JRE only, non-root -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app

EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# busybox wget ships with the Alpine base image
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
