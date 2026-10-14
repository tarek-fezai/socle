#!/usr/bin/env bash
# Appends OPENFGA_STORE_ID / OPENFGA_MODEL_ID from openfga-init volume into .env.ci
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/deploy/compose"
ENV_FILE="${COMPOSE_DIR}/.env.ci"
if [[ "${COMPOSE_PROJECT_NAME:-}" == "socle-production" ]]; then
  echo "e2e: refus — COMPOSE_PROJECT_NAME=socle-production. Ne jamais lancer l'e2e sur une instance d'exploitation." >&2
  exit 1
fi
PROJECT="socle-e2e"
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

bash "${ROOT}/e2e/scripts/compose-e2e.sh" up -d --force-recreate backend
