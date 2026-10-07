# SPDX-License-Identifier: LicenseRef-Socle-Proprietary
# syntax=docker/dockerfile:1

# golang:1.25-bookworm (build) — go.mod requires go >= 1.25.0
FROM golang@sha256:3b4a11519ad929d1e1d261a12cff056f0c85b735253d7d861346b9c6f8b36437 AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/worker ./cmd/worker

# gcr.io/distroless/static-debian12:nonroot — runtime UID/GID 65532
FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab AS runtime

LABEL org.opencontainers.image.licenses="LicenseRef-Socle-Proprietary"

COPY --from=build /out/worker /worker

EXPOSE 8090

# Distroless: no shell or HTTP client; use orchestrator HTTP probes against GET /healthz
# (see webhook-worker/internal/server/server.go). Example Compose:
#   healthcheck:
#     test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8090/healthz"]
#     (requires a sidecar or distroless variant with a probe helper)
HEALTHCHECK NONE

ENTRYPOINT ["/worker"]
