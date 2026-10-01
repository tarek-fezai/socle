# Contributing to Socle

## Developer Certificate of Origin (DCO)

Every commit **must** be signed off (DCO). Use:

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

## Flyway

**Never modify a migration that has already been merged** (`backend/src/main/resources/db/migration/V*.sql`). Add a new `V{n}__….sql` instead. Existing checksums must remain stable.
