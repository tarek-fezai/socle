# Recherche full-text

## Choix : Postgres FTS (pas de moteur externe)

Pour cette itération, l'index est **Postgres native** (`tsvector` + index GIN,
config `french`) sur titre + corps JSONB + noms de tags. Pas d'Elasticsearch /
Meilisearch : le volume actuel tient dans Postgres, et cela évite un nouveau
composant ops. Si les volumes futurs l'imposent, un moteur externe sera un
**chantier séparé** (ré-indexation, sync, authz) — pas anticipé ici.

Migration : `V12__document_search_fts.sql`.

- Colonne `documents.search_vector`
- Fonction `documents_rebuild_search_vector(id)`
- Triggers `AFTER INSERT/UPDATE OF title, body` et sur `document_tags`
  → l'index suit create/update **sans modifier** le moteur de versioning
  applicatif.

## Endpoint

`GET /api/v1/search?q=...&spaceId=&tag=&docType=&limit=`

Réponse : `{ query, results[{ id, title, excerpt, spaceId, spaceName, docType,
status, updatedAt, rank }], total, totalIsEstimate, warning? }`.

- **`totalIsEstimate: true`** — `total` compte la **présélection SQL** (avant
  Check OpenFGA). Il peut surestimer si des candidats sont écartés par le Check.
- **`warning`** — présent si la page n'a pas pu être complétée après 3 itérations
  de refill authz (`search_authz_refill_truncated`).

## Filtrage par permission (critique)

Deux étapes — la confidentialité repose sur OpenFGA, pas sur la colonne SQL.

### 1. Présélection SQL (peut sur-inclure, jamais sous-inclure)

```sql
visibility = 'organisation'
OR (visibility = 'space' AND space_id = ANY(S_view))
OR space_id = ANY(S_owner)
OR id = ANY(D_direct)
OR folder_id = ANY(F_view)
```

| Ensemble | Source OpenFGA | Rôle |
|----------|----------------|------|
| `S_view` | `ListObjects(user, viewer, space)` | espaces lisibles |
| `S_owner` | `ListObjects(user, owner, space)` | owners (couvre `restricted`) |
| `D_direct` | `ListObjects(user, direct_access, document)` | **index** grants explicites (n'accorde aucun droit) |
| `F_view` | `ListObjects(user, viewer, folder)` | dossiers lisibles |

### 2. Vérification finale OpenFGA

Sur la page de candidats (après `LIMIT`/`OFFSET`, **avant** `ts_headline`) :
`BatchCheck viewer` (ou Checks parallèles en fallback). Les refusés sont écartés ;
si la page est incomplète, refill jusqu'à **3 itérations**.

`ts_headline` n'est calculé que sur les ids autorisés — aucun extrait pour un
document rejeté (ex. `visibility=organisation` sans tuple `user:*`, ou
`direct_access` orphelin).

`listViewableDocumentIds(userId, DocumentScope)` (graphe → space, export →
folder/tag, content-health → space) borne la présélection SQL au périmètre
**avant** le Check. {@code DocumentScope.global()} est réservé à la liste
paginée {@code /documents}.

### Plafond ListObjects

Le plafond (`socle.openfga.list-objects-max-results`, défaut 1000) s'applique aux
ListObjects **espaces**, **folders** et **direct_access**. Warning si = plafond.
On ne liste plus les documents via `ListObjects(viewer, document)`.

### Divergence colonne ↔ tuples

- `GET /api/v1/admin/authz/visibility-drift` — colonne ↔ `user:*` / `inherit_from`
- `GET /api/v1/admin/authz/direct-access-drift` — `direct_access` sans
  owner/editor/viewer direct ; réparation :
  `POST …/direct-access-drift/repair`
- `POST /api/v1/admin/authz/backfill-document-links` — index `document_links`
  (`document-links-v1`) ; `GET …/document-links-drift` — index ↔ contenu canonique

## Filtres optionnels

`spaceId`, `tag` (nom, case-insensitive), `docType` — appliqués **avec** la
présélection (ET SQL), jamais à la place du Check. Un document hors OpenFGA ne
peut pas apparaître même s'il matche tous les filtres.

## Restore / versioning

`DocumentService.restore` fait un `UPDATE` de `documents.body` → le trigger
`documents_search_vector_aiud` reconstruit `search_vector`. Couvert par le test
`bodyUpdate_likeRestore_refreshesSearchVector` (pas un hook applicatif séparé).
