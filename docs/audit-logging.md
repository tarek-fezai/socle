# Journal d'audit (`audit_log_events`)

## Mécanisme

- Service central : `AuditService`
  - **`recordSync`** — INSERT synchrone ; échec → `AuditWriteException` (l'action métier
    ne doit pas être considérée réussie).
  - **`record`** — INSERT asynchrone ; échec → log ERROR + métrique
    `socle.audit.write.failures` (Actuator `/actuator/metrics/socle.audit.write.failures`).
- INSERT uniquement ; contrainte append-only en base : `V5__audit_log_append_only.sql`
  (`REVOKE UPDATE, DELETE` sur le rôle `socle`).
- **Sanitization** : `AuditService.sanitizeMetadata` retire systématiquement
  `body`, `bodySnapshot`, `content`, `resolvedBody`, `previousBody` avant INSERT
  (périmètre metadata-only — voir § ci-dessous).

## Sync vs async — choix conformité

| Mode | Événements | Pourquoi |
|------|------------|----------|
| **Sync** (`recordSync`) | `access.grant_requested` / `access.granted`, `access.revoke_requested` / `access.revoked` | Qui a accès à quoi = argument conformité. Voir § cohérence ci-dessous. |
| **Sync** (`recordSync`) | `document.submitted_for_approval`, `document.approved`, `document.rejected`, `approval.escalated`, `approval.chain_exhausted` | Worker Temporal : déjà synchrone dans l'activity ; échec → retry Temporal. |
| **Async** (`record`) | Documents, espaces, groupes, export, workflows, SIEM/webhooks | Volume / criticité opérationnelle ; observabilité via métrique d'échec. |

## Cohérence grant/revoke ↔ audit (option A)

OpenFGA et Postgres ne partagent pas de transaction. **Ordre retenu : audit-first**
(pas de 2PC). Justification : l'ordre précédent (FGA puis audit) laissait un tuple
actif si `recordSync` échouait après un grant réussi — le client voyait un 503 alors
que l'accès existait déjà. Refactorer en audit-first ne casse aucune contrainte technique
et évite le chemin nominal de compensation.

### Séquence (`AccessController`)

1. **`access.grant_requested`** / **`access.revoke_requested`** (`metadata.outcome=pending`)
   — si INSERT échoue → **503**, OpenFGA **inchangé**.
2. **Écriture / suppression du tuple OpenFGA**
   — si échec → erreur FGA (502) ; la ligne `*_requested` trace la tentative, pas un accès réel.
3. **`access.granted`** / **`access.revoked`** (`metadata.outcome=applied`)
   — si INSERT échoue → **compensation OpenFGA** (revoke si c'était un grant, re-grant si
     c'était un revoke) puis **503**. Après compensation réussie, aucun tuple fantôme.

### Propriété garantie

| Réponse client | État |
|----------------|------|
| **2xx** | Tuple FGA **et** ligne `access.granted` / `access.revoked` présents |
| **5xx** (audit requested) | FGA inchangé |
| **5xx** (audit applied + compensation OK) | FGA ramené à l'état antérieur |
| **5xx** (audit applied + compensation KO) | Log **`ALERT CRITICAL`** — intervention manuelle |

### Rattrapage manuel (double échec)

Si le log contient `ALERT CRITICAL` après un grant/revoke :

1. Lire le tuple concerné dans OpenFGA (store / playground `objectType:objectId`).
2. Chercher dans `audit_log_events` la paire `*_requested` sans `access.granted` /
   `access.revoked` correspondant (même `resource_id`, `subjectId`, `relation`).
3. Soit supprimer / rétablir le tuple FGA pour coller à l'intention métier, soit écrire
   manuellement la ligne `applied` manquante **après** avoir stabilisé FGA — puis relancer
   l'opération côté API une fois Postgres et OpenFGA sains.

## Événements branchés (complétude)

| Domaine | Action | Déclencheur | Mode |
|---------|--------|-------------|------|
| Accès | `access.grant_requested` → `access.granted` | `AccessController` grant | sync |
| Accès | `access.revoke_requested` → `access.revoked` | `AccessController` revoke | sync |
| Documents | `document.created` / `document.updated` | `DocumentService` | async |
| Documents | `document.version_created` / `document.version_restored` | `DocumentService` | async |
| Approbation | `document.submitted_for_approval` | `ApprovalActivitiesImpl` | sync |
| Approbation | `document.approved` / `document.rejected` | `ApprovalActivitiesImpl` | sync |
| Approbation | `approval.escalated` / `approval.chain_exhausted` | `ApprovalActivitiesImpl` | sync (`actor_is_system`) |
| Corbeille | `*.trashed` / `*.restored_from_trash` / `*.purged` | `TrashService` | async |
| **Spaces** | `space.created` / `space.updated` | `SpaceService` | async |
| **Spaces** | `space.owner_added` / `space.owner_removed` / `space.responsible_changed` | `SpaceService` | async |
| **Groupes** | `group.created` / `group.updated` / `group.deleted` | `GroupService` | async |
| **Groupes** | `group.member_added` / `group.member_removed` | `GroupService` | async |
| **Export** | `document.exported` / `folder.exported` / `tag.exported` | `ExportService` | async |
| **Workflows** | `workflow.created` / `workflow.updated` / `workflow.deleted` | `ApprovalWorkflowDefinitionService` | async |
| **Intégrations** | `siem.connector_created` / `_updated` / `_deleted` | `SiemConnectorService` | async |
| **Intégrations** | `webhook.endpoint_created` / `_updated` / `_deleted` | `WebhookEndpointService` | async |

## `auth.login` — non capturé côté backend

Le login est géré entièrement par Keycloak (redirect OIDC / `keycloak-js`).
Le backend Spring ne reçoit pas de callback de connexion : il ne voit que des JWT déjà émis
sur les appels API. Tracer `auth.login` ici nécessiterait un endpoint dédié post-login ou
l’Event SPI Keycloak → hors scope ; volontairement **non implémenté** plutôt que bricolé.

## Lecture API — auditeur transverse

`GET /api/v1/audit` — rôle Keycloak realm **`auditeur`** uniquement (401 sans token, 403 sans
ce rôle). Le rôle **`integrateur`** (configuration SIEM/webhooks) **ne suffit pas**.

### Transversalité (autorité plateforme)

- **Aucune** jointure ni filtre sur appartenance d'espace, `space_owners`,
  `external_reference`, membres de groupe ou OpenFGA dans `AuditQueryService`.
- Un auditeur voit l'activité de **toute** l'instance, y compris les espaces dont il
  n'est ni owner ni membre et sur lesquels il n'a aucun droit OpenFGA.
- Un owner d'espace **ne peut pas** retirer cette visibilité : ni via
  `external_reference: restricted`, ni via révocation d'accès documentaire, ni via
  configuration locale. Le rôle `auditeur` est un **rôle realm Keycloak**, attribuable
  uniquement hors gouvernance applicative (console / IdP) — aucune API Socle ne l'accorde
  ni ne le retire.

Preuve automatisée : `AuditorTransversalityTest`, `AuditorVisibilityMvcTest`.

### Périmètre metadata-only (décision explicite)

L'auditeur **n'est pas** un super-lecteur de contenu. OpenFGA reste la seule porte
d'accès au corps des documents. Via le journal, l'auditeur voit uniquement des
**métadonnées de gouvernance** :

| Exposé | Non exposé |
|--------|------------|
| Acteur (`actorId`, display name, email) | Corps TipTap / snapshot (`body`, `content`, …) |
| Action, horodatage, IP | Contenu résolu de transclusion |
| Type / id de ressource | Secrets SIEM / webhook (redaction côté config) |
| Titre de document / nom d'espace / groupe (investigation) | |

Le titre dans `metadata` est un choix documenté pour l'investigation — **pas** un bypass
accidentel de la confidentialité par page. Preuve : `AuditorTransversalityTest`
(`sanitizeMetadata_stripsDocumentBodyKeys`) + `GovernanceAuditCompletenessTest`
(`exportDocument_recordsExportWithoutBodyLeak`).

| Paramètre | Effet |
|-----------|--------|
| `resourceType` | exact (`document`, `space`, …) |
| `resourceId` | UUID ressource |
| `actorId` | UUID acteur |
| `action` | exact (`access.granted`), préfixe (`access.*`), liste CSV |
| `since` / `until` | bornes ISO-8601 sur `created_at` |
| `offset` / `limit` | pagination (défaut 50, max 200) |

Réponse : `{ items, offset, limit, total }` — chaque item enrichi avec
`actorDisplayName` / `actorEmail` (jointure `users` uniquement).

### Comptes de test (realm `socle`) — rôles disjoints

| Compte | Mot de passe | Rôles | Peut |
|--------|--------------|-------|------|
| `auditeur` | `auditeur` | `auditeur` seul | Lire `/api/v1/audit` + livraisons webhook |
| `integrateur` | `integrateur` | `integrateur` seul | CRUD SIEM / webhooks + livraisons (pas le journal) |
| `contributeur` | `contributeur` | `contributeur` seul | Contenu docs — ni audit ni config intégrations |

## Séparation des tâches (SoD) — supervision ≠ configuration

| Capacité | `auditeur` seul | `integrateur` | `contributeur` |
|----------|-----------------|---------------|----------------|
| `GET /api/v1/audit` | oui | **non** | **non** |
| CRUD SIEM / webhook-endpoints | **non** | oui | **non** |
| Muter Spaces / Groupes / Workflows | **non** | — | selon droits métier |
| Export PDF | **non** | — | si viewer OpenFGA |
| `GET /api/v1/webhooks/deliveries` | oui | oui | **non** |

`DenyAuditeurOnlyAuthorizationManager` : un JWT qui n'a que `ROLE_auditeur` est refusé
sur POST/PUT/DELETE Spaces, Groupes, Workflows et sur GET `*/export`. Preuve :
`AuditorSoDSecurityTest` + `IntegrationsSecurityTest`.

Un auditeur ne peut pas détourner le flux SIEM/webhook puis auditer lui-même le
résultat sans contrôle indépendant. Voir aussi `docs/siem-delivery.md`.

### Preuve serveur (pas seulement UI)

- **Automatisé :** `IntegrationsSecurityTest` + `AuditControllerSecurityTest` +
  `AuditorSoDSecurityTest` + `AuditorVisibilityMvcTest` —
  `@WebMvcTest` + `@Import(SecurityConfig)` + `jwt().authorities(ROLE_*)`.
- **Live (2026-09-28, JAR sur `:18090`)** : tokens password-grant réels
  (`auditeur` / `integrateur`) — 403 SoD croisés confirmés.

## Tests

```bash
cd backend
mvn test

# Frontend
pnpm test
```
