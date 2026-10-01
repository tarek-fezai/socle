// SPDX-License-Identifier: AGPL-3.0-or-later
export type SiemProvider = 'splunk' | 'datadog' | 'sentinel'
export type SiemStatus = 'connected' | 'disconnected' | 'error'
export type WebhookStatus = 'active' | 'disabled'

export type SiemConnector = {
  id: string
  provider: SiemProvider | string
  config: Record<string, unknown>
  status: SiemStatus | string
  connectedAt: string | null
}

export type SiemTestResult = {
  ok: boolean
  httpStatus: number
  message: string
}

export type WebhookEndpoint = {
  id: string
  url: string
  subscribedEvents: string[]
  status: WebhookStatus | string
  createdAt: string | null
}

export type WebhookCreateResult = {
  endpoint: WebhookEndpoint
  secret: string
  secretPrefix: string
}

type ApiGet = { get: <T>(url: string) => Promise<{ data: T }> }
type ApiMut = {
  get: <T>(url: string) => Promise<{ data: T }>
  post: <T>(url: string, body?: unknown) => Promise<{ data: T }>
  put: <T>(url: string, body?: unknown) => Promise<{ data: T }>
  delete: (url: string) => Promise<unknown>
}

export async function listSiemConnectors(api: ApiGet): Promise<SiemConnector[]> {
  const { data } = await api.get<SiemConnector[]>('/api/v1/siem-connectors')
  return data
}

export async function createSiemConnector(
  api: ApiMut,
  body: { provider: SiemProvider; config: Record<string, unknown> },
): Promise<SiemConnector> {
  const { data } = await api.post<SiemConnector>('/api/v1/siem-connectors', body)
  return data
}

export async function updateSiemConnector(
  api: ApiMut,
  id: string,
  body: { config?: Record<string, unknown>; status?: SiemStatus },
): Promise<SiemConnector> {
  const { data } = await api.put<SiemConnector>(`/api/v1/siem-connectors/${id}`, body)
  return data
}

export async function deleteSiemConnector(api: ApiMut, id: string): Promise<void> {
  await api.delete(`/api/v1/siem-connectors/${id}`)
}

export async function testSiemConnector(api: ApiMut, id: string): Promise<SiemTestResult> {
  const { data } = await api.post<SiemTestResult>(`/api/v1/siem-connectors/${id}/test`)
  return data
}

export async function listWebhookEndpoints(api: ApiGet): Promise<WebhookEndpoint[]> {
  const { data } = await api.get<WebhookEndpoint[]>('/api/v1/webhook-endpoints')
  return data
}

export async function createWebhookEndpoint(
  api: ApiMut,
  body: { url: string; subscribedEvents: string[]; status?: WebhookStatus },
): Promise<WebhookCreateResult> {
  const { data } = await api.post<WebhookCreateResult>('/api/v1/webhook-endpoints', body)
  return data
}

export async function updateWebhookEndpoint(
  api: ApiMut,
  id: string,
  body: { url?: string; subscribedEvents?: string[]; status?: WebhookStatus },
): Promise<WebhookEndpoint> {
  const { data } = await api.put<WebhookEndpoint>(`/api/v1/webhook-endpoints/${id}`, body)
  return data
}

export async function deleteWebhookEndpoint(api: ApiMut, id: string): Promise<void> {
  await api.delete(`/api/v1/webhook-endpoints/${id}`)
}

export function providerLabel(provider: string): string {
  switch (provider) {
    case 'splunk':
      return 'Splunk — HTTP Event Collector'
    case 'datadog':
      return 'Datadog — Log Intake API'
    case 'sentinel':
      return 'Microsoft Sentinel — Data Collector API'
    default:
      return provider
  }
}

export function siemStatusLabel(status: string): string {
  switch (status) {
    case 'connected':
      return 'Connecté'
    case 'error':
      return 'Erreur'
    default:
      return 'Non connecté'
  }
}

export function secretFieldForProvider(provider: string): { key: string; label: string } {
  switch (provider) {
    case 'datadog':
      return { key: 'api_key', label: 'Clé API Datadog' }
    case 'sentinel':
      return { key: 'shared_key', label: 'Clé partagée / Authorization' }
    default:
      return { key: 'hec_token', label: 'Token HEC' }
  }
}
