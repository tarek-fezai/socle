# Audit intégration : Keycloak → OpenFGA → Temporal (soumission d’approbation)

Date : 2026-09-28  
Périmètre : `POST /api/v1/documents/{id}/approvals` (`DocumentApprovalService.startApproval`)  
Hors scope : SLA / escalade Temporal, modèle DSL OpenFGA, config Keycloak.

## Verdict

**L’ordre Check OpenFGA → `WorkflowClient.start` était déjà respecté** (Check document `editor` avant le start).  
Renforcement appliqué dans cette tâche :

1. Chargement du document **avant** les Checks (404 propre).
2. Check OpenFGA **espace** (`requireSpaceRelation(..., viewer)`) **en plus** du Check document, toujours **avant** Temporal.
3. Commentaire Javadoc + tests d’intégration prouvant qu’un échec FGA ne démarre **aucun** workflow.
4. Aucun pattern « start puis cancel » : en cas de 403, `WorkflowClient.start` n’est jamais appelé.

## Flux vérifié

```
JWT (Keycloak) → sync user
             → find document (404 si absent)
             → OpenFGA requireDocumentRelation(editor)     ❗️ avant Temporal
             → OpenFGA requireSpaceRelation(viewer)      ❗️ avant Temporal
             → garde « déjà en_cours »
             → WorkflowClient.start(...)
             → activity recordSubmission → approval_requests
```

Décision (`…/approvals/{requestId}/decide`) : Check `editor` puis signal Temporal ; activity `recordDecision` → `approval_actions` + status.

## Corrections

| Élément | Avant | Après |
|--------|--------|--------|
| Ordre FGA vs Temporal | Check document avant start (OK) | + Check espace, document load avant Checks |
| Workflow orphelin si 403 | Impossible (start après check) | Confirmé par tests `never` / submissions vides |
| Worker en tests | `@PostConstruct` démarrait un worker réel | `socle.temporal.worker-enabled=false` en construction test |

## Tests

Fichier : `backend/src/test/java/.../DocumentApprovalSubmitIntegrationTest.java`

| Scénario | Attendu |
|----------|---------|
| **Négatif** — JWT OK, FGA document refuse | 403, 0 submission activity, pas de workflow |
| **Négatif** — FGA document OK, FGA espace refuse | 403 avant start Temporal |
| **Positif** — contributeur + FGA OK | start Temporal (DescribeWorkflow RUNNING/COMPLETED), signal Approuver → status `approuve`, 1 `approval_actions`, `temporal_workflow_id` cohérent |

Stack de test : **Temporal `TestWorkflowEnvironment`** (in-process) + **Mockito** pour OpenFGA / JDBC / users. Pas de Docker requis pour ces scénarios.

### Relancer

```bash
cd backend
mvn test -Dtest=DocumentApprovalSubmitIntegrationTest
# ou toute la suite
mvn test
```

## Référence code

`DocumentApprovalService.startApproval` — bloc commenté « OpenFGA Check (DOIT rester avant WorkflowClient.start) ».

## Suite : SLA / escalade

Voir `docs/workflow-sla-escalade.md` (multi-étapes, timer Temporal, chaîne épuisée sans auto-décision).
