# CI — Testcontainers (backend)

Les tests d'intégration backend qui ont besoin de Postgres utilisent déjà
**Testcontainers** (`@Testcontainers`, image `postgres:16`,
`disabledWithoutDocker = true`). OpenFGA est mocké dans les tests
d'autorisation — aucun conteneur OpenFGA n'est requis.

Les `@WebMvcTest` de sécurité utilisent `@SecurityWebMvcTest` (JwtDecoder
factice, exclusion de l'auto-config OAuth2 client) : **Keycloak n'est pas
requis** en CI. Auparavant ces tests ne passaient en local que si Keycloak
écoutait déjà sur `:8081` (découverte OIDC au démarrage du contexte).

Le job `backend` de `.github/workflows/ci.yml` s'appuie donc sur Docker
(disponible sur les runners GitHub Actions) + Testcontainers, **sans**
`services:` Compose pour Postgres/OpenFGA/Keycloak.
