# Privacy — view counts & home activity

## Document view counts

Socle records **aggregated** document views only:

| What | Detail |
|------|--------|
| Storage | `document_view_counts(document_id, day, count)` |
| User tracking | **None** — there is no `user_id` (or equivalent) column |
| Increment | `POST /api/v1/documents/{id}/view` when the caller can view the document |
| KPI use | Home `viewsThisMonth` sums counts for the current calendar month over the SQL readability preselection (`visibility = organisation` OR `S_view` / `S_owner` / `D_direct` / `F_view`) — **no** OpenFGA BatchCheck on KPIs. Only numbers are exposed; column/OpenFGA drift is caught by `visibility-drift` |
| Retention | **13 months** of daily aggregates; older rows are deleted by a daily scheduler |
| Future | A dedicated Analytics screen may surface the same aggregates (still without individual user trails) |

Recording a view never stores who viewed the page — only that *someone with access* opened it on a given UTC day.

## Home dashboard (`GET /api/v1/home`)

| Surface | Authz path |
|---------|------------|
| KPIs (published, views, average reliability) | SQL aggregates on `AuthorizationService.READABLE_PREDICATE` only — **no** BatchCheck. Numbers only; drift vs OpenFGA → `GET …/visibility-drift` |
| Lists (Resume, Recently published, Team activity) | Same SQL preselection with oversized `LIMIT` (3× displayed rows), then OpenFGA `viewer` BatchCheck on those candidates only. Denied documents never appear (title, id, or metadata) |
| Pending approvals | Unchanged (`/approvals/mine` logic) |

Retention purge of `activity_events` is **scheduler-only** — not on the home read path.

## Activity feed (`activity_events`)

The home team-activity strip stores short-lived events (comment, edit proposal, approval submission, publication). Retention is **90 days**. Events on documents the viewer cannot access are omitted from `GET /api/v1/home` (OpenFGA BatchCheck on candidates), not deleted from the table.

## Favorites

Favorites are stored per user (`favorites`). Listing filters out targets the user can no longer view; inaccessible rows are kept until the user removes them explicitly.
