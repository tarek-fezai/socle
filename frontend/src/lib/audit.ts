// SPDX-License-Identifier: AGPL-3.0-or-later
export type AuditEvent = {
  id: number
  actorId: string | null
  actorIsSystem: boolean
  actorDisplayName: string | null
  actorEmail: string | null
  action: string
  resourceType: string
  resourceId: string | null
  metadata: string | null
  ipAddress: string | null
  createdAt: string
}

export type AuditPage = {
  items: AuditEvent[]
  offset: number
  limit: number
  total: number
}

export type AuditFilters = {
  resourceType?: string
  resourceId?: string
  actorId?: string
  action?: string
  since?: string
  until?: string
  offset?: number
  limit?: number
}

export async function listAuditEvents(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  filters: AuditFilters = {},
) {
  const params = new URLSearchParams()
  if (filters.resourceType) params.set('resourceType', filters.resourceType)
  if (filters.resourceId) params.set('resourceId', filters.resourceId)
  if (filters.actorId) params.set('actorId', filters.actorId)
  if (filters.action) params.set('action', filters.action)
  if (filters.since) params.set('since', filters.since)
  if (filters.until) params.set('until', filters.until)
  params.set('offset', String(filters.offset ?? 0))
  params.set('limit', String(filters.limit ?? 50))
  const { data } = await api.get<AuditPage>(`/api/v1/audit?${params.toString()}`)
  return data
}

export function actorLabel(e: AuditEvent): string {
  if (e.actorIsSystem) return 'Système'
  if (e.actorDisplayName?.trim()) return e.actorDisplayName
  if (e.actorEmail?.trim()) return e.actorEmail
  if (e.actorId) return e.actorId.slice(0, 8) + '…'
  return '—'
}

export function resourceLink(e: AuditEvent): string | null {
  if (!e.resourceId) return null
  if (e.resourceType === 'document') {
    if (e.action.includes('version') || e.action.includes('restored')) {
      return `/docs/${e.resourceId}/history`
    }
    return `/docs/${e.resourceId}`
  }
  if (e.resourceType === 'space') return `/spaces/${e.resourceId}/access`
  return null
}

function parseMeta(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null
  try {
    const v = JSON.parse(raw) as unknown
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/** Résumé humain du metadata — types courants seulement. */
export function formatAuditMetadataSummary(e: AuditEvent): string | null {
  const meta = parseMeta(e.metadata)
  if (!meta) return null

  if (e.action === 'access.granted' || e.action === 'access.revoked') {
    const relation = String(meta.relation ?? '?')
    const subject =
      (typeof meta.subjectId === 'string' && meta.subjectId) ||
      (typeof meta.subject === 'string' && meta.subject) ||
      '?'
    const verb = e.action === 'access.granted' ? 'Accordé' : 'Révoqué'
    return `${verb} ${relation} → ${subject}`
  }

  if (e.action === 'document.updated') {
    const before = meta.status_before
    const after = meta.status_after
    if (before != null && after != null && before !== after) {
      return `Statut ${String(before)} → ${String(after)}`
    }
    if (typeof meta.title === 'string') return `Titre : ${meta.title}`
  }

  if (e.action === 'document.version_restored') {
    const from = meta.restoredFromVersion ?? meta.versionNo
    if (from != null) return `Restauration depuis v${String(from)}`
  }

  if (e.action === 'approval.escalated') {
    return `Escalade étape ${String(meta.fromStepOrder ?? '?')} → ${String(meta.toStepOrder ?? '?')}`
  }

  return null
}

export function exportAuditCsv(items: AuditEvent[]): string {
  const header = ['createdAt', 'actor', 'action', 'resourceType', 'resourceId', 'summary', 'metadata']
  const rows = items.map((e) =>
    [
      e.createdAt,
      actorLabel(e),
      e.action,
      e.resourceType,
      e.resourceId ?? '',
      formatAuditMetadataSummary(e) ?? '',
      e.metadata ?? '',
    ]
      .map(csvEscape)
      .join(','),
  )
  return [header.join(','), ...rows].join('\n')
}

export function exportAuditJson(items: AuditEvent[]): string {
  return JSON.stringify(items, null, 2)
}

function csvEscape(value: string): string {
  if (/[",\n]/.test(value)) return `"${value.replace(/"/g, '""')}"`
  return value
}
