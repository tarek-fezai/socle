#!/usr/bin/env sh
# Wait for OpenFGA HTTP readiness. Store/model bootstrap is done only by the backend
# (OpenFgaBootstrap) — this script never creates a store.
set -eu

OPENFGA_API_URL="${OPENFGA_API_URL:-http://openfga:8080}"

echo "openfga-init: waiting for OpenFGA at ${OPENFGA_API_URL}/healthz ..."
command -v wget >/dev/null 2>&1 || apk add --no-cache wget >/dev/null
i=0
while ! wget -q -O /dev/null "${OPENFGA_API_URL}/healthz" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "openfga-init: OpenFGA not healthy" >&2
    exit 1
  fi
  sleep 2
done

echo "openfga-init: OpenFGA ready (store/model handled by backend OpenFgaBootstrap)"
