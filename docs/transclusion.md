# Transclusion — pages composites

## Objectif

Une page composite **transclut** d'autres documents par leur id global
(nœud TipTap `type: "transclusion"`, `attrs.documentId`), pas un simple lien.
La résolution du contenu cible est **dynamique à chaque lecture**.

## API

| Endpoint | Rôle |
|----------|------|
| `GET /api/v1/documents/{id}` | Body **brut** (refs transclusion non résolues) — édition |
| `GET /api/v1/documents/{id}/resolved` | Body **résolu** — lecture composite |

Réponse résolue : `Cache-Control: no-store` + `Pragma: no-cache`.

## Frontend

Route `/docs/:id/view` — lecture résolue (voir aussi graphe : `docs/transclusion-graph.md`).

## Permissions (OpenFGA)

Pour **chaque** bloc transclus, le serveur appelle le même
`AuthorizationService.hasRelation(user, "document", targetId, "viewer")`
que pour une lecture document normale. **Pas** de second système ACL.

| Cas | Réponse dans le bloc |
|-----|----------------------|
| `viewer` OK | `accessible: true`, `title`, `documentId`, `content` (body cible résolu) |
| Refus / manquant | `accessible: false`, `deniedReason` — **ni titre ni contenu ni id cible** |

Indicateur UI : « Contenu non accessible ».

## Inter-workspace — `external_reference`

Couche **supplémentaire** à OpenFGA (voir `docs/spaces-governance.md`) :

1. Check OpenFGA `viewer` sur la cible ;
2. Si espaces source ≠ cible : l'espace **cible** doit être `external_reference = open`.
   Sinon → même indicateur « non accessible » (pas de fuite), **y compris** pour une
   transclusion qui fonctionnait avant un passage en `restricted` (rupture rétroactive).
3. Intra-espace : le réglage est ignoré.

Notification owners cible : première fois qu'une paire d'espaces (B→A) résout une
transclusion inter réussie — pas à chaque document (`space_external_ref_notices`).

## Anti-cycle et profondeur

| Règle | Valeur |
|-------|--------|
| Profondeur max | **5** (`TransclusionResolver.MAX_DEPTH`) — la page racine = profondeur 0 |
| Cycles | Détectés via pile d'IDs sur le chemin courant (`A→B→A` → `deniedReason: cycle`) |

Dépassement → `deniedReason: depth_exceeded`, sans charger la cible.

Ces bornes sont volontairement strictes pour éviter boucle / DoS ; la vue graphe
et `external_reference` (lots suivants) pourront s'appuyer sur les mêmes arêtes
`documentId` sans changer ce contrat.

## Storage dual

La cible est lue uniquement via `DocumentStore.readCurrentContent` — **identique**
quel que soit le provider actif de l'instance (`relational` ou `git`). Le provider
de la page composite n'entre pas en jeu pour le contenu transclus.

## Pas de cache de contenu résolu

- Pas de cache applicatif de contenu résolu.
- HTTP : `no-store` sur `/resolved`.
- Search FTS indexe le body **stocké** (refs brutes), jamais le contenu résolu
  d'une autre page — pas de fuite Search via transclusion.

Preuve test : révocation OpenFGA après une lecture réussie → lecture suivante
sans le contenu (`TransclusionResolverTest.revokeAfterSuccessfulRead_…`).

## Export

Implémenté — voir **`docs/export.md`**.

Réutilise `TransclusionResolver` + `listViewableDocumentIds` (pas de dump
d'un body résolu mis en cache). Bloc interdit → « Contenu non accessible ».

| Endpoint | Maquette |
|----------|----------|
| `GET /api/v1/documents/{id}/export` | Export.dc.html |
| `GET /api/v1/folders/{id}/export` | ExportFolder.dc.html |
| `GET /api/v1/tags/{id}/export` | ExportTag.dc.html |

## Hors scope (lots suivants)

- Soft-lock d'édition, rôle auditeur transverse, certifications
