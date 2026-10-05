// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type TagCreationPolicy = 'any_editor' | 'admins_only'

export type TagAdminView = {
  id: string
  name: string
  color: string | null
  documentCount: number
  createdByDisplayName: string | null
  createdAt: string | null
  governed: boolean
}

export type TagAdminSummary = {
  tagCount: number
  taggedDocumentCount: number
  totalDocumentCount: number
  tagCreationPolicy: TagCreationPolicy
}

export type TagListResponse = {
  tags: TagAdminView[]
  summary: TagAdminSummary
}

export type MergeTagRequest = {
  targetTagId: string
  transferAssignments: boolean
}

export function tagsAdminKey() {
  return ['admin', 'tags'] as const
}

export async function listAdminTags(api: Pick<AxiosInstance, 'get'>) {
  const { data } = await api.get<TagListResponse>('/api/v1/admin/tags')
  return data
}

export async function createAdminTag(
  api: Pick<AxiosInstance, 'post'>,
  body: { name: string; color?: string | null },
) {
  const { data } = await api.post<TagAdminView>('/api/v1/admin/tags', body)
  return data
}

export async function renameAdminTag(api: Pick<AxiosInstance, 'put'>, id: string, name: string) {
  const { data } = await api.put<TagAdminView>(`/api/v1/admin/tags/${id}/name`, { name })
  return data
}

export async function mergeAdminTag(
  api: Pick<AxiosInstance, 'post'>,
  sourceId: string,
  request: MergeTagRequest,
) {
  const { data } = await api.post<TagAdminView>(`/api/v1/admin/tags/${sourceId}/merge`, request)
  return data
}

export async function deleteAdminTag(api: Pick<AxiosInstance, 'delete'>, id: string) {
  await api.delete(`/api/v1/admin/tags/${id}`)
}

export async function updateTagCreationPolicy(
  api: Pick<AxiosInstance, 'put'>,
  tagCreationPolicy: TagCreationPolicy,
) {
  const { data } = await api.put<TagAdminSummary>('/api/v1/admin/tags/creation-policy', {
    tagCreationPolicy,
  })
  return data
}

export function tagCreationPolicyLabel(policy: TagCreationPolicy): string {
  if (policy === 'admins_only') {
    return 'Administrateurs système uniquement'
  }
  return "Tout membre lors de l'édition d'un document"
}
