# Local development

Day-to-day hacking uses **`infra/docker-compose.yml`** (Postgres, Keycloak dev realm, Temporal, OpenFGA). Production-like stacks live under [`deploy/compose/`](../deploy/compose/) — see [`operations/install.md`](operations/install.md).

## Prerequisites

| Tool | Version |
|------|---------|
| Java (Temurin) | 21 |
| Maven | 3.9+ |
| Go | 1.22+ |
| Node.js | 20 LTS |
| pnpm | 9+ |
| Docker + Compose | recent |

Copy environment:

```bash
cp .env.example .env
```

## SQL schema (Flyway only)

Canonical schema: `backend/src/main/resources/db/migration/V1__init.sql` (and later `V*`). Applied on backend start. Root `socle_schema.sql` is reference only — do not run manually.

If an old migration was applied locally, reset Postgres:

```bash
docker compose -f infra/docker-compose.yml --env-file .env down -v
docker compose -f infra/docker-compose.yml --env-file .env up -d
```

## Start infra

```bash
docker compose -f infra/docker-compose.yml --env-file .env up -d
```

Starts **Postgres** and **Keycloak** (realm `socle` from `infra/keycloak/realm-socle.dev.json`, **DEV ONLY**). Other OIDC IdPs: [`identity-providers.md`](identity-providers.md).

### Application processes

```bash
# Backend
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
# or: mvn spring-boot:run -Dspring-boot.run.profiles=local

# Frontend — use 127.0.0.1 for Keycloak redirect URIs
cd frontend
pnpm install
pnpm dev
```

Open **http://127.0.0.1:5173** (not `localhost` unless realm redirect URIs include it).

### Verify OIDC (Keycloak example)

```bash
curl -i http://127.0.0.1:8080/api/documents   # 401 without token

TOKEN=$(curl -s -X POST "http://localhost:8081/realms/socle/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=socle-frontend&username=contributeur&password=contributeur" \
  | jq -r .access_token)
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/documents
```

SPA auth config: `GET http://127.0.0.1:8080/api/v1/public/auth-config`

### Exposed services (infra compose)

| Service | URL / port |
|---------|------------|
| Postgres | `localhost:5433` — `socle_core`, `keycloak`, `temporal`, `temporal_visibility`, `openfga` |
| Keycloak | http://localhost:8081 (admin from `infra/docker-compose.yml`) |
| Temporal gRPC | `localhost:7233` |
| Temporal UI | http://localhost:8088 |
| OpenFGA HTTP | http://localhost:8082 |
| OpenFGA Playground | http://localhost:3001 |

## Auth → authorization → workflows

1. **OIDC** — JWT bearer; roles from claims by default.
2. **OpenFGA** — model in `infra/openfga/`; bootstrap store: `./infra/scripts/bootstrap-openfga.sh` (or `.ps1`).
3. **Temporal** — approvals via `DocumentApprovalWorkflow`; UI http://localhost:8088

Grant `editor` on a space before creating documents: UI **Accès** or `POST /api/v1/access/space/{spaceId}`.

Integration audit doc: [`audit-integration-auth-authz-workflow.md`](audit-integration-auth-authz-workflow.md)

## Backend checks

- Health: http://localhost:8080/actuator/health
- OpenAPI: http://localhost:8080/ApiDocs.dc.html
- Ping: http://localhost:8080/api/v1/ping

Profile `local` relaxes OIDC startup; without it, Keycloak must be up.

## Frontend

```bash
cd frontend && pnpm install && pnpm dev
```

## Webhook worker

```bash
cd webhook-worker && go run ./cmd/worker
```

Health: http://localhost:8090/healthz

## Demo Keycloak accounts (dev only)

- **contributeur@example.com** / **contributeur** (realm role `contributeur`)
- **auditeur@example.com** / **auditeur**
- **integrateur@example.com** / **integrateur**
- SPA client: `socle-frontend` (PKCE, redirect `http://127.0.0.1:5173/*`)

## OpenFGA & Temporal quick checks

```bash
curl -s http://localhost:8082/healthz
docker exec socle-temporal temporal operator cluster health
```

## E2E stack (CI / demo deploy)

Playwright tests under `e2e/` target the **production Compose** file with `--profile demo-idp`. See `e2e/README.md` and `.github/workflows/ci.yml`.
