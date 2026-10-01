# SPDX-License-Identifier: AGPL-3.0-or-later
# syntax=docker/dockerfile:1

FROM eclipse-temurin@sha256:6adefddd4a20bceef702cedb0b03952fcd7691f9c7ccffe27014992abc0b46bd AS build

WORKDIR /build

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813504bf4129be2d09d7 AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --shell /usr/sbin/nologin appuser

WORKDIR /app

COPY --from=build /build/target/socle-backend-*.jar /app/app.jar

# Entrypoint written in-image (avoids Windows CRLF → exec format error).
RUN printf '%s\n' \
  '#!/bin/bash' \
  'set -euo pipefail' \
  'mkdir -p /data/git-content /openfga-config' \
  'chown -R appuser:appuser /data/git-content /openfga-config 2>/dev/null || true' \
  'exec runuser -u appuser -- java -jar /app/app.jar "$@"' \
  > /app/docker-entrypoint.sh \
  && chmod 755 /app/docker-entrypoint.sh \
  && chown -R appuser:appuser /app

USER root

ENV SOCLE_REJECT_WEAK_SECRETS=true
ENV OPENFGA_AUTO_INIT=true
ENV OPENFGA_STATE_DIR=/openfga-config

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
