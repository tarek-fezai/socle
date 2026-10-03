# Rapport divergences contrat API ↔ frontend

Branche `chore/contract-guard`, base `main` @ `8a8c5f9464e0f97cf457760b7a2f6eaca0994baa`.

Inventaire des écarts constatés **avant** / **autour** de l’alignement sur OpenAPI
(`openapi/openapi.json` + `frontend/src/lib/api-types.ts`). Les types listés ci-dessous
sont désormais dérivés des schémas générés ; les fixtures e2e-visual sont validées (AJV).

## Accueil (`HomeDashboardPage` / GET `/api/v1/home`)

| Divergence | Détail |
|------------|--------|
| Contrat FE historique vs `HomeDtos` | Ancien FE attendait `status`/`modifiedAt`, `pendingYourApproval`, activité `actorName`/`isSelf` — cause de la page blanche post-login (corrigé en #42). |
| `pendingApprovals[].slaRemainingLabel` | FE/fixtures autorisaient `null` ; `HomeService` renvoie `"—"` si pas de SLA. |
| `teamActivity[].documentId` / `documentTitle` | FE nullable ; service JOIN documents → toujours peuplés ; fixture Yanis avait des `null`. |

## Documents / lecture (`DocumentReadPage`, rail, page)

| Divergence | Détail |
|------------|--------|
| `DocumentDetail` sans `templateId` / `templateVersion` | Présents sur `DocumentResponse` backend, absents du type FE manuel. |
| `position`, `stale`, `stalenessThresholdDays`, `visibility`, `tags`, `permissions` | Toujours sérialisés côté BE ; FE les traitait comme optionnels. |
| `TagRef.governed` | Booléen toujours présent sur le wire ; optionnel côté FE. |
| Fixture `pageDocument` | Manquait `visibility` / `position` / templates / `reliabilityComputedAt` ; ids d’étiquettes non-UUID (`tag-iam`). |

## Édition (`DocumentEditPage` / drafts / custom-fields / writing-assistant)

| Divergence | Détail |
|------------|--------|
| `moveDocument` typé `DocumentDetail` | L’API move renvoie un payload partiel `{ id, folderId, position, spaceId }`, pas un `DocumentResponse`. |
| `updateDocument` / `createDocument` | BE accepte `changeSummary` ; FE ne l’envoie pas. |
| Draft `title` | Type FE non-null ; Java `String` nullable. |
| Writing-assistant `brokenLinks` | **Corrigé** : `label` = texte d'ancre source TipTap ; `reason` = `deleted` \| `inaccessible` ; `accessible` toujours `false` ; jamais le titre cible. |
| Extraits paragraphes longs | Client 80 caractères vs serveur 100. |

## Historique / comparaison (`DocumentHistoryPage`, `DocumentComparePage`)

| Divergence | Détail |
|------------|--------|
| `VersionSummary.current` | Toujours posé par le BE ; omis dans fixtures history. |
| `linesAdded` / `linesRemoved` / `authorDisplayName` | BE toujours peuplés ; FE optionnels (« absent = inconnu »). |

## Attestations / feedback

| Divergence | Détail |
|------------|--------|
| Attestations (`ActiveAttestation`) | Pas d’écart de champs matériels sur le DTO actif. |
| Feedback (`FeedbackView`) | Aligné (`myVote`, `totals` NON_NULL pour éditeurs). |

## Garde-fous ajoutés

1. Export OpenAPI (`OpenApiExportIT`) → `openapi/openapi.json`
2. `pnpm gen:api` → `frontend/src/lib/api-types.ts`
3. Job CI `api-contract` : régénère et échoue si diff
4. `e2e-visual/fixture-contract.test.ts` : AJV sur fixtures dashboard / page / edit / history

## DCO (commits #42)

| SHA | Message | Signed-off-by |
|-----|---------|---------------|
| `c96e8a3ed705a8ab438c24da703b5a2c647c9fed` | fix(frontend): aligner l'accueil… | **absent** (remédiation DCO sur cette branche) |
| `3f2c86595568b22a59ccdc9e10eadde4ef8d9ee5` | fix(e2e-visual): aligner HOME_SEED… | **absent** (remédiation DCO sur cette branche) |

**Required status checks on `main`:** `backend`, `frontend`, `worker`, `authz-model`.
**DCO n’est pas** un required status check de `main` (ne pas modifier la protection de branche ici).

## Keycloak

`infra/keycloak/realm-socle.dev.json` : import Compose uniquement sous profil `demo-idp`
(+ montage e2e). Bannière `_comment` DEV UNIQUEMENT en tête du JSON.
