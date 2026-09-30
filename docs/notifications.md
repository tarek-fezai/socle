# Notifications

## Production réelle (audit code)

| Type | Écrit par | Statut |
|------|-----------|--------|
| `approval_chain_exhausted` | `ApprovalActivitiesImpl.recordChainExhausted` | **produit** |
| `approval_request`, `comment`, `publish`, `attestation_due` | — | cités dans le schéma, **jamais déclenchés** (backlog) |

Payload actuel :
`{ document_id, approval_request_id, steps_traversed, message }`.

## API

- `GET /api/v1/notifications?unreadOnly=&offset=&limit=` — JWT, **uniquement** `user_id` courant.
  Enrichit `documentTitle` via jointure. Renvoie aussi `unreadCount` (badge).
- `POST /api/v1/notifications/{id}/read` — marque `read_at`.
  Notif absente ou appartenant à un autre utilisateur → **404** (pas 403, anti-fuite d'existence).

Pas de `read-all` pour l'instant (l'écran marque une notif au clic).
