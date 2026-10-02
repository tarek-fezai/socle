# SPDX-License-Identifier: AGPL-3.0-or-later
# syntax=docker/dockerfile:1

# eclipse-temurin:21-jdk-jammy (build — Maven + JDK)
FROM eclipse-temurin@sha256:6adefddd4a20bceef702cedb0b03952fcd7691f9c7ccffe27014992abc0b46bd AS build

WORKDIR /build

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package \
    && cp target/socle-backend-*.jar /build/app.jar

# eclipse-temurin:21.0.8_9-jdk-alpine (jlink → Temurin 21 JRE, musl)
FROM eclipse-temurin@sha256:89517925fa675c6c4b770bee7c44d38a7763212741b0d6fca5a5103caab21a97 AS jre

RUN jlink \
    --add-modules ALL-MODULE-PATH \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /jre

# alpine:3.20.3 (runtime OS) + Temurin 21 JRE (jlink, non-root UID 10001)
FROM alpine@sha256:1e42bbe2508154c9126d48c2b8a75420c3544343bf86fd041fb7527e017a4b4a AS runtime

RUN apk add --no-cache ca-certificates \
    && adduser -D -u 10001 -H -s /sbin/nologin appuser \
    && mkdir -p /app /data/git-content /openfga-config /tmp \
    && chown -R 10001:10001 /app /data/git-content /openfga-config /tmp

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre /jre /opt/java
COPY --from=build --chown=10001:10001 /build/app.jar /app/app.jar

WORKDIR /app
USER 10001:10001

ENV SOCLE_REJECT_WEAK_SECRETS=true
ENV OPENFGA_AUTO_INIT=true
ENV OPENFGA_STATE_DIR=/openfga-config
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
