// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type GroupMember = {
  userId: string
  email: string
  displayName: string
}

export type Group = {
  id: string
  name: string
  createdBy: string | null
  createdAt: string | null
  memberCount: number
  canManage: boolean
  members: GroupMember[]
}

export async function listGroups(api: AxiosInstance) {
  const { data } = await api.get<Group[]>('/api/v1/groups')
  return data
}

export async function createGroup(api: AxiosInstance, name: string) {
  const { data } = await api.post<Group>('/api/v1/groups', { name })
  return data
}

export async function addGroupMember(api: AxiosInstance, groupId: string, userId: string) {
  const { data } = await api.post<Group>(`/api/v1/groups/${groupId}/members`, { userId })
  return data
}

export async function removeGroupMember(api: AxiosInstance, groupId: string, userId: string) {
  const { data } = await api.delete<Group>(`/api/v1/groups/${groupId}/members/${userId}`)
  return data
}

export async function deleteGroup(api: AxiosInstance, groupId: string) {
  await api.delete(`/api/v1/groups/${groupId}`)
}
