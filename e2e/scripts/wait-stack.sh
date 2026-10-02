#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/deploy/compose"
ENV_FILE="${1:-.env.ci}"

KEYCLOAK_PORT="${KEYCLOAK_HTTP_PORT:-8081}"
if [[ -f "${COMPOSE_DIR}/${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  val="$(grep -E '^KEYCLOAK_HTTP_PORT=' "${COMPOSE_DIR}/${ENV_FILE}" | tail -1 | cut -d= -f2- || true)"
  if [[ -n "${val}" ]]; then
    KEYCLOAK_PORT="${val}"
  fi
fi

BASE_URL="${BASE_URL:-http://127.0.0.1}"
KEYCLOAK_BASE="${KEYCLOAK_BASE_URL:-http://127.0.0.1:${KEYCLOAK_PORT}}"
ISSUER="${KEYCLOAK_BASE}/realms/socle"
DISCOVERY="${ISSUER}/.well-known/openid-configuration"

cd "$COMPOSE_DIR"

compose_ps() {
  docker compose -f docker-compose.yml -f ../../e2e/docker-compose.e2e.yml \
    --env-file "$ENV_FILE" --profile demo-idp ps >&2 || true
}

echo "Waiting for Keycloak OIDC (issuer ${ISSUER})..."
for i in $(seq 1 60); do
  body="$(curl -sf "$DISCOVERY" 2>/dev/null || true)"
  if [[ -n "$body" ]] && echo "$body" | grep -q "\"issuer\":\"${ISSUER}\""; then
    echo "Keycloak issuer reachable."
    break
  fi
  if [[ "$i" -eq 60 ]]; then
    echo "Keycloak OIDC not ready (expected issuer ${ISSUER})" >&2
    compose_ps
    exit 1
  fi
  sleep 5
done

echo "Waiting for Caddy edge (${BASE_URL})..."
for i in $(seq 1 30); do
  if curl -sf -o /dev/null "$BASE_URL/" 2>/dev/null; then
    echo "Caddy / frontend reachable."
    break
  fi
  if [[ "$i" -eq 30 ]]; then
    echo "Caddy not reachable at ${BASE_URL}" >&2
    compose_ps
    exit 1
  fi
  sleep 3
done

echo "Waiting for backend readiness via Caddy..."
for i in $(seq 1 90); do
  if curl -sf "${BASE_URL}/actuator/health/readiness" >/dev/null 2>&1; then
    echo "Backend ready."
    exit 0
  fi
  sleep 5
done

echo "Backend not healthy in time" >&2
compose_ps
exit 1
