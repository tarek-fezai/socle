# Workflow Temporal — SLA et escalade multi-étapes

## Comportement

Le workflow `DocumentApprovalWorkflow` boucle sur les étapes de `approval_workflow_steps`
(ordre croissant `step_order`), chargées une fois via l'activity `loadWorkflowSteps`.

À chaque étape :

1. `current_step_order` / `sla_deadline_at` sont positionnés (`now() + sla_hours`).
2. Attente **parallèle** : signal `decide(approuve|rejete)` **ou** timer SLA
   (`Workflow.await(Duration.ofHours(sla), () -> pending != null)`).
3. **Signal avant timer**
   - `rejete` → clôture (`status=rejete`, document `brouillon`).
   - `approuve` sur dernière étape → clôture (`status=approuve`, document `valide`).
   - `approuve` sur étape intermédiaire → `approval_actions(approuve)` puis avance à l'étape suivante.
4. **Timer avant signal (escalade)**
   - Cible = `escalates_to_step_order` si renseigné, sinon `step_order + 1`.
   - `approval_actions.decision = reassigne` (acteur système).
   - `current_step_order` + `sla_deadline_at` recalculés pour la cible.
   - Audit : `approval.escalated` (`actor_is_system=true`).

Toutes les écritures DB passent par `ApprovalActivities` (un seul worker) — pas d'écritures parallèles concurrentes hors activities.

## Cohérence decide ↔ étape affichée (`expectedStepOrder`)

L'API `POST …/approvals/{requestId}/decide` exige `expectedStepOrder` (étape vue par
l'UI au chargement de `/approvals`).

Sous **transaction + `SELECT … FOR UPDATE`** sur `approval_requests` :

| État ligne | Réponse |
|------------|---------|
| `status != en_cours` | **409** `{ "error": "already_resolved" }` — aucun signal Temporal |
| `current_step_order != expectedStepOrder` | **409** `{ "error": "step_advanced" }` — aucun signal Temporal |
| OK | signal Temporal **dans la même transaction** (verrou encore tenu) |

L'activity d'escalade (`recordSlaEscalation`) prend le même verrou ligne, vérifie
`current_step_order == fromStepOrder`, et no-op si decide a déjà clôturé ou avancé.
Ainsi decide et escalade concurrentes : **un seul gagne**, jamais les deux appliqués
sur la même étape.

Frontend : sur `step_advanced`, message distinct + bouton **Recharger** (pas de retry
silencieux avec le nouveau `step_order`). Sur `already_resolved`, message « déjà traitée ».

## Chaîne épuisée (choix produit)

Si le SLA expire et qu'il n'existe **pas** d'étape cible valide après l'étape courante
(dernière étape sans `escalates_to_step_order`, ou cible absente / déjà passée) :

| Action | Détail |
|--------|--------|
| `approval_requests.status` | reste **`en_cours`** |
| `sla_deadline_at` | `NULL` (plus de timer actif) |
| `approval_actions` | une ligne **`reassigne`** (« Chaîne SLA épuisée — intervention manuelle ») |
| `notifications` | lignes **`type=approval_chain_exhausted`** pour le **demandeur** (`requested_by`) **et** chaque utilisateur du rôle approbateur de la dernière étape (`approver_role_id` → `user_global_roles`). Payload JSON : `document_id`, `approval_request_id`, `steps_traversed`. INSERT synchrone ; échec → exception (retry Temporal). |
| Audit | `approval.chain_exhausted` (`actor_is_system=true`, `actor_id=null`) |
| Auto-approbation / auto-rejet | **interdit** — une absence de décision ne devient jamais une décision |

Le workflow se termine avec le code résultat `en_cours_alerte`.

## Sélection de la définition à la soumission

Avant `WorkflowClient.start`, `ApprovalWorkflowDefinitionService.resolveId(spaceId, docType)`
choisit quelle définition (liste d'`ApprovalStepDef`) est passée au moteur — **sans**
modifier `DocumentApprovalWorkflow` / activities.

### Portée

| Colonne | Sens |
|---------|------|
| `approval_workflows.scope_space_id` | Espace ciblé, ou `NULL` = tous les espaces |
| `approval_workflows.scope_doc_type` | Type libre (insensible à la casse), ou `NULL` = tous les types |
| `documents.doc_type` | Type du document (optionnel) — apparié à `scope_doc_type` |

Seules les définitions `status = active` sont candidates.

### Règle de précédence

Parmi les définitions **éligibles** (scopes compatibles avec le document) :

1. **`space_type`** — `scope_space_id` = espace du doc **et** `scope_doc_type` = `doc_type`
2. **`space`** — espace exact, `scope_doc_type` NULL
3. **`type`** — `scope_space_id` NULL, type exact
4. **`global`** — les deux scopes NULL

Au même niveau de spécificité : plus ancien `created_at`, puis `name` ASC
(filet de sécurité pour données legacy). **À l'écriture**, une contrainte d'unicité
partielle + validation API refusent deux définitions `active` sur le même couple
`(scope_space_id, scope_doc_type)` (NULL = « tous », type insensible à la casse).
Les `draft` peuvent partager un scope ; l'activation déclenche le conflit (400).

### Documents sans `doc_type` (NULL après V9)

La colonne est ajoutée nullable, sans défaut — les documents préexistants restent
`doc_type IS NULL`. Une définition typée (`scope_doc_type` non NULL) **n'est pas
éligible** pour ces documents (`matchLevel` ignore le niveau `type` /
`space_type`) : dégradation vers `space` puis `global` puis `fallback`, sans
exception.

### Fallback

Si aucune définition active n'est éligible → seed **« Approbation simple »**
(`ensureDefaultWorkflow`, 1 étape / 24h / scopes NULL) — `matchLevel = fallback`.
La soumission **ne échoue pas** faute de match.

### Modification / suppression d'une définition

Comportement **bloquant** (pas de versioning) :

- `UPDATE` / `DELETE` refusés (**409**) tant qu'il existe une
  `approval_requests` avec `status = en_cours` pour ce `workflow_id`.
- `DELETE` également refusé s'il reste des demandes historiques (FK).

Aperçu UI : `GET /api/v1/documents/{id}/approvals/applicable-workflow`.
Admin : `CRUD /api/v1/approval-workflows`, UI `/admin/workflows`.

## Tests

```bash
cd backend
mvn test -Dtest=DocumentApprovalSlaEscalationTest,DocumentApprovalSubmitIntegrationTest,DecideExpectedStepOrderTest,DecideEscalationConcurrencyTest,ApprovalWorkflowDefinitionServiceTest,DocumentApprovalThreeStepDefinitionTest
```

Horloge : `TestWorkflowEnvironment.sleep(Duration.ofHours(...))` (accélérée, pas de vrai sommeil).
Concurrence : `DecideEscalationConcurrencyTest` (Testcontainers, `FOR UPDATE`).

## Fichiers

- `DocumentApprovalWorkflowImpl` — boucle + await SLA
- `DocumentApprovalService.decide` — `expectedStepOrder` + `FOR UPDATE` avant signal
- `DocumentApprovalService.startApproval` — résout la définition via
  `ApprovalWorkflowDefinitionService` **avant** le start Temporal
- `ApprovalWorkflowDefinitionService` — CRUD + précédence scope + seed
- `ApprovalActivities` / `ApprovalActivitiesImpl` — écritures DB + notifications + audit + verrou escalade
- `ApprovalConflictException` / `ApiExceptionHandler` — 409 `already_resolved` | `step_advanced`
- `ApprovalStepDef` — DTO sérialisable Temporal
