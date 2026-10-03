# Score de fiabilité documentaire (`reliability_score`)

## Règle produit

Le score n’existe **que** pour un document au statut `valide`.

| Statut | `reliability_score` | `reliability_computed_at` |
|--------|---------------------|---------------------------|
| `valide` | calculé (0–100, 2 décimales) | horodatage du dernier calcul |
| `brouillon` / `en_revue` / `archive` | `NULL` | `NULL` |

Pourquoi pas de chiffre hors `valide` : afficher un pourcentage sur un brouillon ou
une version en revue laisserait croire à une métrique fondée alors qu’aucune validation
ni attestation applicable n’est en place. Décision produit : **jamais afficher un chiffre
non fondé** — l’UI doit montrer « Non évalué » lorsque le score est `null` (jamais `0%`
par défaut).

Les dossiers (`folders.reliability_score`) sont hors scope de cette itération.

## Formule

Trois composantes sur 0–100, poids fixes (non configurables côté admin) :

```
reliability_score = round(0.4 × freshness + 0.3 × resolution + 0.3 × attestation, 2)
```

Constantes : `ReliabilityScoreDefaults` (`WEIGHT_*`, `DEFAULT_REVIEW_CYCLE_DAYS = 365`).
Cycle par défaut aussi exposé en config : `socle.reliability.default-review-cycle-days`.

### 1. Fraîcheur (poids 0.4)

```
freshness = clamp(1 − jours_depuis_dernière_validation / cycle_revue_jours, 0, 1) × 100
```

**Source de la dernière validation** : `MAX(created_at)` des lignes
`audit_log_events` avec `action = document.approved` et `resource_id = document`.

Choix : l’audit est la trace canonique de validation. Les `document_versions` servent
aussi aux archives de mutation / restore / soumission — leur `created_at` n’identifie
pas une validation.

**Cycle de revue** : `retention_policies.retention_days` de la première politique
(ordonnée par `created_at ASC`) dont `applies_to`/`scope_ref_id` correspond à l’espace
du document ou à un de ses tags ; sinon `DEFAULT_REVIEW_CYCLE_DAYS` (365).

### 2. Résolution des commentaires (poids 0.3)

```
resolution = resolved / total × 100   (si total = 0 → 100)
```

Comptage sur `document_comments` du document.

### 3. Conformité d’attestation (poids 0.3)

- `is_mandatory_ack = false` → `attestation = 100`
- sinon, campagne active = la campagne ouverte (`closed_at IS NULL`, au plus une par document) dans
  `attestation_campaigns` pour ce document :
  - pas de campagne, ou `audience_size = 0` (taille d'audience figée à la création,
    voir V33) → `attestation = 100` (non applicable, pas de division par zéro)
  - sinon `attestation = acknowledgments_count / audience_size × 100`
    (accusés de `attestation_acknowledgments` pour `campaign_id`)
  - si `due_date` de la campagne est dépassée **et** `attestation < 100` →
    `attestation × 0.5`

Note schéma : la campagne expose `due_date` (pas `ack_due_date`) ;
`documents.ack_due_date` est un champ distinct non utilisé par cette formule.

## Déclencheurs

| Événement | Comportement |
|-----------|--------------|
| `document.approved` (transition → `valide`) | recalcul **après commit**, **async** (`requestRecalculationAfterCommit`) — n’allonge pas la décision d’approbation |
| Rejet d’approbation → `brouillon` | score remis à `NULL` |
| `valide → en_revue` (update / restore body) | score remis à `NULL` **immédiatement** dans la même TX |
| Ajout / résolution d’un `document_comments` | `ReliabilityScoreService.onDocumentCommentChanged` si document `valide` |
| Ajout d’un `attestation_acknowledgments` | `ReliabilityScoreService.onAttestationAcknowledged` si document `valide` |
| Job quotidien (`ReliabilityScoreScheduler`, cron `0 15 3 * * *`) | recalcule tous les `valide` dont `reliability_computed_at` a plus de 24h (ou jamais calculé) — la fraîcheur baisse avec le temps |

Implémentation unique : `ReliabilityScoreService` (`compute` pour les tests unitaires de formule ;
`recalculate` pour persistance).

## API

`GET /api/v1/documents/{id}` (`DocumentResponse`) expose :

- `reliabilityScore` (`BigDecimal` ou `null`)
- `reliabilityComputedAt` (`Instant` ou `null`)

`GET /api/v1/documents` (`DocumentSummary`) expose aussi `reliabilityScore` pour permettre
la moyenne côté UI (rail liste / dossier) sans endpoint dédié.

## UI (frontend)

- Ligne de statut document (`DocumentReliabilityStatus`) : pourcentage + « Calculé le … »,
  ou **Non évalué** si `null` (jamais `0%`).
- Rail liste Documents (`FolderReliabilityRail`) : moyenne des scores **non-NULL** de la
  liste chargée ; ensemble vide → **Non évalué**. N’utilise pas `folders.reliability_score`.
