# Configuration reference

Environment variables override `backend/src/main/resources/application.yml`. Spring Boot relaxed binding applies for `socle.*` (e.g. `socle.identity.role-source` → `SOCLE_IDENTITY_ROLE_SOURCE`).

**Parity:** `ConfigurationDocumentationParityTest` fails CI if a `${ENV_VAR}` placeholder in `application.yml` is missing from this file.

Legend: **Required** = must be set for production Compose (no safe default). **Secret** = treat as credential; rotate via your secret store.

## Server & Spring datasource

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `SERVER_PORT` | `8080` | no | no | HTTP port inside the backend container |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/socle_core` | yes (prod) | no | JDBC URL to `socle_core` |
| `SPRING_DATASOURCE_USERNAME` | `socle` | yes (prod) | no | DB user |
| `SPRING_DATASOURCE_PASSWORD` | `socle` | yes (prod) | **yes** | DB password |

## OIDC (resource server & OAuth client)

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `OIDC_ISSUER_URI` | `http://localhost:8081/realms/socle` | yes (prod) | no | JWT issuer; also `socle.identity.issuer-uri` |
| `OIDC_CLIENT_ISSUER_URI` | same as `OIDC_ISSUER_URI` | no | no | Issuer reachable by backend for OAuth2 client discovery (demo Compose: `http://keycloak:8080/realms/socle`) |
| `OIDC_JWK_SET_URI` | derived from issuer | no | no | JWKS URL if issuer metadata is unreachable from backend |
| `OIDC_CLIENT_ID` | `socle-backend` | no | no | OAuth client for backend (if used) |
| `OIDC_CLIENT_SECRET` | `change-me` | yes (prod) | **yes** | Backend OAuth client secret |
| `OIDC_FRONTEND_CLIENT_ID` | `socle-frontend` | no | no | Public SPA client id (`socle.identity.client-id`) |

## Instance & storage

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `SOCLE_INSTANCE_DISPLAY_NAME` | `Socle` | no | no | Product name in UI (`socle.instance.display-name`) |
| `SOCLE_STORAGE_PROVIDER` | `relational` (local yml); Compose example `git` | **yes** | no | `relational` or `git` — no silent default at runtime |
| `SOCLE_STORAGE_GIT_PATH` | `./data/git-content` | when `git` | no | Git repo path (`socle.storage.git-repository-path`) |
| `SOCLE_STORAGE_GIT_REPOSITORY_PATH` | — | no | no | Compose `.env` alias; mapped to `SOCLE_STORAGE_GIT_PATH` in stack |

## Blob storage (attachments)

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `SOCLE_BLOB_PROVIDER` | `local` | no | no | Exclusive binary store: `local` or `s3` (S3-compatible: Garage, SeaweedFS…). No AWS default endpoint (`socle.blob.provider`) |
| `SOCLE_BLOB_LOCAL_DIR` | `./data/blobs` | when `local` | no | Local blob root; atomic temp+rename (`socle.blob.local.dir`). Helm: PVC RWO; `backend.replicas>1` refused |
| `SOCLE_BLOB_S3_ENDPOINT` | empty | when `s3` | no | S3 API base URL — **required** for `s3` (e.g. `http://garage:3900`) |
| `SOCLE_BLOB_S3_REGION` | `us-east-1` | no | no | Region string expected by the S3 client |
| `SOCLE_BLOB_S3_BUCKET` | empty | when `s3` | no | Bucket name |
| `SOCLE_BLOB_S3_ACCESS_KEY` | empty | when `s3` | **yes** | Access key |
| `SOCLE_BLOB_S3_SECRET_KEY` | empty | when `s3` | **yes** | Secret key |
| `SOCLE_BLOB_S3_PATH_STYLE` | `true` | no | no | Path-style addressing (Garage / MinIO) |
| `SOCLE_BLOB_S3_SSE` | `true` | no | no | Request SSE-S3 (`AES256`) when the service supports it |
| `SOCLE_ATTACHMENT_MAX_MB` | `25` | no | no | Max upload size (MB) for non-video MIME types |
| `SOCLE_ATTACHMENT_MAX_VIDEO_MB` | `200` | no | no | Max upload size (MB) for `video/mp4` and `video/webm`; also sets `spring.servlet.multipart.max-*-size` |
| `SOCLE_ATTACHMENT_ORPHAN_RETENTION_DAYS` | `7` | no | no | Days before unreferenced uploads are purged (draft-referenced IDs are skipped) |
| `SOCLE_ATTACHMENT_MAX_IMAGE_PIXELS` | `40000000` | no | no | Max width×height before pixel decode (decompression-bomb guard) |
| `SOCLE_ATTACHMENT_ALLOWED_TYPES` | png/jpeg/webp/gif, pdf, mp4/webm, office, txt/csv, zip | no | no | Comma-separated MIME allow-list (Tika-detected). SVG not included; WebP via TwelveMonkeys |
| `SOCLE_LINK_PREVIEW_ENABLED` | `false` | no | no | When false: local URL+domain card only, no outbound fetch |
| `SOCLE_LINK_PREVIEW_ALLOWED_DOMAINS` | empty | when enabled | no | Comma-separated host allow-list (exact or suffix) |
| `SOCLE_LINK_PREVIEW_PROXY_URL` | empty | no | no | Optional outbound HTTP proxy for preview fetches |
| `SOCLE_LINK_PREVIEW_CACHE_TTL_HOURS` | `168` | no | no | Preview cache TTL (hours) |
| `SOCLE_LINK_PREVIEW_RATE_LIMIT_PER_MINUTE` | `10` | no | no | Per-user preview fetch rate limit |

## Temporal

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `TEMPORAL_TARGET` | `localhost:7233` | yes (prod) | no | gRPC host:port (`socle.temporal.target`) |
| `TEMPORAL_NAMESPACE` | `default` | no | no | Temporal namespace |

## OpenFGA

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `OPENFGA_API_URL` | `http://localhost:8082` | yes (prod) | no | OpenFGA HTTP API |
| `OPENFGA_STORE_ID` | empty | often | no | Store id; `openfga-init` may write to shared volume |
| `OPENFGA_MODEL_ID` | empty | often | no | Authorization model id |
| `OPENFGA_LIST_OBJECTS_MAX_RESULTS` | `1000` | no | no | ListObjects ceiling warning threshold |
| `OPENFGA_MAX_CHECKS_PER_BATCH_CHECK` | `50` | no | no | BatchCheck batch size |
| `OPENFGA_BATCH_CHECK_PARALLELISM` | `4` | no | no | Parallel BatchCheck workers |
| `OPENFGA_DOCUMENT_CHECK_WARN_THRESHOLD` | `500` | no | no | WARN if viewer checks exceed N |
| `SOCLE_FGA_VISIBILITY_MIGRATION_ON_STARTUP` | `false` | no | no | Run visibility tuple migration at boot |
| `OPENFGA_AUTO_INIT` | `false` (local); `true` in prod image/compose | no | no | Create store + load/update model at boot (`socle.openfga.auto-init`) |
| `OPENFGA_STATE_DIR` | `./data/openfga` | no | no | Fingerprint/model id files for idempotent init (`socle.openfga.state-dir`) |
| `SOCLE_REJECT_WEAK_SECRETS` | `false` (local); `true` in prod image | yes (prod) | no | Refuse secrets equal to `change-me` / `admin` / `socle` / empty |

## CORS, folders, reliability, staleness, edit lock

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | yes (prod) | no | Comma-separated browser origins |
| `SOCLE_FOLDERS_MAX_DEPTH` | `5` | no | no | Max folder nesting |
| `SOCLE_RELIABILITY_DEFAULT_REVIEW_CYCLE_DAYS` | `365` | no | no | Default reliability review cycle |
| `SOCLE_STALENESS_THRESHOLD_DAYS` | `90` | no | no | Stale badge threshold (1–3650) |
| `SOCLE_EDIT_LOCK_HEARTBEAT_SECONDS` | `15` | no | no | Edit lock heartbeat interval |
| `SOCLE_EDIT_LOCK_TTL_SECONDS` | `45` | no | no | Edit lock expiry |
| `SOCLE_WRITING_ASSISTANT_LONG_PARAGRAPH_WORDS` | `120` | no | no | Writing assistant: flag paragraphs longer than N words (`socle.writing-assistant.long-paragraph-words`) |
| `SOCLE_OPENAPI_ENABLED` | `true` | no | no | Enable springdoc `/api-docs` and Swagger UI `/ApiDocs.dc.html`. In production set `false`, or rely on the `ADMINISTRATEUR_SYSTEME` gate |

## Version comparison (`socle.diff` / `DiffProperties`)

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `SOCLE_DIFF_MAX_LINES` | `20000` | no | no | History screen: max Markdown lines per side for `GET /api/v1/documents/{id}/versions/{a}/compare/{b}`; larger versions are rejected with HTTP 413 (`socle.diff.max-lines`, must be > 0) |

## Identity (`socle.identity` / `IdentityProperties`)

YAML-only defaults below can be overridden via env (Spring relaxed binding).

| Name / property | Default | Required | Secret | Description |
|-----------------|---------|----------|--------|-------------|
| `SOCLE_IDENTITY_SUBJECT_CLAIM` | `sub` | no | no | JWT claim for stable subject |
| `SOCLE_IDENTITY_EMAIL_CLAIM` | `email` | no | no | Email claim |
| `SOCLE_IDENTITY_NAME_CLAIM` | `name` | no | no | Display name claim |
| `SOCLE_IDENTITY_ROLES_CLAIM` | `realm_access.roles` | no | no | Claim path for IdP roles |
| `SOCLE_IDENTITY_ROLE_SOURCE` | `CLAIMS` | no | no | `CLAIMS`, `INTERNAL`, or `BOTH` |
| `SOCLE_IDENTITY_DEFAULT_ROLE` | `CONTRIBUTEUR` | no | no | Role if no mapping match |
| `SOCLE_IDENTITY_BOOTSTRAP_ADMIN_SUBJECTS` | empty | no | no | OIDC `sub` values → `ADMINISTRATEUR_SYSTEME` on first login (`INTERNAL`/`BOTH`) |
| `SOCLE_IDENTITY_LINK_BY_VERIFIED_EMAIL` | `false` | no | no | Auto-link by verified email (risky) |
| `SOCLE_IDENTITY_CLIENT_ID` | `socle-frontend` | no | no | Same as `OIDC_FRONTEND_CLIENT_ID` when set via yml |
| `SOCLE_IDENTITY_AUTHORIZATION_ENDPOINT` | derived | no | no | Override OIDC authorize URL |
| `SOCLE_IDENTITY_TOKEN_ENDPOINT` | derived | no | no | Override token URL |
| `SOCLE_IDENTITY_END_SESSION_ENDPOINT` | derived | no | no | Override logout URL |
| `SOCLE_IDENTITY_JWKS_URI` | derived | no | no | Override JWKS URL for SPA hints |
| `SOCLE_ACCESS_POLICY_MODE` | `jit` | no | no | Access policy: `jit`, `require-group`, or `provisioned-only` (`socle.identity.access-policy.mode`) |
| `SOCLE_IDENTITY_PASSKEY_ACR_VALUES` | empty | no | no | OIDC `acr_values` for passkey button; empty hides the button |
| `SOCLE_IDENTITY_IDP_DISPLAY_NAME` | empty | no | no | Optional IdP label exposed on public auth-config |
| `SOCLE_IDENTITY_SUPPORT_CONTACT` | empty | no | no | Support email for login / access-denied mailto links |

Role mapping keys (`socle.identity.role-mapping.*`) map IdP role names to platform roles; configure in YAML or `SOCLE_IDENTITY_ROLE_MAPPING_<KEY>`.

## Compose-only (not in `application.yml`)

| Name | Default | Required | Secret | Description |
|------|---------|----------|--------|-------------|
| `POSTGRES_USER` | — | yes | no | Postgres superuser/app user |
| `POSTGRES_PASSWORD` | — | yes | **yes** | Postgres password |
| `DATABASE_URL` | — | yes | **yes** | Webhook worker DSN (`socle_core`) |
| `DOMAIN` | `localhost` | no | no | Caddy site label |
| `VERSION` | `latest` | no | no | Image tag for GHCR builds |
| `KEYCLOAK_ADMIN` | — | demo-idp | no | Keycloak bootstrap admin user |
| `KEYCLOAK_ADMIN_PASSWORD` | — | demo-idp | **yes** | Keycloak bootstrap password |
| `KEYCLOAK_HTTP_PORT` | `8081` | no | no | Host port for demo Keycloak (E2E) |

## Helm

Values in `deploy/helm/socle/values.yaml` map to the same env keys via the backend ConfigMap/Secret templates (`SOCLE_STORAGE_PROVIDER`, `OIDC_*`, `OPENFGA_*`, `TEMPORAL_*`, datasource fields).
