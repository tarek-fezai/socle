#!/usr/bin/env bash
# Appends OPENFGA_STORE_ID / OPENFGA_MODEL_ID from openfga-init volume into .env.ci
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/deploy/compose"
ENV_FILE="${COMPOSE_DIR}/.env.ci"
PROJECT="${COMPOSE_PROJECT_NAME:-socle-production}"
VOL="${PROJECT}_openfga-config"

STORE_ENV="$(docker run --rm -v "${VOL}:/cfg" alpine cat /cfg/store.env 2>/dev/null || true)"
if [[ -z "$STORE_ENV" ]]; then
  echo "No store.env on volume ${VOL} yet" >&2
  exit 1
fi

grep -v '^OPENFGA_STORE_ID=' "$ENV_FILE" | grep -v '^OPENFGA_MODEL_ID=' > "${ENV_FILE}.tmp" || true
cat "${ENV_FILE}.tmp" > "$ENV_FILE"
echo "$STORE_ENV" >> "$ENV_FILE"
rm -f "${ENV_FILE}.tmp"
echo "Merged OpenFGA ids into ${ENV_FILE}"

cd "$COMPOSE_DIR"
docker compose -f docker-compose.yml -f ../../e2e/docker-compose.e2e.yml \
  --env-file .env.ci --profile demo-idp up -d --force-recreate backend
