# Socle

Plateforme de documentation d'entreprise auto-hébergée.

## Structure

```
socle/
├── backend/            Java 21 + Spring Boot 3.5 (API, OIDC, JPA, Flyway, Temporal, OpenFGA, S3)
├── webhook-worker/     Go 1.22+ (outbox audit → Splunk / Datadog / Sentinel)
├── frontend/           React 18 + TypeScript + Vite
├── infra/              docker-compose, Keycloak realm, OpenFGA model, manifests K8s
└── docs/
```

## Prérequis

| Outil | Version cible | Notes |
|-------|---------------|-------|
| Java (Temurin) | 21 | `JAVA_HOME` pointant vers JDK 21 |
| Maven | 3.9+ | |
| Go | 1.22+ | |
| Node.js | 20 LTS | |
| pnpm | 9+ | `npm i -g pnpm` |
| Docker + Compose | récent | |
| kubectl | optionnel | cluster local k3d/kind |
| psql | optionnel | debug Postgres |

Copier l'environnement :

```bash
cp .env.example .env
```

## Schéma SQL (Flyway uniquement)

Le schéma canonique est versionné :

`backend/src/main/resources/db/migration/V1__init.sql`

Il est appliqué automatiquement au démarrage du backend (`spring.flyway`).  
`socle_schema.sql` à la racine est une **copie de référence** — ne pas l’exécuter à la main. Toute évolution = nouveau fichier `V2__….sql`, `V3__….sql`, etc.

Si une ancienne `V1__init_core_schema` a déjà été appliquée en local, reset le volume Postgres puis relancer le backend :

```bash
docker compose -f infra/docker-compose.yml --env-file .env down -v
docker compose -f infra/docker-compose.yml --env-file .env up -d
```

## Démarrage infra locale

```bash
docker compose -f infra/docker-compose.yml --env-file .env up -d
```

Cela démarre notamment **Postgres** et **Keycloak** (exemple de développement OIDC — realm `socle` importé depuis `infra/keycloak/realm-socle.json`). Tout autre IdP OIDC se configure via `socle.identity` / `GET /api/v1/public/auth-config` — voir `docs/identity-providers.md`.

Puis backend + frontend :

```bash
# Backend
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Frontend (autre terminal) — host 127.0.0.1 pour coller aux redirect URIs OIDC de dev
cd frontend
pnpm install
pnpm dev
```

Ouvrir **http://127.0.0.1:5173** (pas `localhost` : le client SPA n’autorise que `127.0.0.1` en exemple Keycloak).

### Vérifier OIDC (Keycloak = exemple de développement)

```bash
# Sans token → 401
curl -i http://127.0.0.1:8080/api/documents

# Avec token (password grant, client public SPA)
TOKEN=$(curl -s -X POST "http://localhost:8081/realms/socle/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=socle-frontend&username=contributeur&password=contributeur" \
  | jq -r .access_token)
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/documents
```

Config SPA runtime : `GET http://127.0.0.1:8080/api/v1/public/auth-config` (pas de build Vite par IdP).

Dans le navigateur : sans session, http://127.0.0.1:5173 redirige vers le login OIDC ; après connexion, le CRUD documents fonctionne authentifié.

Services exposés :

| Service | URL / port |
|---------|------------|
| Postgres | `localhost:5433` (bases : `socle_core`, `keycloak`, `temporal`, `temporal_visibility`, `openfga`) — port **5433** pour éviter un conflit avec un Postgres Windows sur 5432 |
| Keycloak | http://localhost:8081 (admin / admin) — realm `socle` |
| Temporal gRPC | `localhost:7233` |
| Temporal UI | http://localhost:8088 |
| OpenFGA HTTP | http://localhost:8082 |
| OpenFGA Playground | http://localhost:3001 |
| S3 mock (adobe/s3mock) | http://localhost:9000 — bucket `socle-docs` |
| Redis | `localhost:6379` |

> **Note :** l'image Docker Hub `minio/minio` peut être inaccessible selon le réseau ; le compose utilise `adobe/s3mock` (S3-compatible) pour le stockage objet local.

## Auth → autorisation → workflows

Ordre imposé (chaque couche dépend de la précédente) :

1. **OIDC (Keycloak = exemple de dev)** — JWT Bearer ; IdP configurable via `socle.identity` (voir `docs/identity-providers.md`). Avec les défauts, les rôles realm Keycloak (`contributeur`, `auditeur`, …) deviennent `ROLE_*` comme avant. User de test Keycloak : `contributeur@example.com` / `contributeur`
2. **OpenFGA** — modèle Access.dc.html (cascade space → folder → document, accès direct + `group#member`) ; Check avant lecture/écriture ; grant/revoke immédiat via `/api/v1/access` et UI `/access`
3. **Temporal** — `POST /api/v1/documents/{id}/approvals` démarre `DocumentApprovalWorkflow`
   (1–N étapes data-driven, SLA / escalade par étape). La définition applicable est
   résolue avant le start selon `scope_space_id` / `scope_doc_type` (précédence
   documentée dans `docs/workflow-sla-escalade.md`) ; fallback seed « Approbation
   simple ». Admin des définitions : UI `/admin/workflows` + API
   `/api/v1/approval-workflows`. UI Temporal : http://localhost:8088

Sans token sur les documents : `401`. Health reste public.

Bootstrap OpenFGA (store + modèle → met à jour `.env`) :

```bash
# PowerShell
./infra/scripts/bootstrap-openfga.ps1
# Bash
./infra/scripts/bootstrap-openfga.sh
```

Ensuite (admin Keycloak) : UI **Accès** ou `POST /api/v1/access/space/{spaceId}` pour accorder `editor` à un utilisateur avant de créer des documents.

Audit enchaînement auth→authz→workflow : `docs/audit-integration-auth-authz-workflow.md` — tests : `mvn test -Dtest=DocumentApprovalSubmitIntegrationTest`.

## Backend

```bash
cd backend
# Windows (si JDK 21 installé en local utilisateur)
# $env:JAVA_HOME = "$env:LOCALAPPDATA\jdk-21"
# $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/socle_core"
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Le profil `local` assouplit OIDC (Keycloak peut démarrer lentement) tout en gardant Flyway/JPA/Actuator. Sans profil, le resource server OIDC Keycloak est exigé.

Vérifications :

- Health : http://localhost:8080/actuator/health → `200`
- OpenAPI UI : http://localhost:8080/ApiDocs.dc.html
- Ping : http://localhost:8080/api/v1/ping

## Frontend

```bash
cd frontend
pnpm install
pnpm dev
```

Ouvrir http://localhost:5173

## Webhook worker

```bash
cd webhook-worker
go run ./cmd/worker
```

Health : http://localhost:8090/healthz — Metrics : http://localhost:8090/metrics

## Comptes de démo Keycloak (exemple de dev)

Keycloak local n’est **pas** un prérequis produit — c’est l’IdP d’exemple. Autres IdP : `docs/identity-providers.md`.

- Realm : `socle` (export versionné : `infra/keycloak/realm-socle.json`)
- Utilisateur de test : **contributeur@example.com** / **contributeur** (username `contributeur`, rôle realm `contributeur`)
- Client SPA : `socle-frontend` (public, PKCE, redirect `http://127.0.0.1:5173/*`)
- Client API : `socle-backend` (confidentiel, bearer-only)
- Console admin Keycloak : http://localhost:8081 — identifiants bootstrap **uniquement** dans `infra/docker-compose.yml` (`KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`)
- Config publique frontend : `GET /api/v1/public/auth-config`

## Vérifications rapides OpenFGA & Temporal

```bash
# OpenFGA
curl -s http://localhost:8082/healthz

# Temporal (via tctl dans le conteneur, ou temporal CLI)
docker exec socle-temporal temporal operator cluster health
```
