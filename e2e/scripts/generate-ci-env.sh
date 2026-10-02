#!/usr/bin/env bash
# Writes deploy/compose/.env.ci for GitHub Actions / local E2E.
set -euo pipefail

OUT="${1:-deploy/compose/.env.ci}"
PG_PASS="$(openssl rand -hex 16)"
OIDC_SECRET="$(openssl rand -hex 24)"
KC_PASS="$(openssl rand -hex 12)"

cat > "$OUT" <<EOF
DOMAIN=http://127.0.0.1
VERSION=local
COMPOSE_PROJECT_NAME=socle-production

POSTGRES_USER=socle
POSTGRES_PASSWORD=${PG_PASS}

SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/socle_core
SPRING_DATASOURCE_USERNAME=socle
SPRING_DATASOURCE_PASSWORD=${PG_PASS}

SOCLE_STORAGE_PROVIDER=git
SOCLE_STORAGE_GIT_REPOSITORY_PATH=/data/git-content

OPENFGA_API_URL=http://openfga:8080
OPENFGA_STORE_ID=
OPENFGA_MODEL_ID=
OPENFGA_AUTO_INIT=true
OPENFGA_STATE_DIR=/openfga-config
SOCLE_REJECT_WEAK_SECRETS=true

TEMPORAL_TARGET=temporal:7233
TEMPORAL_NAMESPACE=default

OIDC_ISSUER_URI=http://127.0.0.1:8081/realms/socle
OIDC_CLIENT_ISSUER_URI=http://keycloak:8080/realms/socle
OIDC_JWK_SET_URI=http://keycloak:8080/realms/socle/protocol/openid-connect/certs
OIDC_CLIENT_ID=socle-backend
OIDC_CLIENT_SECRET=${OIDC_SECRET}
OIDC_FRONTEND_CLIENT_ID=socle-frontend

SOCLE_INSTANCE_DISPLAY_NAME=Socle E2E
CORS_ALLOWED_ORIGINS=http://127.0.0.1,http://localhost

DATABASE_URL=postgres://socle:${PG_PASS}@postgres:5432/socle_core?sslmode=disable

KEYCLOAK_ADMIN=e2e_admin
KEYCLOAK_ADMIN_PASSWORD=${KC_PASS}
KEYCLOAK_HTTP_PORT=8081

SOCLE_IDENTITY_ROLE_SOURCE=BOTH
SOCLE_IDENTITY_BOOTSTRAP_ADMIN_SUBJECTS=11111111-1111-1111-1111-111111111111
EOF

echo "Wrote ${OUT}"
