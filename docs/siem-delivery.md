# Diffusion SIEM (audit → connecteurs)

## Objectif

Après chaque écriture réussie dans `audit_log_events`, Socle enqueue une livraison
vers chaque connecteur `siem_connectors` avec `status = 'connected'`. La livraison
HTTP est assurée par le **webhook-worker Go** (même processus que `webhook_deliveries`).

L’audit reste la source de vérité interne. Le SIEM est un aval **best-effort** :
un Splunk down ne doit jamais faire échouer un grant d’accès ni bloquer l’INSERT audit.

## Flux

```
action métier
    → AuditService.record / recordSync  (INSERT audit_log_events … RETURNING id)
    → SiemDeliveryEnqueueService.enqueueAfterAudit  (@Async, best-effort)
         pour chaque siem_connectors WHERE status='connected'
             INSERT siem_deliveries (payload formaté, status=pending)
    → webhook-worker (Go) poll siem_deliveries pending/retrying
         JOIN siem_connectors status=connected
         POST config.endpoint + auth provider
         update status / attempt_count / last_response_code / delivered_at
```

Table `siem_deliveries` : pendant exact de `webhook_deliveries`, avec en plus
`audit_event_id` pour la traçabilité.

## Formats supportés (`config.format`)

| Format | Provider typique | Statut |
|--------|------------------|--------|
| **hec** | Splunk | **Supporté** — enveloppe HEC (`event`, `sourcetype=socle:audit`, `source`, `fields`) |
| datadog | Datadog | Support partiel — tableau JSON intake logs (message + attributes) |
| sentinel | Microsoft Sentinel | Support partiel — tableau JSON Log Analytics / DCR simplifié |
| json | (fallback) | Événement plat sans enveloppe provider |

Si `format` est absent, le provider détermine le défaut (`splunk` → `hec`, etc.).

### Auth (lue depuis `siem_connectors.config`, jamais loguée en clair)

- Splunk : `Authorization: Splunk <hec_token|token>`
- Datadog : header `DD-API-KEY` (`api_key` / `dd_api_key`)
- Sentinel : header `Authorization` (`shared_key` / `authorization`) + `Log-Type: SocleAudit`

Le worker POSTe le **payload déjà formaté** côté Java (pas de re-wrapping).

## Retry (réutilisation du pattern webhook)

Même logique que `webhook_deliveries` :

1. **In-tick** : jusqu’à 5 tentatives HTTP avec backoff exponentiel (500 ms → …).
2. **Inter-poll** : en cas d’échec, `status=retrying`, `attempt_count++`.
3. Après **5** cycles d’échec (`MaxAttempts`) : `status=failed` **et**
   `siem_connectors.status = 'error'` pour refléter la réalité opérationnelle.

## Observabilité

| Métrique | Où |
|----------|----|
| `socle.audit.write.failures` | Backend — échec INSERT audit async |
| `socle.siem.enqueue.failures` | Backend — échec création `siem_deliveries` |
| `socle_siem_delivery_failures{connector_id,provider}` | Worker Go — échec de cycle de livraison |

Endpoint Prometheus du worker : `GET /metrics`.

## Hors scope / backlog

- Formats CEF, enveloppes Datadog/Sentinel avancées (signatures Shared Key HMAC, etc.).
- Rejouer automatiquement les livraisons d’un connecteur repassé `connected` après `error`
  (réactivation manuelle via UI PUT status=connected).

## Configuration UI

CRUD `/api/v1/siem-connectors` + `/api/v1/webhook-endpoints` + écran `/integrations`
protégés par le rôle Keycloak **`integrateur`** (pas `auditeur`).

**SoD :** la supervision du journal (`auditeur`) et la configuration des destinations
(`integrateur`) sont des rôles disjoints — voir `docs/audit-logging.md`.

- Secrets SIEM masqués en lecture (`*_prefix`).
- `POST …/test` n’altère pas `status` ; activation explicite via PUT.
- `GET /api/v1/webhooks/deliveries` : lecture pour **`auditeur` et `integrateur`**.

## Migration

Flyway `V7__siem_deliveries.sql` — table + index partiel sur `pending`/`retrying`.
