// SPDX-License-Identifier: AGPL-3.0-or-later
export type NotificationItem = {
  id: string
  type: string
  payload: Record<string, unknown>
  documentTitle: string | null
  readAt: string | null
  createdAt: string
}

export type NotificationPage = {
  items: NotificationItem[]
  offset: number
  limit: number
  total: number
  unreadCount: number
}

export async function listNotifications(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  opts: { unreadOnly?: boolean; offset?: number; limit?: number } = {},
) {
  const params = new URLSearchParams()
  if (opts.unreadOnly) params.set('unreadOnly', 'true')
  if (opts.offset != null) params.set('offset', String(opts.offset))
  if (opts.limit != null) params.set('limit', String(opts.limit))
  const qs = params.toString()
  const { data } = await api.get<NotificationPage>(
    `/api/v1/notifications${qs ? `?${qs}` : ''}`,
  )
  return data
}

export async function markNotificationRead(
  api: { post: <T>(url: string, body?: unknown) => Promise<{ data: T }> },
  id: string,
) {
  const { data } = await api.post<NotificationItem>(`/api/v1/notifications/${id}/read`)
  return data
}

/** Texte humain — seuls les types réellement produits côté backend. */
export function formatNotificationMessage(n: NotificationItem): string {
  if (n.type === 'approval_chain_exhausted') {
    const title = n.documentTitle?.trim() || 'un document'
    return `La demande d'approbation pour « ${title} » est restée sans décision après plusieurs étapes.`
  }
  if (n.type === 'external_reference_first') {
    const msg = n.payload?.message
    if (typeof msg === 'string' && msg.trim()) return msg
    const source = String(n.payload?.source_space_name ?? 'un autre espace')
    return `Première référence externe depuis « ${source} » vers votre espace.`
  }
  const msg = n.payload?.message
  if (typeof msg === 'string' && msg.trim()) return msg
  return `Notification (${n.type})`
}

export function notificationResourceLink(n: NotificationItem): string | null {
  const docId = n.payload?.document_id
  if (typeof docId === 'string' && docId) {
    return `/docs/${docId}`
  }
  if (n.type === 'external_reference_first') {
    const spaceId = n.payload?.target_space_id
    if (typeof spaceId === 'string' && spaceId) {
      return `/spaces/${spaceId}`
    }
  }
  if (n.type === 'approval_chain_exhausted') {
    return '/approvals'
  }
  return null
}
