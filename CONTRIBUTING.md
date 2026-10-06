# Contributing to Socle

## Contributions externes

Les contributions externes (code, documentation, patches) **ne sont pas acceptées** sans
**accord écrit préalable** du titulaire. Contactez le mainteneur avant d'ouvrir une PR si vous
disposez d'un tel accord.

## Developer Certificate of Origin (DCO)

When an agreed contribution is accepted, every commit **must** be signed off (DCO). Use:

```bash
git commit -s
```

This appends a `Signed-off-by: Name <email>` line. Unsigned commits will be rejected.

By signing off, you certify the [Developer Certificate of Origin](https://developercertificate.org/) (version 1.1).

## Workflow

1. One branch per change set (feature / fix / chore).
2. Open a pull request targeting **`main`**.
3. CI must be green before merge (`backend`, `frontend`, `worker`).
4. Do not push directly to `main`.
5. **No out-of-scope commits in a PR.** Keep the branch limited to the stated change set.
6. **After a review report**, any new commit must be called out explicitly in the PR (or review thread) before merge — do not land silent follow-ups.

## Tests

| Area | Command |
|------|---------|
| Backend | `mvn -B verify` (from `backend/`) |
| Frontend | `pnpm test` (from `frontend/`) |
| Webhook worker | `go test ./...` (from `webhook-worker/`) |

Backend integration tests use **Testcontainers** (Postgres 16, and OpenFGA in-memory for
`AuthorizationServiceOpenFgaTest`) when Docker is available. The authorization **model**
is also tested without Java via `fga model test` (CI job `authz-model`).

## Temporal workflows

Any change to a `*WorkflowImpl` class must either:

- go through `Workflow.getVersion` (compatible replay), or
- be moved into an **activity** (deterministic workflow code stays unchanged).

Do not change workflow history shape without a version gate.

Versions SDK / serveur / UI : `docs/temporal-versions.md`. Le test
`DocumentApprovalWorkflowReplayTest` rejoue un historique capturé sous 1.27.x.

## Flyway

**Never modify a migration that has already been merged** (`backend/src/main/resources/db/migration/V*.sql`). Add a new `V{n}__….sql` instead. Existing checksums must remain stable.
