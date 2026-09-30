# Architecture Socle (vue initiale)

Socle est une plateforme de documentation d'entreprise auto-hébergée. Les briques techniques principales :

- **OIDC** — authentification via un IdP (Keycloak fourni en **exemple de développement** local ; tout IdP OIDC se configure via `socle.identity`)
- **Autorisation fine** — OpenFGA (relations organisation → espace → dossier → document)
- **Workflows métier** — Temporal
- **Stockage objet** — S3-compatible (MinIO / s3mock en local)
- **Diffusion audit** — worker Go (outbox Postgres → SIEM)

Keycloak et OpenFGA sont des **composants techniques** d'authn/authz de la plateforme, pas un produit IAM/IGA client.

Voir le `README.md` racine pour le démarrage local.
