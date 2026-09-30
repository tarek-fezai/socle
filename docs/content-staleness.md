# Fraîcheur du contenu (staleness)

## Décisions (explicites)

| Décision | Choix | Justification |
|----------|-------|----------------|
| Source de la date | Dernière **écriture de contenu** via `DocumentStore.lastContentModifiedAt` | Relational : dernière row `document_versions.created_at`, sinon `documents.created_at`. Git : dernier commit touchant le fichier doc. **Pas** `documents.updated_at` (touché aussi par transitions workflow). |
| Restore | Rafraîchit le badge | Restore = écriture via `DocumentStore` (archive + write) — nouvelle date de contenu. |
| Transition workflow sans body | **Ne rafraîchit pas** | Gouvernance ≠ fraîcheur de contenu (séparation maintenue partout ailleurs). |
| Seuil | **Instance** uniquement (`socle.staleness.thresholdDays`) | Portée volontairement réduite V1 ; extension par espace possible plus tard, hors scope. |
| Défaut | **90 jours** | Compromis doc d'entreprise : au-delà d'un trimestre sans écriture, signal « à revoir » sans être trop bruyant. |
| Calcul | **Dérivé à la lecture** | Pas de champ stocké ni job périodique — une seule source fiable (versions/commits). |
| Config | Lu au **démarrage** (`@PostConstruct` fail-fast si ≤ 0 ou > 3650) | Aligné sur `socle.storage.provider`. |

## API

| Endpoint | Rôle |
|----------|------|
| Champs `stale`, `contentModifiedAt`, `stalenessThresholdDays` sur `GET /documents` et `GET /documents/{id}` | Badge document |
| `GET /api/v1/spaces/{id}/content-health` | Liste des docs **stale** de l'espace |

Filtrage OpenFGA : `listViewableDocumentIds` ∩ docs de l'espace — même famille que Search / Graphe.
Un document stale non lisible **n'apparaît pas** (pas de nœud anonymisé).

## Frontend

- Badge sur édition / listes (`StaleBadge`)
- `/spaces/:spaceId/content-health` (maquette ContentHealth.dc.html)

## Hors scope

- Seuil par espace / par type de document
- Couplage au statut `en_revue` / `valide`
- Job de recalcul
