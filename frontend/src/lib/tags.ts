// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'
import type { TagRef } from './documents'

type Api = Pick<AxiosInstance, 'get' | 'post' | 'delete'>

/** Autocomplétion : GET /api/v1/tags?q=… (préfixes d'abord côté serveur). */
export async function searchTags(api: Pick<AxiosInstance, 'get'>, q: string, limit = 8) {
  const { data } = await api.get<TagRef[]>('/api/v1/tags', {
    params: { ...(q.trim() ? { q: q.trim() } : {}), limit },
  })
  return data
}

/** Rattache une étiquette existante (`tagId`) ou en crée une (`name`) — 201 créée, 200 déjà présente. */
export async function attachTag(
  api: Pick<AxiosInstance, 'post'>,
  documentId: string,
  tag: { tagId: string } | { name: string },
) {
  const { data } = await api.post<TagRef>(`/api/v1/documents/${documentId}/tags`, tag)
  return data
}

export async function detachTag(api: Pick<AxiosInstance, 'delete'>, documentId: string, tagId: string) {
  await api.delete(`/api/v1/documents/${documentId}/tags/${tagId}`)
}

/** Suggestions non déjà rattachées au document, sans doublon (casse ignorée). */
export function filterTagSuggestions(suggestions: TagRef[], attached: TagRef[]): TagRef[] {
  const taken = new Set(attached.map((t) => t.id))
  const seen = new Set<string>()
  const out: TagRef[] = []
  for (const s of suggestions) {
    if (taken.has(s.id)) continue
    const key = s.name.trim().toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    out.push(s)
  }
  return out
}

/** Tri par nom (comme le serveur) après ajout local. */
export function sortTags(tags: TagRef[]): TagRef[] {
  return [...tags].sort((a, b) => a.name.localeCompare(b.name, 'fr', { sensitivity: 'base' }))
}

export type { Api as TagsApi }
