#!/usr/bin/env bash
set -euo pipefail

OPENFGA_API_URL="${OPENFGA_API_URL:-http://localhost:8082}"
MODEL_FILE="$(dirname "$0")/../openfga/model.json"
ENV_FILE="$(dirname "$0")/../../.env"

echo "OpenFGA API: ${OPENFGA_API_URL}"

STORE_ID="${OPENFGA_STORE_ID:-}"
if [[ -z "$STORE_ID" && -f "$ENV_FILE" ]]; then
  STORE_ID=$(grep -E '^\s*OPENFGA_STORE_ID=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
fi

if [[ -z "$STORE_ID" ]]; then
  echo "Creating OpenFGA store..."
  STORE_RESP=$(curl -s -X POST "${OPENFGA_API_URL}/stores" \
    -H "Content-Type: application/json" \
    -d '{"name":"socle"}')
  STORE_ID=$(echo "$STORE_RESP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
  echo "Store ID: ${STORE_ID}"
else
  echo "Reusing store: ${STORE_ID}"
fi

echo "Writing authorization model..."
MODEL_RESP=$(curl -s -X POST "${OPENFGA_API_URL}/stores/${STORE_ID}/authorization-models" \
  -H "Content-Type: application/json" \
  --data-binary @"$MODEL_FILE")

MODEL_ID=$(echo "$MODEL_RESP" | sed -n 's/.*"authorization_model_id":"\([^"]*\)".*/\1/p')
echo "Model ID: ${MODEL_ID}"
echo
echo "Export these into .env:"
echo "OPENFGA_STORE_ID=${STORE_ID}"
echo "OPENFGA_MODEL_ID=${MODEL_ID}"

if [[ -f "$ENV_FILE" ]]; then
  if grep -qE '^\s*OPENFGA_STORE_ID=' "$ENV_FILE"; then
    sed -i.bak "s|^OPENFGA_STORE_ID=.*|OPENFGA_STORE_ID=${STORE_ID}|" "$ENV_FILE"
  else
    echo "OPENFGA_STORE_ID=${STORE_ID}" >> "$ENV_FILE"
  fi
  if grep -qE '^\s*OPENFGA_MODEL_ID=' "$ENV_FILE"; then
    sed -i.bak "s|^OPENFGA_MODEL_ID=.*|OPENFGA_MODEL_ID=${MODEL_ID}|" "$ENV_FILE"
  else
    echo "OPENFGA_MODEL_ID=${MODEL_ID}" >> "$ENV_FILE"
  fi
  rm -f "${ENV_FILE}.bak"
  echo "Updated ${ENV_FILE}"
fi
