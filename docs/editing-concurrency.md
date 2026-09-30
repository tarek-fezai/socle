# Édition concurrente — conflit réel & soft-lock

## 1. Constat avant cette tâche

| Provider | Détection à l'écriture | Preuve |
|----------|------------------------|--------|
| **Git** | Oui — `documents.git_head_sha` vs HEAD réel dans `GitDocumentStore.writeCurrentContent` → **409** | `docs/storage-providers.md`, code `expectedGitHeadSha` |
| **Relational** | **Non** — `RelationalDocumentStore.writeCurrentContent` ignore `expectedGitHeadSha` ; `DocumentService.update` n'envoyait aucune version attendue du client. Deux PUT quasi simultanés basés sur la même version **s'écrasaient silencieusement**. | Absence de `expectedVersionNo` / check dans `UpdateDocumentRequest` (pré-V16) |

Le soft-lock seul n'aurait rien protégé contre cet écrasement. Le trou relational a donc été **corrigé dans cette tâche** (préalable obligatoire).

## 2. Contrôle de version (conflit réel)

- Le client envoie `expectedVersionNo` (= `currentVersionNo` au chargement).
- `DocumentService.update` **et** `restore` chargent la row avec **verrou pessimiste**
  (`findActiveByIdForUpdate`), comparent, refusent en **409** si mismatch.
- Mode Git : inchangé (`git_head_sha` + comparaison HEAD).
- Le soft-lock **n'intervient pas** dans ces chemins.

## 3. Soft-lock (advisory)

| Choix | Valeur | Justification |
|-------|--------|----------------|
| Nature | **Advisory** uniquement | Informe, n'empêche jamais l'édition / la sauvegarde |
| Stockage | Table Postgres `document_edit_locks` | Visible multi-instance (≠ verrou JVM Git) |
| Heartbeat | **15 s** (`socle.edit-lock.heartbeat-seconds`) | Onglet ouvert → renouvellement |
| TTL | **45 s** (= 3 × heartbeat) | Fermeture brutale d'onglet → expiration sans lock fantôme |
| Acquisition | Ouverture Edit (`POST …/edit-lock`) | Renouvellement = même endpoint / heartbeat |
| Libération | `DELETE …/edit-lock` à fermeture / après save | Immédiat, sans attendre le TTL |
| Visibilité | `GET …/edit-lock` — droit **viewer** | Pas de fuite d'identité d'éditeur sans accès doc |

Un second utilisateur qui « acquire » pendant qu'un lock actif existe **ne vole pas** le lock : il reçoit l'état du holder courant.

## API

| Méthode | Chemin |
|---------|--------|
| GET | `/api/v1/documents/{id}/edit-lock` |
| POST | `/api/v1/documents/{id}/edit-lock` |
| POST | `/api/v1/documents/{id}/edit-lock/heartbeat` |
| DELETE | `/api/v1/documents/{id}/edit-lock` |
| PUT body | `expectedVersionNo` sur `/api/v1/documents/{id}` |

## Frontend

Bannière non bloquante sur `/docs/:id` si un autre holder est actif ; heartbeat périodique ; release au démontage / save.
