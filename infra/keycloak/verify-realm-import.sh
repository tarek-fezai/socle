#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Vérifie que chaque fichier realm du dépôt s'importe avec l'image Keycloak
# utilisée en Compose (start-dev + --import-realm, H2 embarqué).
#
# Sous Git Bash/Windows : exporter MSYS_NO_PATHCONV=1 avant d'appeler ce script
# (sinon docker -v mangle les chemins). Sur Linux CI, aucun réglage n'est requis.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.0}"
HOST_PORT="${KEYCLOAK_VERIFY_PORT:-18081}"
TIMEOUT_SEC="${KEYCLOAK_VERIFY_TIMEOUT_SEC:-300}"
LOG_DIR="${KEYCLOAK_VERIFY_LOG_DIR:-${ROOT}/.tmp-keycloak-realm-logs}"

REALMS=(
  "${ROOT}/infra/keycloak/realm-socle.dev.json"
  "${ROOT}/e2e/realm-socle.ci.json"
)

mkdir -p "${LOG_DIR}"
rm -f "${LOG_DIR}"/*.log

fail=0
for realm in "${REALMS[@]}"; do
  base="$(basename "${realm}")"
  name="${base%.json}"
  cname="kc-realm-verify-${name}-$$"
  log="${LOG_DIR}/${name}.log"
  import_dir="$(mktemp -d "${TMPDIR:-/tmp}/kc-realm-${name}.XXXXXX")"

  echo "==> Import check: ${base} (image ${IMAGE})"
  if [[ ! -f "${realm}" ]]; then
    echo "ERROR: missing realm file ${realm}" >&2
    fail=1
    rm -rf "${import_dir}"
    continue
  fi
  cp "${realm}" "${import_dir}/${base}"

  docker rm -f "${cname}" >/dev/null 2>&1 || true
  if ! docker run -d --name "${cname}" \
      -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
      -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
      -e KC_HTTP_PORT=8080 \
      -e KC_HEALTH_ENABLED=true \
      -p "127.0.0.1:${HOST_PORT}:8080" \
      -v "${import_dir}:/opt/keycloak/data/import:ro" \
      "${IMAGE}" \
      start-dev --import-realm --http-enabled=true --hostname-strict=false \
      >/dev/null 2>"${log}"; then
    echo "ERROR: failed to start container for ${base}" >&2
    cat "${log}" >&2 || true
    fail=1
    rm -rf "${import_dir}"
    continue
  fi

  url="http://127.0.0.1:${HOST_PORT}/realms/socle/.well-known/openid-configuration"
  ok=0
  for ((i = 1; i <= TIMEOUT_SEC; i++)); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "${url}" || true)"
    if [[ "${code}" == "200" ]]; then
      ok=1
      echo "OK ${base}: ${url} → 200 (${i}s)"
      break
    fi
    if ! docker inspect -f '{{.State.Running}}' "${cname}" 2>/dev/null | grep -q true; then
      echo "ERROR: Keycloak exited while importing ${base}" >&2
      break
    fi
    sleep 1
  done

  docker logs "${cname}" >"${log}" 2>&1 || true
  if grep -q 'Unrecognized field' "${log}"; then
    echo "ERROR: Keycloak rejected unknown JSON field while importing ${base}" >&2
    ok=0
  fi

  docker rm -f "${cname}" >/dev/null 2>&1 || true
  rm -rf "${import_dir}"

  if [[ "${ok}" -ne 1 ]]; then
    echo "ERROR: ${base} did not reach ${url} with HTTP 200 within ${TIMEOUT_SEC}s" >&2
    echo "---- last 80 lines of ${log} ----" >&2
    tail -n 80 "${log}" >&2 || true
    fail=1
  fi
done

if [[ "${fail}" -ne 0 ]]; then
  echo "Realm import verification FAILED (logs in ${LOG_DIR})" >&2
  exit 1
fi
echo "All realm files imported successfully."
