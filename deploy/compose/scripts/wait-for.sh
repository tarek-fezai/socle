#!/usr/bin/env sh
# Optional: wait for a TCP host:port (BusyBox/Alpine friendly).
set -eu
HOST="${1:?host}"
PORT="${2:?port}"
TIMEOUT="${3:-120}"
end=$(( $(date +%s) + TIMEOUT ))
while ! nc -z "$HOST" "$PORT" 2>/dev/null; do
  if [ "$(date +%s)" -ge "$end" ]; then
    echo "wait-for: timeout waiting for ${HOST}:${PORT}" >&2
    exit 1
  fi
  sleep 1
done
echo "wait-for: ${HOST}:${PORT} is up"
