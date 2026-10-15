// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'

export type InvitationView = {
  id: string
  email: string
  teamId: string | null
  teamName: string | null
  teamRole: string | null
  globalRole: string
  status: string
  expiresAt: string
  lastSentAt: string
  createdAt: string
  invitedBy: string
  invitedByName: string | null
  sendCount: number
}

export type CreateInvitationBody = {
  email: string
  teamId?: string | null
  teamRole?: string | null
  globalRole?: string
  confirmAdmin?: boolean
}

export function invitationsKey() {
  return ['admin', 'invitations'] as const
}

export async function listInvitations(api: Pick<AxiosInstance, 'get'>) {
  const { data } = await api.get<InvitationView[]>('/api/v1/admin/invitations')
  return data
}

export type InvitationCapability = {
  enabled: boolean
  missing: string[]
}

export function invitationNotConfiguredMessage(missing: string[]): string {
  return `L'envoi d'invitations n'est pas configuré (${missing.join(', ')}).`
}

export async function getInvitationCapability(
  api: Pick<AxiosInstance, 'get'>,
): Promise<InvitationCapability> {
  const { data } = await api.get<InvitationCapability | unknown>('/api/v1/admin/invitations/capability')
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return { enabled: true, missing: [] }
  }
  const row = data as { enabled?: unknown; missing?: unknown }
  if (typeof row.enabled !== 'boolean') {
    return { enabled: true, missing: [] }
  }
  const missing = Array.isArray(row.missing)
    ? row.missing.filter((name): name is string => typeof name === 'string')
    : []
  return { enabled: row.enabled, missing }
}

export async function createInvitation(api: Pick<AxiosInstance, 'post'>, body: CreateInvitationBody) {
  const { data } = await api.post<InvitationView>('/api/v1/admin/invitations', body)
  return data
}

export async function resendInvitation(api: Pick<AxiosInstance, 'post'>, id: string) {
  const { data } = await api.post<InvitationView>(`/api/v1/admin/invitations/${id}/resend`)
  return data
}

export async function revokeInvitation(api: Pick<AxiosInstance, 'post'>, id: string) {
  await api.post(`/api/v1/admin/invitations/${id}/revoke`)
}

export async function acceptInvitation(api: Pick<AxiosInstance, 'post'>, token: string) {
  // Création du compte : attendre la réponse du serveur au-delà du délai général de 15 s.
  const { data } = await api.post<{ userId: string; email: string }>(
    '/api/v1/invitations/accept',
    { token },
    { timeout: 90_000 },
  )
  return data
}
