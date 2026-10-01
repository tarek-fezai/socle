#!/usr/bin/env sh
# Idempotent OpenFGA bootstrap (fallback). Prefer backend auto-init of store/model when available.
set -eu

OPENFGA_API_URL="${OPENFGA_API_URL:-http://openfga:8080}"
CONFIG_DIR="${OPENFGA_CONFIG_DIR:-/openfga-config}"
STORE_ENV="${CONFIG_DIR}/store.env"
MODEL_FILE="${OPENFGA_MODEL_FILE:-/openfga/model.fga}"

mkdir -p "$CONFIG_DIR"

if [ -f "$STORE_ENV" ]; then
  # shellcheck disable=SC1090
  . "$STORE_ENV"
fi

if [ -n "${OPENFGA_STORE_ID:-}" ]; then
  echo "openfga-init: reusing store ${OPENFGA_STORE_ID} from ${STORE_ENV}"
  exit 0
fi

echo "openfga-init: waiting for OpenFGA HTTP..."
i=0
while ! wget -q -O /dev/null "${OPENFGA_API_URL}/healthz" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "openfga-init: OpenFGA not healthy" >&2
    exit 1
  fi
  sleep 2
done

echo "openfga-init: creating store (if missing)..."
STORE_RESP="$(wget -q -O - --header='Content-Type: application/json' \
  --post-data='{"name":"socle"}' "${OPENFGA_API_URL}/stores" 2>/dev/null || true)"
STORE_ID="$(printf '%s' "$STORE_RESP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"

if [ -z "$STORE_ID" ]; then
  LIST_RESP="$(wget -q -O - "${OPENFGA_API_URL}/stores" 2>/dev/null || true)"
  STORE_ID="$(printf '%s' "$LIST_RESP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)"
fi

if [ -z "$STORE_ID" ]; then
  echo "openfga-init: could not resolve store id" >&2
  exit 1
fi

MODEL_ID=""
if [ -f "$MODEL_FILE" ]; then
  echo "openfga-init: publishing authorization model from ${MODEL_FILE}..."
  if command -v fga >/dev/null 2>&1; then
    MODEL_ID="$(fga model write --store-id "$STORE_ID" --file "$MODEL_FILE" --format fga 2>/dev/null | sed -n 's/.*authorization_model_id=\([^ ]*\).*/\1/p' || true)"
  else
    echo "openfga-init: fga CLI not present; skip model write (backend may publish model on startup)"
  fi
fi

{
  echo "# Written by openfga-init.sh — optional fallback for OPENFGA_STORE_ID / OPENFGA_MODEL_ID"
  echo "OPENFGA_STORE_ID=${STORE_ID}"
  [ -n "$MODEL_ID" ] && echo "OPENFGA_MODEL_ID=${MODEL_ID}"
} > "${STORE_ENV}.tmp"
mv "${STORE_ENV}.tmp" "$STORE_ENV"
chmod 644 "$STORE_ENV"
echo "openfga-init: wrote ${STORE_ENV}"
