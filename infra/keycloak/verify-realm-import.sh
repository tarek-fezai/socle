#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Vérifie que chaque fichier realm du dépôt s'importe avec la même image Keycloak
# et la même commande que deploy/compose (start --import-realm + Postgres).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Docker Desktop (Windows/macOS via Git Bash) attend des chemins host natifs.
docker_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "${p}"
  else
    printf '%s' "${p}"
  fi
}

COMPOSE_FILE="$(docker_path "${ROOT}/infra/keycloak/docker-compose.verify.yml")"
IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.0}"
HOST_PORT="${KEYCLOAK_VERIFY_PORT:-18081}"
TIMEOUT_SEC="${KEYCLOAK_VERIFY_TIMEOUT_SEC:-180}"
LOG_DIR="${KEYCLOAK_VERIFY_LOG_DIR:-${ROOT}/.tmp-keycloak-realm-logs}"
PROJECT="kc-realm-verify-$$"

REALMS=(
  "${ROOT}/infra/keycloak/realm-socle.dev.json"
  "${ROOT}/e2e/realm-socle.ci.json"
)

mkdir -p "${LOG_DIR}"
rm -f "${LOG_DIR}"/*.log

cleanup() {
  docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail=0
for realm in "${REALMS[@]}"; do
  base="$(basename "${realm}")"
  name="${base%.json}"
  log="${LOG_DIR}/${name}.log"

  echo "==> Import check: ${base} (image ${IMAGE})"
  if [[ ! -f "${realm}" ]]; then
    echo "ERROR: missing realm file ${realm}" >&2
    fail=1
    continue
  fi

  cleanup
  realm_host="$(docker_path "${realm}")"
  # Fichier env dans le dépôt (chemin Windows réel) — mktemp MSYS est invisible pour docker.exe.
  env_file="${LOG_DIR}/.env.${name}"
  {
    printf 'KEYCLOAK_IMAGE=%s\n' "${IMAGE}"
    printf 'KEYCLOAK_VERIFY_PORT=%s\n' "${HOST_PORT}"
    printf 'REALM_FILE=%s\n' "${realm_host}"
  } >"${env_file}"
  env_file_host="$(docker_path "${env_file}")"

  docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" --env-file "${env_file_host}" up -d >>"${log}" 2>&1 || {
      echo "ERROR: compose up failed for ${base}" >&2
      cat "${log}" >&2 || true
      rm -f "${env_file}"
      fail=1
      continue
    }
  rm -f "${env_file}"

  url="http://127.0.0.1:${HOST_PORT}/realms/socle/.well-known/openid-configuration"
  ok=0
  for ((i = 1; i <= TIMEOUT_SEC; i++)); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "${url}" || true)"
    if [[ "${code}" == "200" ]]; then
      ok=1
      echo "OK ${base}: ${url} → 200 (${i}s)"
      break
    fi
    # Laisser quelques secondes au démarrage avant de conclure à un crash.
    if [[ "${i}" -ge 15 ]] \
        && ! docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" ps --status running --services 2>/dev/null \
            | grep -qx keycloak; then
      echo "ERROR: Keycloak is not running while importing ${base}" >&2
      break
    fi
    sleep 1
  done

  docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" logs --no-color keycloak >"${log}" 2>&1 || true
  if grep -qE 'Unrecognized field|Failed to start server|Failed to run import' "${log}"; then
    echo "ERROR: Keycloak import/start failed for ${base}" >&2
    ok=0
  fi

  if [[ "${ok}" -ne 1 ]]; then
    echo "ERROR: ${base} did not reach ${url} with HTTP 200 within ${TIMEOUT_SEC}s" >&2
    echo "---- last 80 lines of ${log} ----" >&2
    tail -n 80 "${log}" >&2 || true
    fail=1
  fi

  cleanup
done

trap - EXIT

if [[ "${fail}" -ne 0 ]]; then
  echo "Realm import verification FAILED (logs in ${LOG_DIR})" >&2
  exit 1
fi
echo "All realm files imported successfully."
