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

## Embedded polls

TipTap `poll` nodes store a stable `id` in the document body. Votes live in `poll_votes(poll_id, user_id, option)` (one row per user, last vote wins while open).

| What | Detail |
|------|--------|
| Vote | `PUT /api/v1/polls/{id}/vote` — document **viewer**; allowed during an in-progress approval (not a content mutation) |
| Close | `POST /api/v1/polls/{id}/close` — document **editor** |
| Read | `GET /api/v1/polls/{id}` → question, options, `myVote`, **aggregated** `results` (counts per option) for every viewer |
| Individual votes | Never exposed — no nominative list, same rule as page feedback |
| GDPR export | `GET /api/v1/me/export` includes the caller's own `pollVotes` only |
| Lifecycle | Removing the node archives the poll (`archived_at`); votes kept while the document exists; purge with the document (`ON DELETE CASCADE`) |

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

## Retention, legal hold and data residence (system administrators)

`GET/PUT /api/v1/admin/retention` (role `administrateur-systeme`, every change audited as `retention.settings_updated`) edits the instance policy stored in `instance_settings` (migration V43):

| Setting | Default | Effect of the daily purge (`RetentionPurgeScheduler`, 03:30) |
|---|---|---|
| `auditRetentionMonths` | 24 | `audit_log_events` older than the window are deleted (only path allowed to delete: a DB trigger accepts `DELETE` only inside the purge transaction and only for rows older than the window) |
| `versionRetentionMode` / `versionRetentionValue` | `unlimited` | `months` / `count`: old `document_versions` rows are removed (documents under hold or with a pending approval are skipped) |
| `archivedDocsRetentionYears` | 7 | archived documents (and fully archived spaces) untouched for longer are hard-deleted, attachments and Git history included (`docs/git-purge.md`) |
| `processingRegisterReviewedAt` | — | date the processing register was last reviewed (`retention.processing_register_reviewed`) |

Each run is idempotent and audited as `retention.purge_ran` with counters (`auditEventsDeleted`, `versionsDeleted`, `archivedDocumentsPurged`, `archivedSpacesPurged`, `skippedLegalHold`, `failures`). The data residence label shown in the admin UI comes from `SOCLE_DATA_RESIDENCE_LABEL` (read-only).

**Legal hold** (`/api/v1/admin/legal-holds`, reason mandatory to place *and* release, audited as `legal_hold.placed` / `legal_hold.released`) freezes a document or a whole space. While a hold covers a document (its own hold or its space's), every destructive path answers `409 legal_hold_active`: moving it or its folder/space to the trash, manual and automatic trash purge, retention purge, attachment purge, and GDPR erasure/anonymisation of an author's comments or drafts on it. One active hold per scope; released holds are kept as history.
