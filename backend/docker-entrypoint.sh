#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
set -euo pipefail
mkdir -p /data/git-content /openfga-config
chown -R appuser:appuser /data/git-content /openfga-config 2>/dev/null || true
exec runuser -u appuser -- java -jar /app/app.jar "$@"
