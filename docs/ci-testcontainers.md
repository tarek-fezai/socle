# CI — Testcontainers (backend)

Les tests d'intégration backend qui ont besoin de Postgres utilisent déjà
**Testcontainers** (`@Testcontainers`, image `postgres:16`,
`disabledWithoutDocker = true`). OpenFGA est mocké dans les tests
d'autorisation — aucun conteneur OpenFGA n'est requis.

Le job `backend` de `.github/workflows/ci.yml` s'appuie donc sur Docker
(disponible sur les runners GitHub Actions) + Testcontainers, **sans**
`services:` Compose pour Postgres/OpenFGA.
