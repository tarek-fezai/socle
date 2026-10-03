# DEV UNIQUEMENT — ne jamais importer en production

Realm Keycloak d'exemple pour le développement local (`docker compose`, profil
`demo-idp`). Ne pas importer ni déployer en staging / production.

Fichier : `realm-socle.dev.json` (realm `socle`, comptes de démo).

> Ne pas ajouter de clés hors schéma Keycloak (ex. `_comment`) : `--import-realm`
> refuse les propriétés inconnues et empêche le démarrage.
