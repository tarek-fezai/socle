# Backup and restore

## What to backup

| Asset | Scope | Notes |
|-------|--------|--------|
| PostgreSQL `socle_core` | All app metadata, relational `documents.body`, audit, workflows | Primary application DB |
| PostgreSQL `openfga` | Authorization tuples | Required for access control |
| PostgreSQL `temporal` + `temporal_visibility` | Workflow history & visibility | In-flight approvals survive restore if consistent |
| Git content volume | `git-content` / `SOCLE_STORAGE_GIT_PATH` | **Required** when `SOCLE_STORAGE_PROVIDER=git` — canonical document bodies |
| Blob volume / S3 bucket | `blob-content` / `SOCLE_BLOB_LOCAL_DIR` or `SOCLE_BLOB_S3_BUCKET` | **Required** with Postgres — attachment bytes; metadata alone is not enough to restore files |
| OpenFGA config volume | `openfga-config/` (`store.id`, `model.id`, fingerprint) | **Cache only** — store/model ids are recoverable from the OpenFGA DB; losing this volume must not create a second store |
| Caddy TLS | `caddy-data` | Only if not using external cert manager |

Configuration secrets (`.env`, K8s Secrets) should be backed up in your secret manager, not only on disk.

## Store OpenFGA en double

Le backend (`OpenFgaBootstrap`) n'accepte **qu'un** store nommé `socle`. S'il en trouve plusieurs au démarrage, il **refuse de démarrer** (message avec ids et dates) au lieu d'en choisir un au hasard.

Causes fréquentes :

- un ancien script d'init qui créait un store à chaque run ;
- restauration de la base `openfga` **sans** aligner `OPENFGA_STORE_ID` ;
- volume `openfga-config` perdu alors qu'`OPENFGA_STORE_ID` n'était pas fixé, suivi d'une création alors que des stores orphelins existaient déjà.

Procédure :

1. Lister les stores : `GET {OPENFGA_API_URL}/stores` (ou CLI `fga store list`).
2. Identifier le store dont les tuples correspondent à votre backup Postgres `openfga` (en général le plus ancien / celui référencé par l'ancien `OPENFGA_STORE_ID`).
3. Fixer `OPENFGA_STORE_ID=<id choisi>` dans l'environnement (Compose `.env` / Helm values / Secret).
4. Supprimer **manuellement** les stores orphelins une fois la vérif faite (`fga store delete` / API) — jamais automatiquement au boot.
5. Si `OPENFGA_STORE_ID` pointe vers un id inexistant, le backend refuse aussi de démarrer (pas de création « à côté »).

Après une perte du volume de cache (`openfga-config`), laissez `OPENFGA_STORE_ID` vide **uniquement** s'il n'existe qu'un store `socle` : le backend le retrouve par nom. Sinon, configurez l'id explicitement.

## Consistency order

Goal: a restore point where Postgres projection matches Git HEAD (git mode) and OpenFGA tuples match visibility columns.

1. **Quiesce writes** (preferred): scale backend to 0 or enable maintenance; wait for Temporal workflows to reach a stable wait state.
2. **Snapshot Postgres** at the same logical time (single volume snapshot or `pg_dump` with `--serializable-deferrable` / stop apps first).
3. **Snapshot Git volume** immediately after or as part of the same storage snapshot (LVM/ZFS/cloud snapshot spanning both is ideal).
4. If only `pg_dump`: dump all four databases from the same quiet window, then archive the Git volume.

Without quiescing, run backups during low traffic and **verify drift endpoints** after restore.

## Backup procedure (Compose)

```bash
# Example — adjust container/volume names from `docker compose ps`
docker compose -f deploy/compose/docker-compose.yml stop backend webhook-worker

docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -Fc socle_core > socle_core.dump
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -Fc openfga > openfga.dump
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -Fc temporal > temporal.dump
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -Fc temporal_visibility > temporal_visibility.dump

docker run --rm -v socle-production_git-content:/data -v "$PWD:/backup" alpine \
  tar czf /backup/git-content.tgz -C /data .

# Local blob provider (skip if SOCLE_BLOB_PROVIDER=s3 — backup the bucket instead)
docker run --rm -v socle-production_blob-content:/data -v "$PWD:/backup" alpine \
  tar czf /backup/blob-content.tgz -C /data .

docker compose start backend webhook-worker
```

## Restore procedure

1. Stop application services (`backend`, `frontend`, `webhook-worker`, `caddy`).
2. Restore Postgres dumps into empty databases (or recreate volume + init scripts, then `pg_restore`).
3. Restore Git volume tarball to `git-content`.
4. Restore blob volume (`blob-content`) or the S3-compatible bucket together with Postgres — attachment rows without bytes are unusable.
5. Ensure `OPENFGA_STORE_ID` / `OPENFGA_MODEL_ID` in env match the restored OpenFGA DB (or re-run model publish if store id changed — tuples must align).
6. Start stack; watch backend logs for storage consistency validators.

## Post-restore verification

As a platform admin (`ADMINISTRATEUR_SYSTEME` / realm role `administrateur-systeme`):

| Check | Endpoint | Expect |
|-------|----------|--------|
| Git projection drift | `GET /api/v1/admin/storage/git-projection-drift` | No missing transclusions in Git HEAD |
| Visibility drift | `GET /api/v1/admin/authz/visibility-drift` | Empty drift list |
| Document links drift | `GET /api/v1/admin/authz/document-links-drift` | Empty drift list |

Functional smoke: open a known document id/title, search for its title, confirm approval state and comments.

Prometheus/metrics: search the codebase for `git-projection-drift`, `visibility-drift`, and `document-links-drift` admin routes if you wire SLO alerts.
