// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'
import { formatLongDateFr, formatRelativeFr } from './relativeTime'

export type FavoriteResourceType = 'document' | 'folder' | 'space'

/** Favori normalisé côté UI (le serveur renvoie `targetType` / `targetId`). */
export type FavoriteItem = {
  resourceType: FavoriteResourceType
  resourceId: string
  title: string
  createdAt: string
  status?: string | null
  updatedAt?: string | null
  spaceId?: string | null
  spaceName?: string | null
  documentCount?: number | null
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
  status?: string | null
  updatedAt?: string | null
  spaceId?: string | null
  spaceName?: string | null
  documentCount?: number | null
}

function isResourceType(v: unknown): v is FavoriteResourceType {
  return v === 'document' || v === 'folder' || v === 'space'
}

export function normalizeFavorite(raw: ApiFavoriteItem): FavoriteItem | null {
  const type = raw.targetType ?? raw.resourceType
  const id = raw.targetId ?? raw.resourceId
  if (!isResourceType(type) || !id) return null
  return {
    resourceType: type,
    resourceId: id,
    title: raw.title ?? '',
    createdAt: raw.createdAt ?? '',
    status: raw.status,
    updatedAt: raw.updatedAt,
    spaceId: raw.spaceId,
    spaceName: raw.spaceName,
    documentCount: raw.documentCount,
  }
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
export function favoriteMetaLine(item: FavoriteItem, now = Date.now()): string {
  if (!item.title && !item.status && !item.spaceName) {
    return 'Indisponible'
  }
  if (item.resourceType === 'space') {
    const n = item.documentCount
    return n == null ? 'Espace' : `Espace · ${n} document${n > 1 ? 's' : ''}`
  }
  if (item.resourceType === 'folder') {
    return item.spaceName ? `Dossier · ${item.spaceName}` : 'Dossier'
  }
  const status = item.status?.toLowerCase()
  const label =
    status === 'valide' || status === 'published'
      ? 'Validé'
      : status === 'brouillon' || status === 'draft'
        ? 'Brouillon'
        : status === 'en_revue' || status === 'review'
          ? 'En revue'
          : item.status || 'Document'
  if (!item.updatedAt) return label
  if (status === 'brouillon' || status === 'draft') {
    const rel = formatRelativeFr(item.updatedAt, now)
    return rel ? `${label} · modifié ${rel}` : label
  }
  const date = formatLongDateFr(item.updatedAt)
  return date ? `${label} · révisé le ${date}` : label
}

export function groupFavoritesBySpace(items: FavoriteItem[]): Array<{
  key: string
  label: string
  items: FavoriteItem[]
}> {
  const order: string[] = []
  const map = new Map<string, FavoriteItem[]>()
  for (const item of items) {
    const label = item.spaceName?.trim() || (item.resourceType === 'space' ? item.title : '') || 'Autres'
    const key = item.spaceId || label
    if (!map.has(key)) {
      map.set(key, [])
      order.push(key)
    }
    map.get(key)!.push(item)
  }
  return order.map((key) => ({
    key,
    label: map.get(key)![0].spaceName?.trim()
      || (map.get(key)![0].resourceType === 'space' ? map.get(key)![0].title : 'Autres'),
    items: map.get(key)!,
  }))
}

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
