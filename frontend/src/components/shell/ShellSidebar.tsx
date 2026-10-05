// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api } from '../../lib/api'
import { documentHref, getSpaceTree, spaceBrowseHref, spaceTreeKey } from '../../lib/folders'
import { listSpaces } from '../../lib/spaces'
import {
  initialsFromName,
  loadExpandedSpaces,
  saveExpandedSpaces,
  searchShortcutLabel,
} from './shellUtils'
import {
  useBrandingAccent,
  useBrandingInstanceName,
  useBrandingLogoUrl,
  useHidePoweredBy,
} from '../../lib/publicBranding'

type Props = {
  onOpenSearch: () => void
}

const iconSearch = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" strokeLinecap="round" aria-hidden>
    <circle cx="11" cy="11" r="7" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
  </svg>
)

const iconStar = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
  </svg>
)

const iconSpaces = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" aria-hidden>
    <rect x="3" y="3" width="7" height="7" rx="1.5" />
    <rect x="14" y="3" width="7" height="7" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
    <rect x="14" y="14" width="7" height="7" rx="1.5" />
  </svg>
)

const iconTeam = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#6B6B72" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
    <circle cx="9" cy="7" r="4" />
    <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
    <path d="M16 3.13a4 4 0 0 1 0 7.75" />
  </svg>
)

export function ShellSidebar({ onOpenSearch }: Props) {
  const { me, logout } = useAuth()
  const location = useLocation()
  const [expanded, setExpanded] = useState<Set<string>>(() => loadExpandedSpaces())
  const [kbd, setKbd] = useState('⌘K')

  useEffect(() => {
    setKbd(searchShortcutLabel())
  }, [])

  const spaces = useQuery({
    queryKey: ['spaces'],
    queryFn: () => listSpaces(api),
  })

  const spaceList = spaces.data ?? []

  const trees = useQueries({
    queries: spaceList.map((s) => ({
      queryKey: spaceTreeKey(s.id),
      queryFn: () => getSpaceTree(api, s.id, 1),
      enabled: expanded.has(s.id) || spaceList.length <= 8,
    })),
  })

  const treeBySpace = useMemo(() => {
    const map = new Map<string, (typeof trees)[number]['data']>()
    spaceList.forEach((s, i) => {
      map.set(s.id, trees[i]?.data)
    })
    return map
  }, [spaceList, trees])

  // Expand all by default on first visit so sidebar matches mockup density
  useEffect(() => {
    if (spaceList.length === 0) return
    if (expanded.size > 0) return
    const all = new Set(spaceList.map((s) => s.id))
    setExpanded(all)
    saveExpandedSpaces(all)
  }, [spaceList, expanded.size])

  function toggleSpace(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      saveExpandedSpaces(next)
      return next
    })
  }

  const path = location.pathname
  const displayName = me?.displayName ?? 'Compte'
  const initials = me?.avatarInitials || initialsFromName(displayName)
  const logoUrl = useBrandingLogoUrl()
  const accent = useBrandingAccent()
  const brandName = useBrandingInstanceName() || 'Socle'
  const hidePoweredBy = useHidePoweredBy()

  return (
    <aside className="shell-sidebar shell-desktop-only" data-mock-id="shell-sidebar" aria-label="Navigation">
      <Link to="/" className="shell-logo" data-mock-id="shell-logo">
        {logoUrl ? (
          <img className="shell-logo-mark shell-logo-mark--img" src={logoUrl} alt="" width={24} height={24} aria-hidden />
        ) : (
          <span className="shell-logo-mark" style={{ background: accent }} aria-hidden />
        )}
        <span className="shell-logo-name">{brandName}</span>
      </Link>

      <button
        type="button"
        className="shell-search-chip"
        onClick={onOpenSearch}
        data-mock-id="shell-search-chip"
        data-testid="shell-search-chip"
      >
        {iconSearch}
        <span className="label">Rechercher</span>
        <span className="kbd">{kbd}</span>
      </button>

      <Link
        to="/favorites"
        className={`shell-nav-item${path.startsWith('/favorites') ? ' active' : ''}`}
        data-mock-id="shell-nav-favorites"
      >
        {iconStar}
        Favoris
      </Link>

      <Link
        to="/spaces"
        className={`shell-nav-item${path === '/spaces' ? ' active' : ''}`}
        data-mock-id="shell-nav-spaces"
      >
        {iconSpaces}
        Tous les espaces
      </Link>

      <Link
        to="/team"
        className={`shell-nav-item${path.startsWith('/team') ? ' active' : ''}`}
        data-mock-id="shell-nav-team"
      >
        {iconTeam}
        Membres &amp; équipes
      </Link>

      <div className="shell-tree" data-mock-id="shell-tree">
        {spaceList.map((space, idx) => {
          const open = expanded.has(space.id)
          const tree = treeBySpace.get(space.id)
          const rootDocs = (tree?.documents ?? [])
            .filter((d) => d.folderId == null)
            .slice()
            .sort((a, b) => a.position - b.position || a.title.localeCompare(b.title, 'fr'))
          return (
            <div key={space.id}>
              <button
                type="button"
                className={`shell-tree-section${idx > 0 ? ' spaced' : ''}`}
                onClick={() => toggleSpace(space.id)}
                aria-expanded={open}
                data-mock-id={idx === 0 ? 'shell-space-section' : undefined}
              >
                {space.name}
              </button>
              {open &&
                rootDocs.map((doc, di) => {
                  const active = path === documentHref(doc.id) || path.startsWith(`/docs/${doc.id}/`)
                  return (
                    <Link
                      key={doc.id}
                      to={documentHref(doc.id)}
                      className={`shell-nav-link${active ? ' active' : ''}`}
                      data-mock-id={idx === 0 && di === 0 ? 'shell-tree-doc' : undefined}
                    >
                      {doc.title}
                    </Link>
                  )
                })}
              {open && rootDocs.length === 0 && (
                <Link to={spaceBrowseHref(space.id)} className="shell-nav-link">
                  Parcourir…
                </Link>
              )}
            </div>
          )
        })}
      </div>

      {!hidePoweredBy && (
        <p className="shell-powered" data-mock-id="shell-powered-by">
          Propulsé par Socle
        </p>
      )}

      <button
        type="button"
        className="shell-user"
        data-mock-id="shell-user"
        onClick={() => {
          if (window.confirm('Se déconnecter ?')) void logout()
        }}
        title="Déconnexion"
      >
        <span className="shell-avatar" aria-hidden>
          {initials}
        </span>{' '}
        <span className="shell-user-name">{displayName}</span>
      </button>
    </aside>
  )
}
