# CI — Testcontainers (backend)

Les tests d'intégration backend qui ont besoin de Postgres utilisent déjà
**Testcontainers** (`@Testcontainers`, image `postgres:16`,
`disabledWithoutDocker = true`).

Les tests d'autorisation contre un **vrai** OpenFGA
(`AuthorizationServiceOpenFgaTest`) démarrent `openfga/openfga` (même tag que
`docker-compose.yml`, datastore mémoire). Sans Docker, le test est désactivé
(`disabledWithoutDocker` / `@EnabledIf`).

Le modèle lui-même est aussi vérifié **sans Java** par le job CI `authz-model`
(`fga model test` sur `infra/openfga/model.fga.yaml`). Voir `docs/openfga-versions.md`.

Les `@WebMvcTest` de sécurité utilisent `@SecurityWebMvcTest` (JwtDecoder
factice, exclusion de l'auto-config OAuth2 client) : **Keycloak n'est pas
requis** en CI. Auparavant ces tests ne passaient en local que si Keycloak
écoutait déjà sur `:8081` (découverte OIDC au démarrage du contexte).

Le job `backend` de `.github/workflows/ci.yml` s'appuie donc sur Docker
(disponible sur les runners GitHub Actions) + Testcontainers, **sans**
`services:` Compose pour Postgres/OpenFGA/Keycloak.
