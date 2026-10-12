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
  const { data } = await api.post<{ userId: string; email: string }>('/api/v1/invitations/accept', {
    token,
  })
  return data
}
