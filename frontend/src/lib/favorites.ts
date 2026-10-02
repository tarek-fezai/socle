// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'

export type FavoriteResourceType = 'document' | 'folder' | 'space'

export type FavoriteItem = {
  resourceType: FavoriteResourceType
  resourceId: string
  title: string
  spaceId?: string | null
  spaceName?: string | null
  createdAt: string
}

export type FavoritesList = {
  items: FavoriteItem[]
}

export async function listFavorites(api: AxiosInstance) {
  const { data } = await api.get<FavoritesList>('/api/v1/favorites')
  return data
}

export async function addFavorite(
  api: AxiosInstance,
  resourceType: FavoriteResourceType,
  resourceId: string,
) {
  const { data } = await api.put<FavoriteItem>(`/api/v1/favorites/${resourceType}/${resourceId}`)
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
