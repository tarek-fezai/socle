# OpenAPI contract

Source of truth for the HTTP API surface (springdoc).

## Regenerate

From `backend/` (Docker required for Testcontainers):

```bash
mvn -B -Popenapi-export test
# or: mvn -B -Dtest=OpenApiExportIT test
```

Writes `openapi/openapi.json`.

Then from `frontend/`:

```bash
pnpm gen:api
```

Writes `frontend/src/lib/api-types.ts`.

## CI

Job `api-contract` regenerates both artifacts and fails on git diff (stale types / schema).

## Runtime exposure

- Document: `GET /api-docs` (JSON), UI at `/ApiDocs.dc.html`
- Gated by `ROLE_administrateur-systeme`
- Disable entirely with `SOCLE_OPENAPI_ENABLED=false` (recommended in production)
