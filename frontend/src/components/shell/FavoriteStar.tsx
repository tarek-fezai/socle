// SPDX-License-Identifier: AGPL-3.0-or-later
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'
import {
  addFavorite,
  favoriteKey,
  favoritesQueryKey,
  isFavorite,
  removeFavorite,
  type FavoriteResourceType,
} from '../../lib/favorites'

type Props = {
  resourceType: FavoriteResourceType
  resourceId: string
  className?: string
}

/** Bouton étoile minimal (document / espace). */
export function FavoriteStar({ resourceType, resourceId, className }: Props) {
  const qc = useQueryClient()
  const fav = useQuery({
    queryKey: favoriteKey(resourceType, resourceId),
    queryFn: () => isFavorite(api, resourceType, resourceId),
    enabled: Boolean(resourceId),
  })

  const toggle = useMutation({
    mutationFn: async () => {
      if (fav.data) {
        await removeFavorite(api, resourceType, resourceId)
        return false
      }
      await addFavorite(api, resourceType, resourceId)
      return true
    },
    onSuccess: (next) => {
      qc.setQueryData(favoriteKey(resourceType, resourceId), next)
      void qc.invalidateQueries({ queryKey: favoritesQueryKey })
    },
  })

  const on = Boolean(fav.data)

  return (
    <button
      type="button"
      className={className}
      aria-pressed={on}
      aria-label={on ? 'Retirer des favoris' : 'Ajouter aux favoris'}
      data-testid="favorite-star"
      disabled={toggle.isPending || fav.isLoading}
      onClick={() => toggle.mutate()}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 32,
        height: 32,
        borderRadius: 8,
        border: '1px solid #ECECEE',
        background: '#FFFFFF',
        cursor: 'pointer',
        color: on ? '#3730E0' : '#6B6B72',
      }}
    >
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill={on ? 'currentColor' : 'none'}
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden
      >
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
      </svg>
    </button>
  )
}
