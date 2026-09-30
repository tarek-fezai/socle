# Espaces & groupes — gouvernance

## `external_reference` (transclusion inter-workspace)

Colonne `spaces.external_reference` (migration V15) : **`open`** (défaut) | **`restricted`**.

| Couche | Rôle |
|--------|------|
| OpenFGA | Droit de lecture **par document** (accès direct inchangé) |
| `external_reference` | Politique **par espace**, supplémentaire : autorise ou non la *transclusion entrante* depuis d'autres espaces |

- Modification : **owners** de l'espace (`PUT /api/v1/spaces/{id}` avec `externalReference`).
- Intra-workspace : **aucun effet**.
- **Rupture rétroactive** : passer en `restricted` fait échouer immédiatement les
  transclusions inter **déjà en place** (bloc « non accessible », pas de fuite) —
  pas seulement les nouvelles. Spec assumée, pas adoucie.
- Graphe : même point de vérité (`ExternalReferencePolicy` via
  `TransclusionResolver.allowsInterWorkspaceEdge`) — l'arête inter disparaît.

### Notification (première référence externe)

Granularité retenue : **une fois par paire d'espaces** (source → cible), pas par
document — table `space_external_ref_notices`. Évite le bruit une fois la relation
B→A établie. Notifie les **owners** de l'espace cible (`type=external_reference_first`
via `NotificationService.create`, même table `notifications` que le reste).

Détail comportement résolution : `docs/transclusion.md`.

## Visibilité des pages

Chaque document a un champ SQL `visibility` ∈ `organisation` | `space` | `restricted`
(migration V17). Les espaces portent `default_visibility` (défaut **`organisation`**
à la création) — appliqué aux nouveaux documents, surchargeable à la création uniquement
par un **owner** de l’espace.

| Niveau | Qui peut lire | Tuples OpenFGA |
|--------|---------------|----------------|
| `organisation` | Tout utilisateur authentifié de l’instance | `parent` + `inherit_from` + `user:* viewer` |
| `space` | Membres de l’espace / dossier (héritage) | `parent` + `inherit_from` |
| `restricted` | Accès explicites (user/group) **+ owners** de l’espace | `parent` seulement (pas d’`inherit_from`) |

### Qui modifie

- **Owners de l’espace** (hérités via `owner from parent`) : `PUT /api/v1/documents/{id}/visibility`
  et grant/revoke ACL (`AccessController.requireCanManage`).
- Le **créateur** reçoit `editor` sur le document, **pas** `owner` — il ne gère ni
  visibilité ni ACL (403).

### Héritage `parent` vs `inherit_from`

Le modèle OpenFGA sépare gouvernance et lecture/écriture :

- **`parent`** : sert **uniquement** à `owner from parent`. Les owners d’espace/dossier
  gardent toujours la main, y compris en `restricted`.
- **`inherit_from`** : porte `editor from inherit_from` et `viewer from inherit_from`.
  Présent pour `organisation` et `space` ; **absent** pour `restricted`.

Migration des documents existants : `visibility = 'space'` (aucun élargissement silencieux
vers organisation). Migration FGA `visibility-v1` (table `authz_migrations`) :
convertit **uniquement** le tuple `owner` du **créateur** (`documents.created_by`)
en `editor` + `direct_access` ; les owners délégués via Access sont préservés.
Exécution unique ; démarrage désactivé par défaut
(`socle.openfga.visibility-migration-on-startup=false`). Relance :
`POST /api/v1/admin/authz/migrate-visibility` (`?force=true` pour rejouer l'idempotent).

Diagnostic colonne ↔ tuples : `GET /api/v1/admin/authz/visibility-drift`.

### Création document — ordre OpenFGA / Git

1. INSERT SQL (`created_by`, `visibility`)
2. Write OpenFGA atomique (`provisionDocumentAccess`)
3. Commit Git (`createContent`) — si échec → compensation delete groupé FGA puis rollback TX

Cas résiduel : compensation FGA échoue après échec Git → tuples orphelins (log
`ALERT CRITICAL`) ; la ligne SQL est annulée.

### Dossiers

`F_view = ListObjects(user, viewer, folder)` entre dans la présélection SQL
(`folder_id = ANY(F_view)`). Un grant uniquement sur dossier apparaît donc en
recherche / graphe / export ; le Check viewer final garantit l'exactitude.
`direct_access` reste un index ListObjects et n'accorde aucun droit.

## Responsible ⊂ owners

Table `space_owners` (migration V11) :

| Colonne | Rôle |
|---------|------|
| `user_id` | Owner de l'espace |
| `is_responsible` | Sous-ensemble des owners qui gère **qui entre/sort** des owners |

- À la création d'un espace : le créateur est inscrit `is_responsible = true` **et**
  reçoit le tuple OpenFGA `owner` sur `space:{id}`.
- **Responsible** : `POST/DELETE /api/v1/spaces/{id}/owners`, bascule responsible.
- **Owners** (FGA `owner` + ligne `space_owners`) : métadonnées espace (`PUT`),
  Accès pages (`AccessController` — inchangé : Check FGA owner).
- OpenFGA reste la source pour Check ; `space_owners` matérialise la gouvernance.

Espaces seedés / legacy sans row `space_owners` : le premier owner FGA qui appelle
la gouvernance est bootstrapé comme responsible.

## Groupes

- Tout utilisateur authentifié peut `POST /api/v1/groups`.
- Seul `groups.created_by` (ou Keycloak `administrateur-systeme`) gère membres /
  rename / delete.
- Ajout/retrait de membre : SQL `group_members` **et** tuple OpenFGA
  `user:{id} member group:{id}` (nécessaire pour `group#member` dans Access).
- `AccessController` valide l'existence du groupe avant grant/revoke
  (`subjectType=group`) — 400 si inexistant.

`/api/v1/global-roles` reste réservé aux rôles approbateurs de workflow — système
distinct.

## Documents

`POST /api/v1/documents` exige `spaceId` (plus de `DEFAULT_SPACE_ID` hardcodé).
L'espace seed `00000000-…-0001` reste un espace parmi d'autres.

## Approbation workflow × confidentialité OpenFGA (choix explicite)

`DocumentApprovalService.decide` exige **à la fois** :

1. le rôle approbateur de l'étape courante (`user_global_roles` / `approver_role_id`) ;
2. la relation OpenFGA **`editor`** sur le document (`requireDocumentRelation`).

Conséquence : un utilisateur avec uniquement le rôle de gouvernance, **sans** droit
de lecture/écriture page, **ne peut pas** approuver/rejeter. Ce n'est pas un angle
mort : l'approbation n'est pas « aveugle ». Le scope de rôle restreint *qui* peut
décider parmi ceux qui ont déjà accès page ; il ne remplace pas OpenFGA.

`GET /approvals/mine` liste les demandes selon le rôle seul (sans Check FGA) —
l'utilisateur peut voir une demande qu'il ne pourra pas décider si l'accès page
lui manque. Comportement assumé V1 (filtre UI possible plus tard).

## Audit des actions de gouvernance

Actions tracées dans `GET /api/v1/audit` :

| Domaine | Actions |
|---------|---------|
| Espaces | `space.created`, `space.updated`, `space.owner_added`, `space.owner_removed`, `space.responsible_changed` (+ trash) |
| Groupes | `group.created`, `group.updated`, `group.deleted`, `group.member_added`, `group.member_removed` |
| Versions | `document.version_restored` (restore) |

## Endpoints récents — qui a accès

| API | Protection |
|-----|------------|
| `/api/v1/spaces` create | tout utilisateur authentifié ; update/owners = owner/responsible (403 sinon) |
| `/api/v1/groups` create | tout authentifié ; membres = créateur ou `administrateur-systeme` (403 sinon) |
| `/api/v1/search` | tout authentifié ; filtre SQL S_view/S_owner/D_direct + organisation |
| `socle.storage.provider` | config démarrage uniquement (pas d'endpoint runtime) |
