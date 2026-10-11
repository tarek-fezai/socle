// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
export type NotificationActor = {
  id: string
  displayName: string
  initials: string
}

export type NotificationItem = {
  id: string
  type: string
  payload: Record<string, unknown>
  documentTitle: string | null
  versionNo?: number | null
  actor?: NotificationActor | null
  readAt: string | null
  createdAt: string
}

export type MessagePart = { text: string; emphasis?: boolean }

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

export async function markAllNotificationsRead(
  api: { post: <T>(url: string, body?: unknown) => Promise<{ data: T }> },
) {
  const { data } = await api.post<{ updated: number }>('/api/v1/notifications/read-all')
  return data
}

/** Texte humain — composés des champs structurés (acteur, titre, version). Pas de HTML. */
export function notificationMessageParts(n: NotificationItem): MessagePart[] {
  const actor = n.actor?.displayName?.trim()
  const title = n.documentTitle?.trim()
  const version = n.versionNo != null ? `v${n.versionNo}` : null
  if (n.type === 'comment_mention') {
    const parts: MessagePart[] = []
    if (actor) {
      parts.push({ text: actor, emphasis: true })
      parts.push({ text: ' vous a mentionné dans ' })
    } else {
      parts.push({ text: 'Vous avez été mentionné dans ' })
    }
    parts.push({ text: title || 'un commentaire', emphasis: Boolean(title) })
    if (version) parts.push({ text: ` — ${version}` })
    return parts
  }
  if (n.type === 'approval_chain_exhausted') {
    const parts: MessagePart[] = [
      { text: "La demande d'approbation pour " },
      { text: title || 'un document', emphasis: Boolean(title) },
      { text: ' est restée sans décision après plusieurs étapes.' },
    ]
    if (version) parts.splice(2, 0, { text: ` (${version})` })
    return parts
  }
  if (n.type === 'external_reference_first') {
    const msg = n.payload?.message
    if (typeof msg === 'string' && msg.trim()) return [{ text: msg.trim() }]
    const source = String(n.payload?.source_space_name ?? 'un autre espace')
    return [
      { text: 'Première référence externe depuis « ' },
      { text: source, emphasis: true },
      { text: ' » vers votre espace.' },
    ]
  }
  if (n.type === 'pat_expiring') {
    const name = String(n.payload?.name ?? '').trim() || 'sans nom'
    const last4 = String(n.payload?.last4 ?? '')
    const raw = n.payload?.expires_at
    const at = typeof raw === 'string' ? Date.parse(raw) : NaN
    const date = Number.isNaN(at)
      ? 'dans 7 jours'
      : `le ${new Date(at).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' })}`
    return [
      { text: 'Votre jeton d’accès personnel « ' },
      { text: name, emphasis: true },
      { text: ` » (…${last4}) expire ${date}.` },
    ]
  }
  const msg = n.payload?.message
  if (typeof msg === 'string' && msg.trim()) return [{ text: msg.trim() }]
  if (actor && title) {
    return [{ text: actor, emphasis: true }, { text: ' — ' }, { text: title, emphasis: true }]
  }
  return [{ text: `Notification (${n.type})` }]
}

export function formatNotificationMessage(n: NotificationItem): string {
  return notificationMessageParts(n)
    .map((p) => p.text)
    .join('')
}

export function notificationResourceLink(n: NotificationItem): string | null {
  const approvalRequestId = n.payload?.approval_request_id
  if (typeof approvalRequestId === 'string' && approvalRequestId) {
    return `/approvals/${approvalRequestId}`
  }
  const docId = n.payload?.document_id
  if (typeof docId === 'string' && docId) {
    if (n.type === 'comment_mention') {
      return `/docs/${docId}?comments=open`
    }
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
  if (n.type === 'pat_expiring') {
    return '/account'
  }
  return null
}
