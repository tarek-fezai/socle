# Soft-delete & Corbeille (trash)

## Pourquoi l’ancienne conception était risquée

Avant ce correctif, une suppression « via corbeille » aurait pu s’appuyer sur un
**DELETE SQL réel** + une seule ligne `trash_items` avec `resource_snapshot` du
**parent** (espace ou dossier).

Or le schéma définit déjà :

- `folders.space_id → spaces ON DELETE CASCADE`
- `folders.parent_folder_id → folders ON DELETE CASCADE`
- `documents.space_id → spaces ON DELETE CASCADE`

Donc supprimer un espace / dossier en SQL **détruit immédiatement** tous les
enfants (dossiers, documents, versions, commentaires…). Le snapshot du parent
ne permet **pas** de les reconstruire : perte irréversible déguisée en
« restaurable 30 jours ».

## Nouveau mécanisme

| Couche | Rôle |
|--------|------|
| `documents` / `folders` / `spaces`.`deleted_at` / `deleted_by` | **Source de vérité** (tombstone) |
| `trash_items` | **Index Corbeille** (affichage : title/snapshot, `purge_at`) — **une ligne par ressource** affectée |
| `ON DELETE CASCADE` PostgreSQL | **Uniquement** lors de la purge réelle (`purge_at` dépassé ou purge manuelle) |

Rétention : constante `TrashService.RETENTION` = **30 jours**
(`purge_at = deleted_at + 30d`).

Migration : Flyway `V8__soft_delete.sql`.

## Cascade applicative (soft-delete)

Dans **une même transaction** :

1. Soft-delete **dossier** → CTE récursive des sous-dossiers + tous les documents
   de chaque nœud → chacun reçoit `deleted_at` + une ligne `trash_items`.
2. Soft-delete **espace** → tous les dossiers et documents de l’espace, puis l’espace.
3. Soft-delete **document** → document seul.

OpenFGA `editor` (ou `owner` pour restore/list) avant toute opération.

## Restauration

`POST /api/v1/trash/{trashItemId}/restore` :

- Clear `deleted_at` / `deleted_by` sur la ressource **et** ses descendants encore
  soft-delete (symétrique à la suppression).
- Supprime les lignes `trash_items` correspondantes.

**Parent encore en corbeille** (choix volontairement simple) :

> Restaurer un **document** (ou un **dossier**) dont le dossier parent est encore
> soft-delete → **409** avec message
> « Restaurez d'abord le dossier parent (encore en corbeille) ».
> Pas de remontée automatique du parent.

## Purge réelle

- Job quotidien `TrashPurgeScheduler` (04:00) : `purge_at <= now()` → `DELETE` SQL
  (documents → folders → spaces) ; les cascades PG s’appliquent **ici**.
- Purge anticipée : `DELETE /api/v1/trash/{id}`.

## Audit

| Action | Quand |
|--------|--------|
| `document.trashed` / `folder.trashed` / `space.trashed` | Soft-delete |
| `document.restored_from_trash` / `folder.restored_from_trash` / `space.restored_from_trash` | Restore |
| `document.purged` / `folder.purged` / `space.purged` | Purge manuelle ou planifiée |

## API (backend uniquement — pas d’UI dans cette tâche)

| Méthode | Chemin |
|---------|--------|
| `GET` | `/api/v1/trash?resourceType=&offset=&limit=` — liste paginée (`deleted_at DESC`), filtrée OpenFGA **viewer** |
| `GET` | `/api/v1/trash/{id}` (prévisualisation soft-delete, droit **editor**) |
| `POST` | `/api/v1/trash/{id}/restore` — Check **editor** sur la racine **et chaque descendant** avant écriture |
| `DELETE` | `/api/v1/trash/{id}` (purge anticipée) |
| `DELETE` | `/api/v1/documents/{id}` / `/api/v1/trash/folders/{id}` / `/api/v1/trash/spaces/{id}` |

Soft-delete cascade : Check **editor** sur **chaque** dossier/document affecté **avant** tout `UPDATE deleted_at` (sinon 403, aucune cascade partielle).

Réponse liste : `{ items: [{ id, resourceType, resourceId, title, deletedBy, deletedByName, deletedAt, purgeAt, snapshot }], offset, limit, total }`.

## Lectures filtrées (`deleted_at IS NULL`)

| Endpoint / chemin | Filtre |
|-------------------|--------|
| `GET /api/v1/documents` | `findAllActiveByIdIn` |
| `GET/PUT /api/v1/documents/{id}` (+ versions, diff, restore version) | `findActiveById` |
| `POST …/approvals` | `findActiveById` |
| `GET …/approvals/current`, `GET /api/v1/approvals/mine` | `d.deleted_at IS NULL` |
| `ApprovalActivitiesImpl.recordSubmission` | SELECT/UPDATE filtrés |
| `ReliabilityScoreService` (load + job stale) | `deleted_at IS NULL` |

Prévisualisation Corbeille uniquement via `GET /api/v1/trash/{id}`.

## Hors scope

- UI React Corbeille (`Trash.dc.html`) — tâche frontend séparée.
- Ne pas toucher au versioning / reliability_score métier : le soft-delete masque
  la ressource, l’historique reste intact jusqu’à purge réelle.
