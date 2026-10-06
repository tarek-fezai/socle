// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { apiErrorCode, apiErrorStatus } from './apiError'
import { fetchVersionCompare, type VersionCompare } from './documents'

export { apiErrorCode, apiErrorDetail, apiErrorMessage, apiErrorStatus, apiProblem } from './apiError'

/** Lien entrant visible (filtré côté serveur : jamais de titre illisible). */
export type ImpactedLink = { id: string; title: string }

/**
 * Raisons pour lesquelles l'utilisateur ne peut pas décider
 * (`GET /api/v1/approvals/{requestId}` → `cannotDecideReason`).
 */
export type CannotDecideReason =
  | 'requester'
  | 'contributor'
  | 'not_current_step_approver'
  | 'resolved'

/**
 * `GET /api/v1/approvals/mine` (`ApprovalView`).
 * La file « mine » n'inclut plus les liens impactés (tableaux vides / `hiddenImpactedCount: 0`) ;
 * le détail enrichi vient de `GET /api/v1/approvals/{requestId}`.
 * `baselineVersionNo` = `submitted_version_no` de la dernière demande APPROUVÉE (null si jamais approuvé).
 */
export type ApprovalItem = {
  approvalRequestId: string
  documentId: string
  documentTitle: string
  temporalWorkflowId: string
  status: string
  currentStepOrder: number
  slaDeadlineAt: string | null
  submittedVersionNo: number | null
  baselineVersionNo: number | null
  requestedBy: string
  createdAt: string
  requestedByDisplayName?: string | null
  requestedByInitials?: string | null
  impactedLinks?: ImpactedLink[] | null
  /** Documents liés non accessibles (toujours 0 sur `/mine`). */
  hiddenImpactedCount?: number
}

/**
 * `GET /api/v1/approvals/{requestId}` (`ApprovalDetailView`) —
 * champs `ApprovalView` + capacité de décision + liens entrants filtrés.
 */
export type ApprovalDetail = ApprovalItem & {
  canDecide: boolean
  cannotDecideReason: CannotDecideReason | null
}

export type { VersionCompare }

/** Étape du circuit applicable (`GET /documents/{id}/approvals/applicable-workflow`). */
export type ApplicableStep = {
  id?: string
  stepOrder: number
  slaHours?: number | null
  approverRoleName?: string | null
  escalatesToStepOrder?: number | null
}

export type ApplicableWorkflow = {
  id?: string
  name?: string
  stepCount?: number
  steps?: ApplicableStep[] | null
}

export type ApprovalConflictError = 'already_resolved' | 'step_advanced'

/** Message affiché quand le serveur (ou le client) refuse un refus sans justification. */
export const REJECT_JUSTIFICATION_REQUIRED = 'Justification obligatoire pour un refus'

export async function fetchApplicableWorkflow(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
): Promise<ApplicableWorkflow> {
  const { data } = await api.get<ApplicableWorkflow>(
    `/api/v1/documents/${documentId}/approvals/applicable-workflow`,
  )
  return data
}

export async function listMyApprovals(api: {
  get: <T>(url: string) => Promise<{ data: T }>
}) {
  const { data } = await api.get<ApprovalItem[]>('/api/v1/approvals/mine')
  return data
}

/** Détail lecture/décision (`GET /api/v1/approvals/{requestId}`). */
export async function fetchApprovalDetail(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  requestId: string,
): Promise<ApprovalDetail> {
  const { data } = await api.get<ApprovalDetail>(`/api/v1/approvals/${requestId}`)
  return data
}

/** Message français pour un mode lecture seule. */
export function cannotDecideMessage(
  reason: CannotDecideReason | null | undefined,
  currentStepOrder?: number | null,
): string {
  switch (reason) {
    case 'requester':
      return 'Vous avez demandé cette approbation'
    case 'contributor':
      return 'Vous êtes contributeur de cette révision'
    case 'not_current_step_approver': {
      const n = currentStepOrder != null && currentStepOrder > 0 ? currentStepOrder : null
      return n != null ? `En attente de l'étape N${n}` : "En attente de l'étape en cours"
    }
    case 'resolved':
      return 'Cette demande est déjà résolue'
    default:
      return 'Vous ne pouvez pas décider sur cette demande'
  }
}

export async function decideApproval(
  api: { post: <T>(url: string, body: unknown) => Promise<{ data: T }> },
  documentId: string,
  requestId: string,
  decision: 'approuve' | 'rejete',
  expectedStepOrder: number,
  comment?: string | null,
) {
  const { data } = await api.post<{
    approvalRequestId: string
    temporalWorkflowId: string
    status: string
  }>(`/api/v1/documents/${documentId}/approvals/${requestId}/decide`, {
    decision,
    comment: comment ?? null,
    expectedStepOrder,
  })
  return data
}

/** Comparaison ligne à ligne (`?mode=lines`) entre la dernière version approuvée et la révision soumise. */
export async function fetchApprovalCompare(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
  fromVersion: number,
  toVersion: number,
): Promise<VersionCompare> {
  return fetchVersionCompare(api, documentId, fromVersion, toVersion)
}

export function formatSlaCountdown(deadlineIso: string | null, nowMs = Date.now()): string {
  if (!deadlineIso) return 'SLA non défini'
  const ms = new Date(deadlineIso).getTime() - nowMs
  if (Number.isNaN(ms)) return 'SLA invalide'
  const abs = Math.abs(ms)
  const hours = Math.floor(abs / 3_600_000)
  const minutes = Math.floor((abs % 3_600_000) / 60_000)
  const label = hours > 0 ? `${hours} h ${minutes} min` : `${minutes} min`
  if (ms < 0) return `Échu depuis ${label}`
  return `Reste ${label}`
}

const HOUR_MS = 3_600_000
const MINUTE_MS = 60_000

/** Durée courte : « 22h » ou « 35 min » (arrondi à l'unité inférieure). */
function shortDuration(absMs: number): string {
  const hours = Math.floor(absMs / HOUR_MS)
  if (hours >= 1) return `${hours}h`
  return `${Math.floor(absMs / MINUTE_MS)} min`
}

/** Rail desktop : « Dans 22h · SLA 48h » (SLA total = échéance − création). */
export function formatSlaRail(
  deadlineIso: string | null,
  createdAtIso: string | null,
  nowMs = Date.now(),
): string {
  if (!deadlineIso) return 'SLA non défini'
  const deadline = new Date(deadlineIso).getTime()
  if (Number.isNaN(deadline)) return 'SLA invalide'
  const ms = deadline - nowMs
  const head = ms < 0 ? `Échu depuis ${shortDuration(-ms)}` : `Dans ${shortDuration(ms)}`
  const created = createdAtIso ? new Date(createdAtIso).getTime() : NaN
  const totalH = Number.isNaN(created) ? 0 : Math.round((deadline - created) / HOUR_MS)
  return totalH > 0 ? `${head} · SLA ${totalH}h` : head
}

/** Mobile : « SLA 22h restantes » / « SLA échu depuis 3h ». */
export function formatSlaRemaining(deadlineIso: string | null, nowMs = Date.now()): string {
  if (!deadlineIso) return 'SLA non défini'
  const ms = new Date(deadlineIso).getTime() - nowMs
  if (Number.isNaN(ms)) return 'SLA invalide'
  return ms < 0 ? `SLA échu depuis ${shortDuration(-ms)}` : `SLA ${shortDuration(ms)} restantes`
}

export function approvalConflictCode(error: unknown): ApprovalConflictError | null {
  if (apiErrorStatus(error) !== 409) return null
  const code = apiErrorCode(error)
  if (code === 'step_advanced' || code === 'already_resolved') return code
  return null
}

// apiErrorMessage / apiErrorStatus réexportés depuis ./apiError (helper unique problem+json).
