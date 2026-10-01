# SPDX-License-Identifier: AGPL-3.0-or-later
# syntax=docker/dockerfile:1

FROM golang@sha256:3d699e4d15d0f8f13c9195c0632a16702b8cbdece2955af1c23b37ae5d55a253 AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/worker ./cmd/worker

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab AS runtime

COPY --from=build /out/worker /worker

EXPOSE 8090

# Distroless: no shell or HTTP client; use orchestrator HTTP probes against GET /healthz
# (see webhook-worker/internal/server/server.go). Example Compose:
#   healthcheck:
#     test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8090/healthz"]
#     (requires a sidecar or distroless variant with a probe helper)
HEALTHCHECK NONE

ENTRYPOINT ["/worker"]
