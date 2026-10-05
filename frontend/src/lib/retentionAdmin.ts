// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type VersionRetentionMode = 'unlimited' | 'months' | 'count'

export type RetentionSettingsView = {
  auditRetentionMonths: number
  versionRetentionMode: VersionRetentionMode
  versionRetentionValue: number | null
  archivedDocsRetentionYears: number
  processingRegisterReviewedAt: string | null
  dataResidenceLabel: string | null
  activeLegalHolds: number
  lastPurgeRanAt: string | null
  updatedAt: string | null
}

export type UpdateRetentionRequest = {
  auditRetentionMonths: number
  versionRetentionMode: VersionRetentionMode
  versionRetentionValue: number | null
  archivedDocsRetentionYears: number
  processingRegisterReviewedAt: string | null
}

export type LegalHoldScopeType = 'document' | 'space'

export type LegalHoldView = {
  id: string
  scopeType: LegalHoldScopeType
  scopeId: string
  scopeLabel: string | null
  reason: string
  createdBy: string | null
  createdByDisplayName: string | null
  createdAt: string
  releasedBy: string | null
  releasedByDisplayName: string | null
  releasedAt: string | null
  releaseReason: string | null
  active: boolean
}

export type LegalHoldListResponse = {
  holds: LegalHoldView[]
  activeCount: number
}

export type PlaceLegalHoldRequest = {
  scopeType: LegalHoldScopeType
  scopeId: string
  reason: string
}

export function retentionAdminKey() {
  return ['admin', 'retention'] as const
}

export function legalHoldsKey(activeOnly = false) {
  return ['admin', 'legal-holds', { activeOnly }] as const
}

export async function getRetentionSettings(api: Pick<AxiosInstance, 'get'>) {
  const { data } = await api.get<RetentionSettingsView>('/api/v1/admin/retention')
  return data
}

export async function updateRetentionSettings(
  api: Pick<AxiosInstance, 'put'>,
  body: UpdateRetentionRequest,
) {
  const { data } = await api.put<RetentionSettingsView>('/api/v1/admin/retention', body)
  return data
}

export async function listLegalHolds(
  api: Pick<AxiosInstance, 'get'>,
  activeOnly = false,
) {
  const { data } = await api.get<LegalHoldListResponse>('/api/v1/admin/legal-holds', {
    params: { activeOnly },
  })
  return data
}

export async function placeLegalHold(
  api: Pick<AxiosInstance, 'post'>,
  body: PlaceLegalHoldRequest,
) {
  const { data } = await api.post<LegalHoldView>('/api/v1/admin/legal-holds', body)
  return data
}

export async function releaseLegalHold(
  api: Pick<AxiosInstance, 'post'>,
  id: string,
  reason: string,
) {
  const { data } = await api.post<LegalHoldView>(`/api/v1/admin/legal-holds/${id}/release`, {
    reason,
  })
  return data
}

export function versionRetentionLabel(
  mode: VersionRetentionMode,
  value: number | null,
): string {
  if (mode === 'unlimited') return 'Illimitée'
  if (mode === 'months') return `${value ?? 0} mois`
  return `${value ?? 0} versions`
}

export function formatRetentionReviewDate(isoDate: string | null): string {
  if (!isoDate) return 'Non renseignée'
  const d = new Date(`${isoDate}T12:00:00`)
  if (Number.isNaN(d.getTime())) return isoDate
  return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
}
