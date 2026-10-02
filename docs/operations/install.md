# Installation

Production artifacts live under [`deploy/`](../../deploy/). Images are published to GHCR on version tags; see [`deploy/README.md`](../../deploy/README.md).

## Prerequisites

| Component | Minimum | Notes |
|-----------|---------|--------|
| Docker Engine + Compose v2 | recent | Production stack is Compose-first |
| Kubernetes (optional) | 1.28+ | Helm chart at `deploy/helm/socle` |
| PostgreSQL 16 | (embedded in stack) | Databases: `socle_core`, `openfga`, `temporal`, `temporal_visibility` |
| IdP (OIDC) | — | Your corporate IdP, or **demo only** Keycloak (`demo-idp` profile) |
| TLS / DNS | — | Public hostname pointing at the edge proxy |

### Sizing hints

| Profile | vCPU | RAM | Disk |
|---------|------|-----|------|
| Small (≤50 users, git storage) | 4 | 8 GiB | 50 GiB SSD (+ Git volume growth) |
| Medium | 8 | 16 GiB | 100 GiB+ |
| Git storage | 1 backend replica | — | Persistent volume for `/data/git-content` |
| Relational storage | 2+ backend replicas possible | — | Postgres IOPS matter for search projection |

Temporal and OpenFGA run in-process with Postgres in the default Compose stack; plan headroom for workflow history and FGA tuple growth.

## Docker Compose (production)

1. Copy the example environment file:

   ```bash
   cp deploy/compose/.env.production.example deploy/compose/.env
   ```

2. Set **every** secret and hostname (no `CHANGE_ME`, `change-me`, `admin`, or empty `POSTGRES_PASSWORD` / `OIDC_CLIENT_SECRET`). The backend rejects weak defaults in production profiles.

3. Choose **storage** (see below) — set `SOCLE_STORAGE_PROVIDER` to `relational` or `git` before first write.

4. Configure OIDC to your IdP (issuer, client id/secret, frontend client id, CORS). See [Identity providers](../identity-providers.md).

5. Optional first admin bootstrap (when using `socle.identity.role-source` `INTERNAL` or `BOTH`):

   ```yaml
   # or equivalent env: SOCLE_IDENTITY_BOOTSTRAP_ADMIN_SUBJECTS=<oidc-sub>
   socle.identity.bootstrap-admin-subjects:
     - "<subject from your IdP>"
   ```

6. Start the stack:

   ```bash
   cd deploy/compose
   docker compose --env-file .env up -d
   ```

   Build from source instead of pulling GHCR:

   ```bash
   VERSION=local docker compose --env-file .env up -d --build
   ```

7. **OpenFGA store/model** — with `OPENFGA_AUTO_INIT=true` (Compose default), the **backend** creates or reuses the unique store named `socle` and publishes the model. Do **not** rely on `openfga-init` to create stores (it only waits for OpenFGA health). Prefer setting `OPENFGA_STORE_ID` explicitly after the first bootstrap; see [`backup-restore.md`](backup-restore.md) if several stores named `socle` appear.

### Volumes and permissions (non-root backend UID 10001)

Named Compose volumes for `/data/git-content` and `/openfga-config` inherit ownership from the image on first create (directories exist in the image as UID 10001).

For **bind mounts**, fix ownership on the host before start:

```bash
sudo mkdir -p /var/lib/socle/git-content /var/lib/socle/openfga-config
sudo chown -R 10001:10001 /var/lib/socle/git-content /var/lib/socle/openfga-config
```

Then map them in Compose (`./data/git:/data/git-content:rw`, etc.). The container no longer runs as root to `chown` at startup.

### Demo IdP profile (non-production)

For local demos only:

1. Uncomment `CREATE DATABASE keycloak;` in `deploy/compose/postgres/init-databases.sql` **before** the first Postgres volume init (or recreate the volume).
2. Set `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` in `.env`.
3. Point OIDC at Keycloak (browser-visible issuer), e.g. `OIDC_ISSUER_URI=http://localhost:8081/realms/socle`, and align SPA redirect URIs in the realm export.
4. Start with profile:

   ```bash
   docker compose --env-file .env --profile demo-idp up -d
   ```

## Helm (Kubernetes)

1. Configure `deploy/helm/socle/values.yaml` (or `-f` overrides): Postgres host or `postgres.embedded`, `storage.provider`, OpenFGA/Temporal mode, `idp.*`, ingress host/TLS, secrets via `existingSecret` or `secrets.*`.
2. Install:

   ```bash
   helm upgrade --install socle deploy/helm/socle \
     --namespace socle --create-namespace \
     -f my-values.yaml
   ```

3. When `storage.provider=git`, the chart creates a PVC unless `storage.git.existingClaim` is set. Use `backend.replicas=1` (the chart **fails** if replicas > 1 with git). Backend runs as UID/GID **10001** (`fsGroup: 10001`).
4. Wire ingress to backend (`/api`, `/actuator`) and frontend (`/`). Container UIDs: backend **10001**, frontend (nginx-unprivileged) **101**, worker (distroless nonroot) **65532**.

## Storage choice: `relational` vs `git`

This is an **instance-level, practically irreversible** decision without a planned migration:

| Mode | Content source of truth | Scale-out |
|------|-------------------------|-----------|
| `relational` | `documents.body` JSONB in Postgres | Multiple backend replicas |
| `git` | Git repo at `SOCLE_STORAGE_GIT_PATH` | **Single** backend writer (shared volume) |

Switching providers after data exists is blocked at startup:

- **`relational` → `git`** — `GitStorageConsistencyValidator` refuses boot if any active document lacks `git_head_sha` (would silently read stale Postgres projection).
- **`git` → `relational`** — `RelationalStorageConsistencyValidator` refuses boot if active documents have `git_head_sha` but no usable `body` JSONB.

Plan migrations explicitly; do not toggle `SOCLE_STORAGE_PROVIDER` on a live dataset.

## Identity (IdP)

All OIDC claim mapping, role sources, and IdP change procedures: [../identity-providers.md](../identity-providers.md).

Runtime SPA config is served by the backend: `GET /api/v1/public/auth-config`.

## Post-install checks

1. **Health** — `GET https://<DOMAIN>/actuator/health` (or via Caddy) → `UP`; readiness includes dependencies.
2. **Login** — Open `/`, complete OIDC login, confirm `GET /api/v1/me` (or UI profile) succeeds.
3. **Create a space** — `/spaces` → create; creator becomes space owner in OpenFGA.
4. **Grant access** — `/access` or `POST /api/v1/access/space/{id}` if another user must edit.
5. **Drift diagnostics (git mode, admin)** — after traffic, expect zero drift:
   - `GET /api/v1/admin/storage/git-projection-drift`
   - `GET /api/v1/admin/authz/visibility-drift`
   - `GET /api/v1/admin/authz/document-links-drift`

See [`backup-restore.md`](backup-restore.md) for backup scope and restore verification.
