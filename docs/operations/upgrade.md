# Upgrade

## Before you upgrade

1. Read the [GitHub Release notes](https://github.com/tarek-fezai/socle/releases) for the target version (breaking changes, migrations, IdP notes).
2. Take a full backup per [`backup-restore.md`](backup-restore.md).
3. Note current image digests/tags and Helm values.

## Application upgrade

### Compose / GHCR

1. Set `VERSION=<semver>` in `deploy/compose/.env` (no leading `v`).
2. Flyway runs automatically on backend start — never apply SQL by hand except emergency DBA procedures.

**Cette version (team-roles-v1) — arrêt complet, pas de rolling :**

1. Sauvegarde : `pg_dump` de la base Socle (`socle_core`) et de la base OpenFGA, plus copie du volume Git des documents (voir [`backup-restore.md`](backup-restore.md)).
2. `docker compose stop backend webhook-worker frontend` (le worker Compose s’appelle `webhook-worker`).
3. Mise à jour des images (`docker compose pull` ou `docker compose build`).
4. Démarrer Postgres / OpenFGA / Temporal s’ils étaient arrêtés ; attendre qu’ils soient healthy.
5. Démarrer le **backend seul**. Attendre dans les logs `team-roles-v1` (rapport `errors=0` / leftover 0, ou « déjà appliquée — skip ») puis `GET /actuator/health` → `UP`.
6. Démarrer le worker (`webhook-worker`) puis le frontend.

Un ordre **progressif** (Postgres/OpenFGA/Temporal → backend → frontend → worker) reste valable pour les versions **sans** migration d’autorisation — les notes de version le diront explicitement.

### Helm

```bash
helm upgrade socle deploy/helm/socle -n socle -f values.yaml \
  --set backend.image.tag=<semver> \
  --set backend.replicas=1 \
  --set backend.strategy.type=Recreate
```

Pour **cette version** : `backend.replicas=1` pendant la mise à jour (déjà imposé en mode git) et stratégie **`Recreate`** — une instance ancienne ne doit pas rester en ligne pendant `team-roles-v1`. Après succès, vous pouvez rétablir `RollingUpdate` et le nombre de réplicas habituel (mode relationnel uniquement).

Use `readiness` probes; increase `minAvailable` on PDB during node drains **uniquement** sur les versions sans migration d’autorisation.

## OpenFGA model

Authorization model updates ship in the repo (`deploy/compose/openfga/model.fga`, `infra/openfga/`). On upgrade:

- `openfga-init` or your runbook publishes a new model version when `OPENFGA_MODEL_ID` is empty.
- If you pin `OPENFGA_MODEL_ID`, update it after publishing the new model to the store.
- Run authz tests in CI (`authz-model` job) mirror what you should run after model changes.

> **Mise à jour vers cette version : arrêt COMPLET des instances backend
> (et du worker) avant de démarrer la nouvelle version. Pas de mise à jour
> progressive : une instance ancienne pourrait écrire des droits `group#member`
> pendant la migration.**

- **team-roles-v1** (rôles d’équipe) : au premier démarrage de cette version le
  backend lit et réécrit les tuples sous le modèle précédent
  (`openfga/model-pre-team-roles.json`, sujets `group#member` encore valides),
  puis publie le modèle courant (`openfga/model.json`, ACL sans `group#member`).
  Échec (erreurs FGA ou tuples legacy restants) → démarrage refusé, non marquée
  dans `authz_migrations`, rejouable. Ne pas épingler `OPENFGA_MODEL_ID` sur
  l’ancien modèle après succès.

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

Retour arrière de **cette version** (team-roles-v1) : restaurer les sauvegardes prises avant la mise à jour, puis redéployer l’image précédente.

1. Arrêter backend, worker et frontend (`docker compose stop backend webhook-worker frontend`, ou scale Helm à 0).
2. Restaurer `pg_dump` Socle (`socle_core`) **et** OpenFGA, plus le volume Git, depuis le dossier de sauvegarde pré-mise à jour (voir [`backup-restore.md`](backup-restore.md)). Le schéma Flyway ne se « défait » pas tout seul — préfèrez la restauration au `flyway undo`.
3. Redéployer le tag/digest d’image **précédent** (backend, frontend, worker).
4. Aligner `OPENFGA_STORE_ID` / `OPENFGA_MODEL_ID` sur la base OpenFGA restaurée (ne pas republier le modèle courant sur d’anciens tuples).

Keep at least one previous image tag available in your registry (see [`deploy/README.md`](../../deploy/README.md)).
