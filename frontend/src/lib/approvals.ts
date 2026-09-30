import { fetchVersionDiff, type VersionDiff } from './documents'

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
}

export type { VersionDiff }

export type ApprovalConflictError = 'already_resolved' | 'step_advanced'

export async function listMyApprovals(api: {
  get: <T>(url: string) => Promise<{ data: T }>
}) {
  const { data } = await api.get<ApprovalItem[]>('/api/v1/approvals/mine')
  return data
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

export async function fetchApprovalDiff(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  documentId: string,
  fromVersion: number,
  toVersion: number,
): Promise<VersionDiff> {
  return fetchVersionDiff(api, documentId, fromVersion, toVersion)
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

function responseData(error: unknown): {
  status?: number
  data?: { error?: string; message?: string; detail?: string }
} | undefined {
  if (error && typeof error === 'object' && 'response' in error) {
    return (
      error as {
        response?: { status?: number; data?: { error?: string; message?: string; detail?: string } }
      }
    ).response
  }
  return undefined
}

export function approvalConflictCode(error: unknown): ApprovalConflictError | null {
  const response = responseData(error)
  if (response?.status !== 409) return null
  const code = response.data?.error
  if (code === 'step_advanced' || code === 'already_resolved') return code
  return null
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  const response = responseData(error)
  const status = response?.status
  const code = response?.data?.error
  if (status === 409 && code === 'step_advanced') {
    return 'Cette demande a été escaladée entretemps — l’étape a changé. Rechargez avant de décider.'
  }
  if (status === 409 && code === 'already_resolved') {
    return 'Cette demande a déjà été traitée.'
  }
  if (status === 409) {
    return response?.data?.message ?? response?.data?.detail ?? 'Conflit — opération refusée.'
  }
  if (status === 403) return 'Accès refusé pour cette action.'
  if (status === 404) return 'Ressource introuvable.'
  const detail = response?.data?.message ?? response?.data?.detail
  if (detail) return detail
  return fallback
}
