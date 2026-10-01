// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type Space = {
  id: string
  name: string
  color: string | null
  createdAt: string | null
  /** open (défaut) | restricted — transclusion entrante inter-workspace */
  externalReference?: 'open' | 'restricted' | string
  /** organisation | space | restricted — défaut des nouveaux documents */
  defaultVisibility?: 'organisation' | 'space' | 'restricted' | string
  canManage: boolean
  isOwner: boolean
  isResponsible: boolean
  /** member = relations espace ; public-only = docs organisation lisibles */
  membership?: 'member' | 'public-only' | string
}

export type SpaceOwner = {
  userId: string
  email: string
  displayName: string
  responsible: boolean
}

export async function listSpaces(api: AxiosInstance) {
  const { data } = await api.get<Space[]>('/api/v1/spaces')
  return data
}

export async function getSpace(api: AxiosInstance, id: string) {
  const { data } = await api.get<Space>(`/api/v1/spaces/${id}`)
  return data
}

export async function createSpace(api: AxiosInstance, body: { name: string; color?: string }) {
  const { data } = await api.post<Space>('/api/v1/spaces', body)
  return data
}

export async function updateSpace(
  api: AxiosInstance,
  id: string,
  body: {
    name: string
    color?: string | null
    externalReference?: 'open' | 'restricted' | null
    defaultVisibility?: 'organisation' | 'space' | 'restricted' | null
  },
) {
  const { data } = await api.put<Space>(`/api/v1/spaces/${id}`, body)
  return data
}

export async function listSpaceOwners(api: AxiosInstance, spaceId: string) {
  const { data } = await api.get<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners`,
  )
  return data
}

export async function addSpaceOwner(
  api: AxiosInstance,
  spaceId: string,
  body: { userId: string; responsible?: boolean },
) {
  const { data } = await api.post<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners`,
    { userId: body.userId, responsible: body.responsible ?? false },
  )
  return data
}

export async function removeSpaceOwner(api: AxiosInstance, spaceId: string, userId: string) {
  const { data } = await api.delete<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners/${userId}`,
  )
  return data
}

export async function setSpaceResponsible(
  api: AxiosInstance,
  spaceId: string,
  userId: string,
  responsible: boolean,
) {
  const { data } = await api.put<{ spaceId: string; owners: SpaceOwner[] }>(
    `/api/v1/spaces/${spaceId}/owners/${userId}/responsible`,
    { responsible },
  )
  return data
}
