# Upgrade

## Before you upgrade

1. Read the [GitHub Release notes](https://github.com/tarek-fezai/socle/releases) for the target version (breaking changes, migrations, IdP notes).
2. Take a full backup per [`backup-restore.md`](backup-restore.md).
3. Note current image digests/tags and Helm values.

## Application upgrade

### Compose / GHCR

1. Set `VERSION=<semver>` in `deploy/compose/.env` (no leading `v`).
2. Pull or build images: `docker compose pull` or `docker compose up -d --build`.
3. Flyway runs automatically on backend start — never apply SQL by hand except emergency DBA procedures.
4. Rolling order: Postgres/OpenFGA/Temporal (if image bumps) → backend → frontend → webhook-worker.

### Helm

```bash
helm upgrade socle deploy/helm/socle -n socle -f values.yaml --set backend.image.tag=<semver>
```

Use `readiness` probes; increase `minAvailable` on PDB during node drains.

## OpenFGA model

Authorization model updates ship in the repo (`deploy/compose/openfga/model.fga`, `infra/openfga/`). On upgrade:

- `openfga-init` or your runbook publishes a new model version when `OPENFGA_MODEL_ID` is empty.
- If you pin `OPENFGA_MODEL_ID`, update it after publishing the new model to the store.
- Run authz tests in CI (`authz-model` job) mirror what you should run after model changes.

Visibility one-shot migration: `POST /api/v1/admin/authz/migrate-visibility` (or `SOCLE_FGA_VISIBILITY_MIGRATION_ON_STARTUP=true` once).

## Temporal workflows

Document approval uses Temporal (`DocumentApprovalWorkflow`). Upgrades must keep **workflow code compatible** with histories already in `temporal` DB:

- Use Temporal workflow versioning (`Workflow.getVersion` / patch markers) when changing workflow logic; release notes call out required minimum server/worker versions.
- Do not downgrade Temporal server below the version that wrote existing histories.
- After upgrade, spot-check in-flight approvals in Temporal UI and complete a test approval in staging.

## Post-upgrade checks

- `GET /actuator/health` → `UP`
- Login + create/edit document (git and relational smoke paths)
- Drift endpoints at zero (see backup-restore)
- Webhook worker `/healthz`

## Rollback

1. Stop traffic to new backend.
2. Restore Postgres + Git from pre-upgrade backup if Flyway migrated forward (schema rollback is **not** automatic — prefer restore over `flyway undo` unless documented for that release).
3. Redeploy previous image tag/digest.
4. If OpenFGA model was advanced, restore `openfga` DB or re-pin previous `OPENFGA_MODEL_ID` consistent with restored tuples.

Keep at least one previous image tag available in your registry (see [`deploy/README.md`](../../deploy/README.md)).
