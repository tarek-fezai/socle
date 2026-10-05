#!/usr/bin/env bash
# Vérifie que la clé publique Ed25519 embarquée dans l'image backend correspond à EXPECTED.
# Usage :
#   verify-embedded-licence-key.sh <image-or-container-ref> <expected-b64-file-or-literal>
# Extrait licence/ed25519-public.b64 depuis /app/app.jar (aucune clé privée).
set -euo pipefail

REF="${1:?image ou conteneur requis}"
EXPECTED_SRC="${2:?fichier ou littéral Base64 attendu}"

if [[ -f "${EXPECTED_SRC}" ]]; then
  EXPECTED="$(tr -d '\n\r ' < "${EXPECTED_SRC}")"
else
  EXPECTED="$(printf '%s' "${EXPECTED_SRC}" | tr -d '\n\r ')"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# Accepte un ID d'image ou un nom de service déjà démarré via docker create depuis l'image.
if docker image inspect "${REF}" >/dev/null 2>&1; then
  CID="$(docker create "${REF}")"
  trap 'docker rm -f "${CID}" >/dev/null 2>&1 || true; rm -rf "${TMP}"' EXIT
  docker cp "${CID}:/app/app.jar" "${TMP}/app.jar"
  docker rm -f "${CID}" >/dev/null
elif docker inspect "${REF}" >/dev/null 2>&1; then
  docker cp "${REF}:/app/app.jar" "${TMP}/app.jar"
else
  echo "Référence Docker inconnue : ${REF}" >&2
  exit 1
fi

python3 - <<'PY' "${TMP}/app.jar" "${TMP}/embedded.b64"
import sys, zipfile
jar, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as z:
    data = z.read("BOOT-INF/classes/licence/ed25519-public.b64")
open(out, "wb").write(data)
PY

ACTUAL="$(tr -d '\n\r ' < "${TMP}/embedded.b64")"
if [[ "${ACTUAL}" != "${EXPECTED}" ]]; then
  echo "Clé publique embarquée ≠ attendue" >&2
  echo "  expected=${EXPECTED}" >&2
  echo "  actual  =${ACTUAL}" >&2
  exit 1
fi
echo "OK: clé publique embarquée correspond (${#ACTUAL} caractères Base64)"
