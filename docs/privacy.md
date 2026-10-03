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

## Page feedback ("Was this page helpful?")

Readers can vote yes/no once per document (`document_feedback(document_id, user_id, helpful, updated_at)`, one row per user, last vote wins).

| What | Detail |
|------|--------|
| Write | `PUT /api/v1/documents/{id}/feedback` `{ "helpful": true \| false }` — caller must be able to view the document (otherwise 404, existence not revealed) |
| Read | `GET /api/v1/documents/{id}/feedback` → `{ "myVote": true \| false \| null, "totals"?: { "yes", "no" } }` |
| Own vote | Each user only ever sees **their own** vote |
| Totals | `totals` (aggregate yes/no counts) is returned **only to document editors** (OpenFGA `editor` on the document). For everyone else the field is omitted |
| Individual votes | Never exposed through any API — no list of who voted what, not even to editors |
| Deletion | Votes are deleted with the document or the user (`ON DELETE CASCADE`) |

## Read attestations

Attestation campaigns (`attestation_campaigns`, `attestation_acknowledgments`) are a compliance record, so they are **nominative by design**:

| Surface | Who |
|---------|-----|
| Own status + `X/Y` counts + due date (`GET …/attestations/active`) | Users in the campaign audience only (counts are aggregates) |
| Nominative list of acknowledgments (`GET …/attestations/{campaignId}/acknowledgments`) | **Space owners** of the document's space only |
| Create / close campaign | Space owners only |

Campaign creation, closure and each acknowledgment are written to the audit log (`attestation.campaign_created`, `attestation.campaign_closed`, `attestation.acknowledged`). The audience size (`audience_size`, the "Y" in `X/Y`) is frozen when the campaign is created.

## Related documents (page rail)

`GET /api/v1/documents/{id}/links` lists outgoing and incoming document links. Candidates are read from `document_links` (bounded to 50 per direction) and then filtered with one OpenFGA `viewer` BatchCheck: a document the caller cannot view never appears (no id, no title).

## Favorites

Favorites are stored per user (`favorites`). Listing filters out targets the user can no longer view; inaccessible rows are kept until the user removes them explicitly.
