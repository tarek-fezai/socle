// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import {
  favoritesLead,
  favoritesQueryKey,
  favoriteKey,
  listFavorites,
  removeFavorite,
  type FavoriteItem,
  type FavoriteResourceType,
} from '../lib/favorites'
import { documentHref, folderHref, spaceBrowseHref } from '../lib/folders'
import { listSpaces } from '../lib/spaces'

function hrefFor(type: string, id: string) {
  if (type === 'document') return documentHref(id)
  if (type === 'folder') return folderHref(id)
  if (type === 'space') return spaceBrowseHref(id)
  return '/'
}

const TYPE_LABEL: Record<FavoriteResourceType, string> = {
  document: 'Document',
  folder: 'Dossier',
  space: 'Espace',
}

/**
 * Favoris — colonne principale de Favorites.dc.html.
 * Comme la maquette, la première ligne de la liste est mise en avant (titre 600, icône accent).
 * Seules données serveur : type, id, titre, date d'ajout (pas de statut / date de révision / espace).
 */
export function FavoritesPage() {
  const qc = useQueryClient()
  const favs = useQuery({
    queryKey: favoritesQueryKey,
    queryFn: () => listFavorites(api),
  })
  // Couleur d'un espace favori : donnée réelle de GET /spaces (le favori n'embarque pas l'espace).
  const spaces = useQuery({
    queryKey: ['spaces'],
    queryFn: () => listSpaces(api),
    enabled: Boolean(favs.data?.items.some((i) => i.resourceType === 'space')),
  })

  const remove = useMutation({
    mutationFn: (item: FavoriteItem) => removeFavorite(api, item.resourceType, item.resourceId),
    onSuccess: (_void, item) => {
      void qc.invalidateQueries({ queryKey: favoritesQueryKey })
      void qc.invalidateQueries({ queryKey: favoriteKey(item.resourceType, item.resourceId) })
    },
  })

  const items = favs.data?.items ?? []
  const spaceColor = (id: string) => spaces.data?.find((s) => s.id === id)?.color ?? '#3730E0'

  return (
    <main className="mx-auto w-full max-w-[996px] px-12 pt-12 leading-[normal] md:mx-0">
      <h1
        className="font-display text-[40px] font-normal leading-[normal] text-socle-ink"
        data-mock-id="favorites-title"
      >
        Favoris
      </h1>
      <p
        className="mb-[30px] mt-1.5 text-[15px] leading-[normal] text-socle-muted"
        data-mock-id="favorites-lead"
      >
        {favs.data ? favoritesLead(items) : 'Chargement…'}
      </p>

      {favs.isError && (
        <p className="text-sm text-socle-danger" role="alert">
          Impossible de charger les favoris.
        </p>
      )}
      {remove.isError && (
        <p className="mb-3 text-sm text-socle-danger" role="alert">
          Impossible de retirer ce favori.
        </p>
      )}

      <ul className="m-0 flex list-none flex-col gap-2 p-0" data-mock-id="favorites-list">
        {items.map((item, i) => (
          <li
            key={`${item.resourceType}:${item.resourceId}`}
            data-mock-id={`favorites-row-${i}`}
            className="relative flex items-center gap-3 rounded-[10px] border border-socle-line px-[15px] py-[13px] hover:border-[#C7C6F5]"
          >
            {item.resourceType === 'space' ? (
              <div
                aria-hidden
                className="h-4 w-4 shrink-0 rounded-[4px]"
                style={{ background: spaceColor(item.resourceId) }}
              />
            ) : (
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke={i === 0 ? '#3730E0' : '#6B6B72'}
                strokeWidth="2"
                className="shrink-0"
                aria-hidden
              >
                <path d="M14 3v4a1 1 0 0 0 1 1h4" />
                <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
              </svg>
            )}
            <div className="min-w-0 grow">
              <div
                data-mock-id={`favorites-row-${i}-title`}
                className={`text-[14px] ${
                  i === 0 ? 'font-semibold' : 'font-medium'
                } text-socle-ink ${
                  item.resourceType === 'space' ? '' : 'mb-0.5'
                }`}
              >
                <Link
                  to={hrefFor(item.resourceType, item.resourceId)}
                  className="after:absolute after:inset-0 after:rounded-[10px]"
                >
                  {item.title}
                </Link>
              </div>
              <div className="text-[12px] text-socle-muted" data-visual-mask="favorites-row-meta">
                {TYPE_LABEL[item.resourceType]}
              </div>
            </div>
            <button
              type="button"
              aria-label="Retirer des favoris"
              aria-pressed="true"
              title="Retirer des favoris"
              disabled={remove.isPending}
              onClick={() => remove.mutate(item)}
              className="relative z-10 flex shrink-0 items-center justify-center border-0 bg-transparent p-0 hover:opacity-75 disabled:opacity-50"
            >
              <svg
                width="15"
                height="15"
                viewBox="0 0 24 24"
                fill="#B7791F"
                stroke="#B7791F"
                strokeWidth="1.5"
                aria-hidden
              >
                <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
              </svg>
            </button>
          </li>
        ))}
      </ul>
    </main>
  )
}
