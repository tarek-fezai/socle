#!/usr/bin/env bash
# Importe la licence de TEST signée par generate-ci-env.sh (bootstrap admin).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/deploy/compose"
ENV_FILE="${1:-.env.ci}"
LIC_OUT="${ROOT}/e2e/.ci-licence-keys/licence.signed.json"
BASE_URL="${BASE_URL:-http://127.0.0.1}"
KEYCLOAK_PORT="${KEYCLOAK_HTTP_PORT:-8081}"

if [[ -f "${COMPOSE_DIR}/${ENV_FILE}" ]]; then
  val="$(grep -E '^KEYCLOAK_HTTP_PORT=' "${COMPOSE_DIR}/${ENV_FILE}" | tail -1 | cut -d= -f2- || true)"
  if [[ -n "${val}" ]]; then
    KEYCLOAK_PORT="${val}"
  fi
fi

KEYCLOAK_BASE="${KEYCLOAK_BASE_URL:-http://127.0.0.1:${KEYCLOAK_PORT}}"
USER_A="${E2E_USER_A:-contributeur}"
PASS_A="${E2E_PASS_A:-contributeur}"
CLIENT_ID="${OIDC_FRONTEND_CLIENT_ID:-socle-frontend}"

if [[ ! -f "${LIC_OUT}" ]]; then
  echo "Licence CI absente : ${LIC_OUT} (lancer generate-ci-env.sh d'abord)" >&2
  exit 1
fi

echo "Fetching bootstrap token (${USER_A})..."
TOKEN="$(curl -sf -X POST \
  "${KEYCLOAK_BASE}/realms/socle/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=password&client_id=${CLIENT_ID}&username=${USER_A}&password=${PASS_A}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')"

# Premier sync : provisionne le bootstrap admin (exempt de la limite sièges).
curl -sf -o /dev/null -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/me"

PAYLOAD="$(python3 -c 'import json,sys; print(json.dumps({"licenceJson": sys.stdin.read()}))' < "${LIC_OUT}")"

echo "Importing CI test licence..."
HTTP="$(curl -s -o /tmp/socle-licence-import.json -w '%{http_code}' \
  -X POST "${BASE_URL}/api/v1/admin/licence/import" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "${PAYLOAD}")"

if [[ "${HTTP}" != "200" ]]; then
  echo "Import licence failed HTTP ${HTTP}" >&2
  cat /tmp/socle-licence-import.json >&2 || true
  exit 1
fi

echo "Licence CI importée (maxUsers=100)."
