# Licences des composants tiers

Inventaire à jour après le basculement source-available (tag `last-agpl` = dernier état AGPL).
Ce document liste les dépendances **empaquetées / liées** dans les artefacts Socle, et distingue les
**services d'infrastructure** déployés séparément.

> Aucune dépendance n'a été remplacée dans cette bascule. Signaler toute dépendance
> GPL / AGPL / SSPL / BUSL réellement intégrée au produit avant d'envisager un remplacement.

## Backend (Maven — `backend/pom.xml`)

Principales dépendances d'exécution (licences courantes, non copyleft) :

| Composant | Licence typique | Notes |
|-----------|-----------------|-------|
| Spring Boot / Spring Framework | Apache-2.0 | |
| PostgreSQL JDBC | BSD-2-Clause | |
| Flyway | Apache-2.0 | |
| Hibernate / Jakarta | LGPL-2.1 / EPL-2.0 (API) | LGPL côté Hibernate ORM — **à surveiller** (liaison dynamique) |
| Temporal Java SDK | MIT | Client uniquement ; serveur Temporal = service séparé |
| OpenFGA Java SDK | Apache-2.0 | Client ; serveur OpenFGA = service séparé |
| Eclipse JGit | EDL-1.0 (BSD-like) | |
| Nimbus JOSE JWT | Apache-2.0 | |
| springdoc-openapi | Apache-2.0 | |
| MapStruct | Apache-2.0 | |
| java-diff-utils | Apache-2.0 | |

Commande d'audit local : `mvn -f backend/pom.xml org.codehaus.mojo:license-maven-plugin:aggregate-third-party-report`
(rapport HTML dans `backend/target`).

**À surveiller :** Hibernate (LGPL-2.1). Pas de dépendance GPL/AGPL/SSPL/BUSL identifiée comme liée en
statique dans le POM d'application au moment de l'inventaire.

## Frontend (pnpm — `frontend/package.json`)

Stack UI (React, Vite, TipTap, etc.) : licences majoritairement MIT / Apache-2.0 / ISC.
Audit : `pnpm -C frontend licenses list` (ou `license-checker` si ajouté).

Aucune dépendance GPL/AGPL/SSPL/BUSL connue dans les dépendances de production au moment de
l'inventaire. Vérifier après chaque ajout de package.

## Webhook worker (Go — `webhook-worker/go.mod`)

| Module | Licence typique |
|--------|-----------------|
| chi | MIT |
| lib/pq | MIT |
| prometheus/client_golang | Apache-2.0 |

Pas de dépendance GPL/AGPL/SSPL/BUSL identifiée.

## Images de base Docker

| Image | Usage | Licence image / OS |
|-------|--------|--------------------|
| `eclipse-temurin` (Alpine) | build + JRE backend | GPL-2.0-with-classpath-exception (OpenJDK) + Alpine |
| `alpine` | runtime backend | MIT |
| `node` | build frontend | MIT (Node) |
| `nginxinc/nginx-unprivileged` | runtime frontend | BSD-2-Clause (nginx) |
| `golang` | build worker | BSD-3-Clause |
| `gcr.io/distroless/static-debian12` | runtime worker | Apache-2.0 / Debian notices |

Les images Temurin / OpenJDK embarquent du code sous GPL avec classpath exception — **à surveiller**
pour la redistribution des images GHCR (pas une dépendance liée dans le JAR Socle lui-même).

## Services séparés (non empaquetés dans le JAR / le bundle frontend)

Ces composants sont déployés à côté de Socle (Compose / Helm) ; leurs licences s'appliquent à leur
propre binaire / image, pas au code Socle :

| Service | Licence | Rôle |
|---------|---------|------|
| PostgreSQL | PostgreSQL License | Base de données |
| Keycloak | Apache-2.0 | IdP OIDC |
| OpenFGA | Apache-2.0 | Autorisation |
| Temporal Server | MIT | Workflows d'approbation |
| Garage (S3) | AGPL-3.0 | Stockage objets **optionnel** — **service séparé**, pas lié au code Socle |
| Caddy | Apache-2.0 | Reverse proxy |

**Garage (AGPL)** : uniquement s'il est déployé comme service de blob. Ce n'est pas une bibliothèque
intégrée au backend ; le client S3 (AWS SDK / compatible) dialogue via API. Aucun remplacement
effectué — à confirmer selon le mode de déploiement blob (local vs S3/Garage).

## Actions manuelles

- Visibilité des images **GHCR** (publiques aujourd'hui) : à régler dans GitHub Packages (action manuelle
  du titulaire).
- Conserver le tag git `last-agpl` pour l'historique AGPL.
