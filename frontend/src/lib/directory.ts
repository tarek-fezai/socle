// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'

export type DirectoryUser = {
  id: string
  displayName: string
  email: string
  initials: string
}

export type AdminTeamRef = {
  id: string
  name: string
  role: string
}

export type AdminUserRow = {
  id: string
  displayName: string
  email: string
  initials: string
  platformRoles: string[]
  teams: AdminTeamRef[]
  status: string
  lastActivityAt: string | null
}

export type AdminUsersResponse = {
  users: AdminUserRow[]
  total: number
  activeCount: number
  teamCount: number
  offset: number
  limit: number
}

export async function searchDirectoryUsers(api: AxiosInstance, q: string, limit = 8) {
  const { data } = await api.get<{ users: DirectoryUser[] }>('/api/v1/directory/users', {
    params: { q, limit },
  })
  return data.users
}

export async function listAdminUsers(
  api: AxiosInstance,
  params: {
    q?: string
    status?: string
    platformRole?: string
    groupId?: string
    offset?: number
    limit?: number
  },
) {
  const { data } = await api.get<AdminUsersResponse>('/api/v1/admin/users', { params })
  return data
}

export async function disableUser(api: AxiosInstance, id: string) {
  const { data } = await api.post<{ id: string; status: string }>(`/api/v1/admin/users/${id}/disable`)
  return data
}

export async function enableUser(api: AxiosInstance, id: string) {
  const { data } = await api.post<{ id: string; status: string }>(`/api/v1/admin/users/${id}/enable`)
  return data
}
