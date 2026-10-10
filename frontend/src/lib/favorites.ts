// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'

export type FavoriteResourceType = 'document' | 'folder' | 'space'

/** Favori normalisé côté UI (le serveur renvoie `targetType` / `targetId`). */
export type FavoriteItem = {
  resourceType: FavoriteResourceType
  resourceId: string
  title: string
  createdAt: string
}

export type FavoritesList = {
  items: FavoriteItem[]
}

/** `FavoriteDtos.FavoriteItem` (OpenAPI) — `GET /api/v1/favorites` renvoie un **tableau** de ces objets. */
type ApiFavoriteItem = {
  targetType?: string
  targetId?: string
  /** Ancien contrat `{ items: [...] }` (tests / mocks historiques). */
  resourceType?: string
  resourceId?: string
  title?: string | null
  createdAt?: string
}

function isResourceType(v: unknown): v is FavoriteResourceType {
  return v === 'document' || v === 'folder' || v === 'space'
}

export function normalizeFavorite(raw: ApiFavoriteItem): FavoriteItem | null {
  const type = raw.targetType ?? raw.resourceType
  const id = raw.targetId ?? raw.resourceId
  if (!isResourceType(type) || !id) return null
  return { resourceType: type, resourceId: id, title: raw.title ?? '', createdAt: raw.createdAt ?? '' }
}

/** Accepte le tableau réel de l'API ou l'enveloppe `{ items }` historique. */
export function normalizeFavoritesPayload(data: unknown): FavoritesList {
  const rows: ApiFavoriteItem[] = Array.isArray(data)
    ? (data as ApiFavoriteItem[])
    : Array.isArray((data as { items?: unknown } | null)?.items)
      ? ((data as { items: ApiFavoriteItem[] }).items)
      : []
  const items: FavoriteItem[] = []
  for (const row of rows) {
    const item = normalizeFavorite(row)
    if (item) items.push(item)
  }
  return { items }
}

export async function listFavorites(api: AxiosInstance): Promise<FavoritesList> {
  const { data } = await api.get<unknown>('/api/v1/favorites')
  return normalizeFavoritesPayload(data)
}

export async function addFavorite(
  api: AxiosInstance,
  resourceType: FavoriteResourceType,
  resourceId: string,
) {
  const { data } = await api.put<ApiFavoriteItem>(`/api/v1/favorites/${resourceType}/${resourceId}`)
  return data
}

export async function removeFavorite(
  api: AxiosInstance,
  resourceType: FavoriteResourceType,
  resourceId: string,
) {
  await api.delete(`/api/v1/favorites/${resourceType}/${resourceId}`)
}

export async function isFavorite(
  api: AxiosInstance,
  resourceType: FavoriteResourceType,
  resourceId: string,
) {
  try {
    const { data } = await api.get<{ favorited: boolean }>(
      `/api/v1/favorites/${resourceType}/${resourceId}`,
    )
    return Boolean(data.favorited)
  } catch {
    return false
  }
}

export const favoritesQueryKey = ['favorites'] as const
export const favoriteKey = (type: FavoriteResourceType, id: string) =>
  ['favorites', type, id] as const

/** « 4 documents et 1 espace que vous avez marqués comme favoris. » — décomptes réels uniquement. */
export function favoritesLead(items: FavoriteItem[]): string {
  if (items.length === 0) return 'Aucun favori pour le moment.'
  const count = (t: FavoriteResourceType) => items.filter((i) => i.resourceType === t).length
  const parts: string[] = []
  const docs = count('document')
  const folders = count('folder')
  const spaces = count('space')
  if (docs) parts.push(`${docs} document${docs > 1 ? 's' : ''}`)
  if (folders) parts.push(`${folders} dossier${folders > 1 ? 's' : ''}`)
  if (spaces) parts.push(`${spaces} espace${spaces > 1 ? 's' : ''}`)
  const list =
    parts.length > 1 ? `${parts.slice(0, -1).join(', ')} et ${parts[parts.length - 1]}` : parts[0]
  return items.length === 1
    ? `${list} que vous avez marqué comme favori.`
    : `${list} que vous avez marqués comme favoris.`
}
