# E2E (Playwright)

Runs against the **production Compose** stack with `--profile demo-idp` (Keycloak dev realm).

## Environment

| Variable | Default | Purpose |
|----------|---------|---------|
| `BASE_URL` | `http://127.0.0.1` | Caddy edge (SPA + API) |
| `API_BASE_URL` | same as `BASE_URL` | REST calls |
| `KEYCLOAK_BASE_URL` | `http://127.0.0.1:8081` | Password grant + browser IdP |
| `E2E_USER_A` / `E2E_PASS_A` | `contributeur` / `contributeur` | Submitter |
| `E2E_USER_B` / `E2E_PASS_B` | `auditeur` / `auditeur` | Approver (after role assignment) |
| `E2E_RUN_BACKUP` | — | Set locally to enable backup/restore step |

## Local run

```bash
./e2e/scripts/generate-ci-env.sh deploy/compose/.env.ci
# Enable Keycloak DB once (empty volume):
#   uncomment CREATE DATABASE keycloak in deploy/compose/postgres/init-databases.sql

cd deploy/compose
docker compose -f docker-compose.yml -f ../../e2e/docker-compose.e2e.yml \
  --env-file .env.ci --profile demo-idp up -d --build
../../e2e/scripts/sync-openfga-env.sh
../../e2e/scripts/wait-stack.sh .env.ci

cd ../../e2e
pnpm install
E2E_RUN_BACKUP=1 pnpm test
```

## Selectors

UI steps use existing `data-testid` where available (`step-1`…`step-3`, `comments-panel`, `comment-selection-btn`, etc.). Document creation and approval setup use the REST API when faster or more stable.
