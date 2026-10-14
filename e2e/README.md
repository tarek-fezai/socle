# E2E (Playwright)

Runs against the dedicated Compose project `socle-e2e` (`--profile demo-idp`, Keycloak dev realm). The production compose file is reused as a definition only: `-p socle-e2e` overrides its `name:`.

## Isolation

L'e2e, en local comme en CI, passe uniquement par `e2e/scripts/compose-e2e.sh`. Ce script force le projet `socle-e2e` et `--env-file .env.ci`. Le `-p` de la ligne de commande l'emporte sur `name: socle-production`. Il refuse de s'exécuter si `COMPOSE_PROJECT_NAME` vaut `socle-production`, si `.env.production` est passé, ou si des conteneurs du projet `socle-production` seraient visés. Les volumes du fichier Compose ne sont pas `external` et n'ont pas de `name:` fixe : Compose les préfixe par le projet (`socle-e2e_…`), distincts de l'instance d'exploitation. Si le port 80 ou 8081 est déjà pris, le script s'arrête sans modifier l'autre instance. L'e2e construit des images :e2e, jamais celles de l'instance d'exploitation. Ne jamais lancer l’e2e sur une instance d’exploitation.

## Environment

| Variable | Default | Purpose |
|----------|---------|---------|
| `BASE_URL` | `http://127.0.0.1` | Caddy edge (SPA + API) |
| `API_BASE_URL` | same as `BASE_URL` | REST calls |
| `KEYCLOAK_BASE_URL` | `http://127.0.0.1:8081` | Password grant + browser IdP |
| `E2E_USER_A` / `E2E_PASS_A` | `contributeur` / `contributeur` | Submitter |
| `E2E_USER_B` / `E2E_PASS_B` | `auditeur` / `auditeur` | Approver (after role assignment) |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | — | Bootstrap admin du Keycloak e2e (généré dans `deploy/compose/.env.ci`). Requis pour créer les invités du spec invitations. La CI les exporte avant Playwright. |
| `E2E_RUN_BACKUP` | — | Set locally to enable backup/restore step |

## Local run

```bash
./e2e/scripts/generate-ci-env.sh deploy/compose/.env.ci
# Enable Keycloak DB once (empty volume):
#   uncomment CREATE DATABASE keycloak in deploy/compose/postgres/init-databases.sql

./e2e/scripts/compose-e2e.sh up -d --build
./e2e/scripts/sync-openfga-env.sh
./e2e/scripts/wait-stack.sh .env.ci

cd e2e
pnpm install
E2E_RUN_BACKUP=1 pnpm test
```

## Selectors

UI steps use existing `data-testid` where available (`creation-warnings`, `comments-panel`, `comment-selection-btn`, etc.). Document creation and approval setup use the REST API when faster or more stable.
