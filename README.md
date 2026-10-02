# Socle

Plateforme de documentation d'entreprise auto-hébergée.

## Structure

```
socle/
├── backend/            Java 21 + Spring Boot 3.5 (API, OIDC, JPA, Flyway, Temporal, OpenFGA)
├── webhook-worker/     Go 1.22+ (outbox audit → Splunk / Datadog / Sentinel)
├── frontend/           React 18 + TypeScript + Vite
├── infra/              docker-compose dev, Keycloak realm, OpenFGA model
├── deploy/             production Compose, Helm, Caddy
├── e2e/                Playwright smoke tests (demo stack)
└── docs/
```

## Quick links

| Topic | Document |
|-------|----------|
| **Local development** | [`docs/development.md`](docs/development.md) |
| **Install (Compose / Helm)** | [`docs/operations/install.md`](docs/operations/install.md) |
| **Configuration** | [`docs/operations/configuration.md`](docs/operations/configuration.md) |
| **Backup & restore** | [`docs/operations/backup-restore.md`](docs/operations/backup-restore.md) |
| **Upgrade** | [`docs/operations/upgrade.md`](docs/operations/upgrade.md) |
| **Security** | [`docs/operations/security.md`](docs/operations/security.md) |
| **Identity providers** | [`docs/identity-providers.md`](docs/identity-providers.md) |
| **Deploy artifacts** | [`deploy/README.md`](deploy/README.md) |

## Prérequis (résumé)

Java 21, Maven 3.9+, Go 1.22+, Node 20, pnpm 9+, Docker Compose. Copier `.env.example` → `.env` pour le dev local.

Schéma SQL : Flyway dans `backend/src/main/resources/db/migration/` uniquement.

## Licence

Socle est distribué sous **GNU Affero General Public License v3.0 or later**
([AGPL-3.0-or-later](https://www.gnu.org/licenses/agpl-3.0.html)).
Voir [`LICENSE`](LICENSE). Contributions : [`CONTRIBUTING.md`](CONTRIBUTING.md).
Vulnérabilités : [`SECURITY.md`](SECURITY.md).
