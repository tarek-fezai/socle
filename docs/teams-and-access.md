# Annuaire, accès nominatifs et rôles d’équipe

Référence pour l’annuaire, l’écran Accès et l’admin « Membres & équipes »
(`Team.dc.html`). Les tests d’intégration, du modèle OpenFGA et de sécurité
s’alignent sur ce document — pas l’inverse.

Les groupes SQL (`groups` / `group_members`) sont le stockage des **équipes**.
Les rôles d’équipe sont distincts des rôles plateforme
(`ADMINISTRATEUR_SYSTEME`, `CONTRIBUTEUR`, `AUDITEUR`, `INTEGRATEUR`) et des
rôles approbateurs de workflow (`/api/v1/global-roles`).

## 1. Matrice rôles d’équipe → OpenFGA

Quatre rôles, stockés en SQL (`group_members.role`) et en tuples OpenFGA sur
l’objet `group` :

| Rôle d’équipe (API / SQL) | Relation directe OpenFGA | Libellé UI |
|---|---|---|
| `proprietaire` | `group:{id}#proprietaire@user:{id}` | Propriétaire |
| `approbateur` | `group:{id}#approbateur@user:{id}` | Approbateur / Approbatrice |
| `contributeur` | `group:{id}#contributeur@user:{id}` | Contributeur |
| `lecteur` | `group:{id}#lecteur@user:{id}` | Lecteur |

Un utilisateur a **au plus un** rôle dans une équipe donnée. Défaut à l’ajout
et pour les membres existants à la migration : **`contributeur`**.

### 1.1 Relations calculées sur `group`

```
type group
  relations
    define proprietaire: [user]
    define approbateur: [user]
    define contributeur: [user]
    define lecteur: [user]
    define member: proprietaire or approbateur or contributeur or lecteur
    define as_owner: proprietaire
    define as_editor: proprietaire or approbateur or contributeur
    define as_viewer: proprietaire or approbateur or contributeur or lecteur
    define as_approver: proprietaire or approbateur
```

`member` ne sert plus de sujet de grant sur un espace / dossier / document.
Il reste pour « est membre de l’équipe » (liste, Check `member`).

### 1.2 Droits sur l’équipe elle-même

| Action | `proprietaire` | `approbateur` | `contributeur` | `lecteur` | Admin système |
|---|---|---|---|---|---|
| Voir l’équipe et ses membres | oui | oui | oui | oui | oui |
| Ajouter / retirer un membre | oui | **non** | **non** | **non** | oui |
| Changer le rôle d’un membre | oui | **non** | **non** | **non** | oui |
| Renommer / supprimer l’équipe | oui | **non** | **non** | **non** | oui |

Le **dernier `proprietaire`** d’une équipe ne peut être ni retiré ni rétrogradé
→ **409** (`last_team_owner`). Un admin système n’est pas exempté de cette
règle : il doit d’abord nommer un autre propriétaire.

Créer une équipe (`POST /api/v1/groups`) : tout utilisateur authentifié
(inchangé). Le créateur est inscrit **`proprietaire`**.

### 1.3 Héritage quand l’équipe est accordée sur un objet

L’ACL d’un espace / dossier / document reste **`owner` | `editor` | `viewer`**.
Quand on accorde une équipe `G` avec la relation `R` sur l’objet `O`, le
serveur écrit les tuples suivants (et plus aucun `O#R@group:G#member`) :

| Grant demandé `R` | Tuples écrits |
|---|---|
| `owner` | `O#owner@G#as_owner` + `O#editor@G#as_editor` + `O#viewer@G#as_viewer` |
| `editor` | `O#editor@G#as_editor` + `O#viewer@G#as_viewer` |
| `viewer` | `O#viewer@G#as_viewer` |

Sur un **document**, `direct_access` est aussi écrit pour le même sujet
`G#as_viewer` (index ListObjects, n’accorde aucun droit — inchangé).

Droits **effectifs** d’un membre sur `O` (ni plus, ni moins) :

| Rôle d’équipe | Grant `owner` | Grant `editor` | Grant `viewer` |
|---|---|---|---|
| `proprietaire` | owner (+ editor + viewer) | editor (+ viewer) | viewer |
| `approbateur` | editor (+ viewer) — **pas** owner | editor (+ viewer) | viewer |
| `contributeur` | editor (+ viewer) — **pas** owner | editor (+ viewer) | viewer |
| `lecteur` | viewer **uniquement** | viewer **uniquement** | viewer |

`as_approver` n’accorde **aucun** droit de page. Il sert uniquement à
`Check(user, as_approver, group)` : un `proprietaire` ou `approbateur` est
éligible comme approbateur de workflow **parmi ceux qui ont déjà `editor`**
sur le document (`docs/spaces-governance.md`). Un `lecteur` ne peut pas
décider, même s’il porte un rôle de workflow.

Tests du modèle (`infra/openfga/model.fga.yaml`) : un cas **positif et un
négatif** par rôle × grant (`owner` / `editor` / `viewer`), y compris
« `lecteur` n’est pas `editor` » et « `approbateur` n’est pas `owner` ».

### 1.4 Migration (V48 + réécriture FGA)

- Colonne `group_members.role` `TEXT NOT NULL DEFAULT 'contributeur'` avec
  `CHECK (role IN ('proprietaire','approbateur','contributeur','lecteur'))`.
- Membres existants → `contributeur`. Si `groups.created_by` n’est pas
  membre, l’insérer en `proprietaire` ; s’il l’est, le passer en
  `proprietaire`.
- Tuples `group#member@user` remplacés par `group#{role}@user`, puis
  suppression des tuples directs `member`.
- Tuples `object#{owner\|editor\|viewer}@group#member` (organisation,
  space, folder, document) réécrits selon le tableau § 1.3.
- Job au démarrage (`ApplicationRunner` + `SmartLifecycle` phase −10,
  après le bootstrap OpenFGA phase −20), **avant** d’accepter des
  requêtes. Verrou `pg_advisory_lock` (multi-instances). Idempotent
  (`authz_migrations`, clé `team-roles-v1`). Lecture FGA **paginée**
  (store entier : OpenFGA 1.8 refuse `object="type:"` sans id).
- Si `errors > 0` ou s’il reste un tuple
  `O#R@group:*#member` / `group:*#member@user:*` : **pas** marquée
  appliquée, démarrage refusé (message : nombre d’erreurs, objets),
  rejouable au boot suivant.
- Ordre du modèle : lecture / réécriture sous
  `classpath:openfga/model-pre-team-roles.json` (`member: [user] or …`
  encore assignable, sujets `group#member` encore autorisés sur les ACL),
  puis publication de `openfga/model.json` (ACL sans `group#member` —
  `member` reste une relation **calculée** sur `group`).

## 2. Annuaire

### 2.1 `GET /api/v1/directory/users`

Recherche **nominative**. Pas d’énumération.

| Règle | Comportement |
|---|---|
| `q` obligatoire, **≥ 2** caractères une fois trimé | sinon **400** |
| `limit` | défaut 8, maximum **20** (au-delà → borné à 20, pas d’erreur) |
| Cible | `display_name` et `email`, **insensible à la casse et aux accents** (NFD + suppression des diacritiques, puis `LOWER`) |
| Statut | **`active` uniquement** — un compte `disabled` n’apparaît jamais |
| Réponse | `{ "users": [ { "id", "displayName", "email", "initials" } ] }` — **exactement ces 4 champs**, rien d’autre |
| Auth | tout utilisateur authentifié |
| PAT | autorisé en portée **read** (GET) |
| Débit | par utilisateur appelant, fenêtre glissante 1 minute, défaut **30** (`socle.directory.rate-limit-per-minute`) → **429** au-delà |

Interdit : endpoint sans `q`, `q=*`, liste paginée « tous les users », champ
`status` / rôles / équipes dans cette réponse.

### 2.2 `GET /api/v1/admin/users`

Réservé à `ADMINISTRATEUR_SYSTEME`. PAT déjà interdit sur `/api/v1/admin/**`
→ **403** `pat_not_allowed` (inchangé). JWT non-admin → **403**.

Query : `q` (optionnel, même normalisation), `status` (`active` \| `disabled`),
`platformRole`, `groupId`, `offset`, `limit` (défaut 50, max 200).

Chaque ligne :

- identité : `id`, `displayName`, `email`, `initials`
- `platformRoles` : rôles globaux
- `teams` : `{ id, name, role }` pour chaque équipe
- `status`, `lastActivityAt` (`users.last_login_at`, nullable)

Compteurs (sur le filtre courant, hors pagination) : `activeCount`,
`teamCount` (nombre d’équipes distinctes dans l’instance, pas le filtre).

Assemblage **sans N+1** : 1 requête users + 1 `user_platform_roles` (`user_id
= ANY(?)`) + 1 `group_members JOIN groups` (`user_id = ANY(?)`). Une liste
de 200 utilisateurs doit rester sur ces 3 allers SQL (mesure dans le rapport
de PR).

### 2.3 Qui peut gérer quoi (synthèse)

| Surface | Qui voit | Qui mute |
|---|---|---|
| Annuaire `/directory/users` | tout authentifié (rate-limité) | — (lecture seule) |
| Admin `/admin/users` | admin système | disable/enable et rôles plateforme : admin système, **dernier admin** protégé (existant) |
| Accès objet | **owner** FGA de l’objet (ou admin système) | idem ; sinon **403** sur GET / POST / DELETE |
| Membres d’équipe | membres + admin | `proprietaire` de l’équipe ou admin système |
| Invitations org | visible « Bientôt » | **pas d’API** (chantier B) |

## 3. Écran Accès

Routes : `/spaces/:id/access`, `/folders/:id/access`, `/documents/:id/access`.
Maquette comparable : `Access.dc.html` = **document** uniquement.

- Liste enrichie : `displayName`, `initials`, `lastActivityAt` (personnes) ;
  `name`, `memberCount` (équipes). **Aucun UUID affiché.**
- Rôle affiché = relation OpenFGA de l’entrée : `owner` → Propriétaire,
  `editor` → Éditeur, `viewer` → Lecteur.
- « + Inviter » : combobox ARIA (clavier : flèches, Entrée, Échap) branchée
  sur l’annuaire (personnes) et `GET /api/v1/groups` (équipes, filtrage
  local par nom). Plus **aucun** champ UUID.
- Onglets document (Lire / Modifier / Historique / Commentaires / Accès) :
  chrome existant (`DocumentChrome`) sur la route document.
- Retrait : `DELETE` accès existant (inchangé).
- Encadré « Déplacer ce document vers la corbeille » : API existante
  `DELETE /api/v1/trash/documents/{id}` (exige `editor` ; l’UI le montre
  seulement si `canManage` = owner, comme la maquette). Dossier / espace :
  pas d’encadré (hors maquette).

`GET /access` sans droit de gestion → **403** (déjà le cas). Un non-owner
ne voit pas la liste et ne peut ni accorder ni retirer.

## 4. Admin « Membres & équipes »

`AdminShell`, route `/admin/team` (onglets Membres / Équipes).

- **Membres** : recherche, tableau membre / rôle global / équipe / rôle
  d’équipe / statut / dernière activité ; activer / désactiver via
  `POST /api/v1/admin/users/{id}/disable|enable` avec confirmation **dans
  la page** (pas de `window.confirm`) ; changement de rôle global via
  l’API plateforme existante (protection du dernier admin conservée).
- **Équipes** : liste, création, membres et rôles (annuaire + § 1.2).
- **Invitations** et CTA « + Inviter un membre » : visibles, **désactivés**,
  libellé « Bientôt ». Backlog `TEAM-INVITATIONS` (chantier B) —
  `NOT_IMPLEMENTED` visuel.
- `/team` : redirection vers `/admin/team` pour un admin système ; **retirée
  des menus** (sidebar, menu mobile) pour les autres. Un non-admin qui
  ouvre `/team` ou `/admin/team` → garde admin existante (403 / redirection
  hors admin).

## 5. Audit

`recordSync` pour les mutations d’accès (déjà le cas). `record` (async) pour
l’équipe et les rôles globaux.

| Action | Événement | Metadata obligatoire |
|---|---|---|
| Octroi / retrait d’accès | `access.granted` / `access.revoked` (et `*_requested`) | `actor`, `subjectType`, `subjectId`, `relation` |
| Ajout / retrait de membre | `group.member_added` / `group.member_removed` | `actor`, `memberUserId`, `role` |
| Changement de rôle d’équipe | `group.member_role_changed` | `actor`, `memberUserId`, `before`, `after` |
| Changement de rôle global | `platform.role_granted` / `platform.role_revoked` | `actor`, cible, rôle (existant) |

Pas de secret, pas d’e-mail en clair obligatoire au-delà de ce que les
événements existants portent déjà.

## 6. Sécurité — cas de test (oracle)

1. Non-admin : `GET /api/v1/admin/users` → **403**.
2. Annuaire : sans `q` ou `q` d’un caractère → **400** ; au-delà du débit →
   **429**.
3. Compte `disabled` : absent de toute réponse `/directory/users`, quelle
   que soit `q`.
4. Annuaire : le JSON d’un user n’a que `id`, `displayName`, `email`,
   `initials` (échec si un champ de plus est sérialisé).
5. Accès : utilisateur sans `owner` sur l’objet → GET / POST / DELETE
   `/access/...` → **403**.
6. Rôles d’équipe : chaque cellule du tableau § 1.3 (positif **et**
   négatif) — modèle `fga model test` **et** IT Java + OpenFGA réel.
7. Désactivation : accès refusé + PAT révoqués (tests existants
   `UserAccountServiceTest` / `PersonalAccessTokenHttpTest` restent vrais).
8. Dernier propriétaire d’équipe : retrait ou rétrogradation → **409**.
9. PAT : `/admin/**` interdit ; `GET /directory/users` autorisé en `read`.

## 7. Parité visuelle

- Specs `access` et nouveau `team` (onglets Membres, Équipes) selon
  `docs/visual-parity.md`. Sections comparées ≤ 1 %.
- Retirer `ACCESS-PEOPLE-PICKER` (`access-doc-tabs`, `access-invite`,
  `access-people-rows`) et l’`APP_EXTRA` `access-grant-form`.
- `ACCESS-DELETE-BOX` implémenté sur le document → retirer de
  `NOT_IMPLEMENTED`.
- `TEAM-INVITATIONS` (onglet + CTA) reste `NOT_IMPLEMENTED`.
- **Référence de mesure = CI Linux.** Les mesures locales Windows sont
  indicatives. Les gardes `RASTER_EXCEPTIONS` / `SUBPIXEL_EXCEPTIONS`
  « inutilisée sur ce rasterizer » ne s’évaluent **que sur Linux (CI)** :
  un ≤ 1 % Windows ne fait plus échouer ni ne force le retrait d’une
  entrée encore nécessaire sur Linux.

## 8. Hors périmètre

- Invitations par e-mail / lien (chantier B).
- Présence temps réel (« En ligne ») : `lastActivityAt` = `last_login_at`.
- Modification des maquettes `*.dc.html`.
- Signature ou import de licence.
