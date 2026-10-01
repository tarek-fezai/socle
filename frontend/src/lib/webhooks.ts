// SPDX-License-Identifier: AGPL-3.0-or-later
export type WebhookDeliveryStatus = 'pending' | 'delivered' | 'failed' | 'retrying'

export type WebhookDelivery = {
  id: string
  endpointId: string
  endpointUrl: string
  eventType: string
  status: WebhookDeliveryStatus | string
  attemptCount: number
  lastResponseCode: number | null
  deliveredAt: string | null
  createdAt: string
}

export type WebhookDeliveryPage = {
  items: WebhookDelivery[]
  offset: number
  limit: number
  total: number
}

export async function listWebhookDeliveries(
  api: { get: <T>(url: string) => Promise<{ data: T }> },
  opts: {
    status?: WebhookDeliveryStatus | ''
    endpointId?: string
    offset?: number
    limit?: number
  } = {},
): Promise<WebhookDeliveryPage> {
  const params = new URLSearchParams()
  if (opts.status) params.set('status', opts.status)
  if (opts.endpointId) params.set('endpointId', opts.endpointId)
  if (opts.offset != null) params.set('offset', String(opts.offset))
  if (opts.limit != null) params.set('limit', String(opts.limit))
  const qs = params.toString()
  const { data } = await api.get<WebhookDeliveryPage>(
    `/api/v1/webhooks/deliveries${qs ? `?${qs}` : ''}`,
  )
  return data
}

export function deliveryStatusLabel(status: string): string {
  switch (status) {
    case 'delivered':
      return 'Livré'
    case 'failed':
      return 'Échec'
    case 'retrying':
      return 'Nouvel essai…'
    case 'pending':
      return 'En attente'
    default:
      return status
  }
}

/** Classes badge alignées palette Socle (ok / warn / danger). */
export function deliveryStatusBadgeClass(status: string): string {
  switch (status) {
    case 'delivered':
      return 'bg-[#E9F6EF] text-[#1E8E5A]'
    case 'retrying':
    case 'pending':
      return 'bg-[#FBF3E4] text-[#B7791F]'
    case 'failed':
      return 'bg-[#FCEEEA] text-[#B54708]'
    default:
      return 'bg-[#F5F5F7] text-[#6B6B72]'
  }
}

export function formatDeliveryTimestamp(iso: string): string {
  const d = new Date(iso)
  const dd = String(d.getDate()).padStart(2, '0')
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${dd}/${mm} ${hh}:${mi}:${ss}`
}

export function httpStatusLabel(code: number | null, status: string): string {
  if (code == null) {
    if (status === 'retrying') return 'Nouvel essai…'
    if (status === 'pending') return 'En attente'
    return '—'
  }
  if (code >= 200 && code < 300) return `${code} OK`
  if (code === 504) return '504 Timeout'
  if (code >= 500) return `${code} Error`
  return String(code)
}
