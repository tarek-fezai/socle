// SPDX-License-Identifier: AGPL-3.0-or-later
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { favoritesQueryKey, listFavorites } from '../lib/favorites'
import { documentHref, folderHref, spaceBrowseHref } from '../lib/folders'

function hrefFor(type: string, id: string) {
  if (type === 'document') return documentHref(id)
  if (type === 'folder') return folderHref(id)
  if (type === 'space') return spaceBrowseHref(id)
  return '/'
}

/** Liste simple des favoris — écran complet plus tard. */
export function FavoritesPage() {
  const favs = useQuery({
    queryKey: favoritesQueryKey,
    queryFn: () => listFavorites(api),
  })

  return (
    <main className="page-shell">
      <h1 className="serif-title">Favoris</h1>
      <p className="mt-2 text-sm text-socle-muted">Documents, dossiers et espaces épinglés.</p>

      {favs.isLoading && <p className="mt-8 text-sm text-socle-muted">Chargement…</p>}
      {favs.isError && (
        <p className="mt-8 text-sm text-socle-danger" role="alert">
          Impossible de charger les favoris.
        </p>
      )}
      {favs.data && favs.data.items.length === 0 && (
        <p className="mt-8 text-sm text-socle-muted">Aucun favori pour le moment.</p>
      )}
      <ul className="mt-8 space-y-2">
        {(favs.data?.items ?? []).map((item) => (
          <li key={`${item.resourceType}:${item.resourceId}`}>
            <Link
              to={hrefFor(item.resourceType, item.resourceId)}
              className="flex items-center justify-between rounded-[10px] border border-socle-line px-4 py-3 hover:border-[#C7C6F5]"
            >
              <div>
                <div className="text-sm font-medium text-socle-ink">{item.title}</div>
                <div className="text-xs text-socle-muted">
                  {item.resourceType}
                  {item.spaceName ? ` · ${item.spaceName}` : ''}
                </div>
              </div>
              <span className="text-[12.5px] font-semibold text-socle-accent">Ouvrir →</span>
            </Link>
          </li>
        ))}
      </ul>
    </main>
  )
}
