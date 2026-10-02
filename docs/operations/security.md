# Security operations

## Exposed surface

Production Compose exposes **only Caddy** on host ports **80** and **443** (`deploy/compose/Caddyfile`):

- `/api/*` and `/actuator/*` → backend
- `/` → frontend SPA

Postgres, OpenFGA, Temporal, and Keycloak (without `demo-idp`) should **not** be published to the host. Use Docker/Kubernetes network policies (`deploy/helm/socle/templates/networkpolicy.yaml`) to limit east-west traffic.

Actuator: health is used for probes; restrict `/actuator` at the ingress in high-trust environments (metrics/prometheus scraping via internal network only).

## Headers and TLS

- Terminate TLS at Caddy or ingress; enforce HTTPS redirects.
- Set `CORS_ALLOWED_ORIGINS` to your public origin only (no wildcard in production).
- Frontend nginx (`frontend/nginx.conf`) sets CSP (`frame-ancestors 'none'`), `X-Content-Type-Options`, and `Referrer-Policy`. `connect-src` / `form-action` allow `http:` and `https:` so the SPA can reach the client's IdP (demo HTTP Keycloak or production HTTPS). Add HSTS at Caddy/ingress.

## Secrets

| Secret | Rotation |
|--------|----------|
| `POSTGRES_PASSWORD` / `SPRING_DATASOURCE_PASSWORD` | Rotate in DB + all consumers; restart backend/worker |
| `OIDC_CLIENT_SECRET` | Rotate in IdP + backend env |
| Webhook/SIEM secrets | Via Socle admin UI (hashed at rest) |

Do not commit `deploy/compose/.env`. Prefer External Secrets / sealed secrets in Kubernetes.

## Vulnerability reporting

Report security issues privately via GitHub Security Advisories — see [`SECURITY.md`](../../SECURITY.md). Do not file public issues for vulnerabilities.

## Container image verification (Cosign)

Release images on GHCR are signed keyless (Sigstore). Example verification after pulling:

```bash
VERSION=1.2.3  # semver tag without v

cosign verify "ghcr.io/tarek-fezai/socle-backend:${VERSION}" \
  --certificate-identity-regexp='https://github.com/tarek-fezai/socle/.github/workflows/release.yml@refs/tags/v.*' \
  --certificate-oidc-issuer='https://token.actions.githubusercontent.com'

cosign verify "ghcr.io/tarek-fezai/socle-frontend:${VERSION}" \
  --certificate-identity-regexp='https://github.com/tarek-fezai/socle/.github/workflows/release.yml@refs/tags/v.*' \
  --certificate-oidc-issuer='https://token.actions.githubusercontent.com'

cosign verify "ghcr.io/tarek-fezai/socle-worker:${VERSION}" \
  --certificate-identity-regexp='https://github.com/tarek-fezai/socle/.github/workflows/release.yml@refs/tags/v.*' \
  --certificate-oidc-issuer='https://token.actions.githubusercontent.com'
```

Pin by digest after verify (`image@sha256:…`). SBOMs attach to GitHub Releases (see release workflow).

Published image names: `socle-backend`, `socle-frontend`, `socle-worker`.
