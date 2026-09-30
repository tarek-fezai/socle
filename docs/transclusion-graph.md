# Graphe de dépendances de transclusion

## Périmètre retenu : **centré sur un espace**

Endpoint : `GET /api/v1/spaces/{spaceId}/graph`

| Choix | Justification |
|-------|----------------|
| Centré sur un espace | Checks OpenFGA bornés à `DocumentScope.space` + `DocumentScope.ids` des voisins directs indexés. |
| Index `document_links` | Arêtes entrantes inter-espace sans scan instance (V19). |
| Viewer space requis | Pas d'énumération d'espaces invisibles via le graphe. |

## Index `document_links`

Table peuplée à chaque create / update / restore (JSON TipTap écrit, pas le blob).
Backfill admin : `POST /api/v1/admin/authz/backfill-document-links` (`document-links-v1`
dans `authz_migrations`). Drift : `GET …/document-links-drift`.

Soft-delete : lignes conservées ; jointure sur `documents.deleted_at IS NULL` à la lecture.

## Construction des arêtes

1. **Sortantes** : `document_links` où `source_space_id = :space`
2. **Entrantes** : `target` dans l'espace et `source_space_id <> :space`
3. Voisins externes vérifiés via `DocumentScope.ids(...)`
4. Arête affichée seulement si viewer sur **les deux** extrémités +
   `ExternalReferencePolicy.allowsInterWorkspaceEdge`

## Filtrage OpenFGA (non-fuite)

Un document non lisible n'apparaît ni comme nœud ni dans une arête (id / titre exclus).

## Tag `intra` / `inter`

| Tag | Règle |
|-----|--------|
| `intra` | même `spaceId` |
| `inter` | espaces différents (sous réserve `external_reference`) |

## Notification première référence

À l'écriture d'un lien inter-espace (`DocumentLinkService`), une notif par paire
d'espaces (`ExternalReferenceNotifier`) — plus à la lecture résolue.
