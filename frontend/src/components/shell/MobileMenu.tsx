// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api } from '../../lib/api'
import {
  buildSpaceTree,
  documentHref,
  getSpaceTree,
  spaceBrowseHref,
  spaceTreeKey,
} from '../../lib/folders'
import { listSpaces } from '../../lib/spaces'
import { initialsFromName, loadExpandedSpaces, saveExpandedSpaces } from './shellUtils'
import {
  useBrandingAccent,
  useBrandingInstanceName,
  useBrandingLogoUrl,
} from '../../lib/publicBranding'

type Props = {
  open: boolean
  onClose: () => void
  onOpenSearch: () => void
}

export function MobileMenu({ open, onClose, onOpenSearch }: Props) {
  const { me } = useAuth()
  const location = useLocation()
  const [activeSpaceId, setActiveSpaceId] = useState<string | null>(null)
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(() => new Set())
  const logoUrl = useBrandingLogoUrl()
  const accent = useBrandingAccent()
  const brandName = useBrandingInstanceName() || 'Socle'

  const spaces = useQuery({
    queryKey: ['spaces'],
    queryFn: () => listSpaces(api),
    enabled: open,
  })
  const spaceList = spaces.data ?? []

  useEffect(() => {
    if (!open || spaceList.length === 0) return
    if (activeSpaceId && spaceList.some((s) => s.id === activeSpaceId)) return
    const saved = [...loadExpandedSpaces()][0]
    setActiveSpaceId(
      saved && spaceList.some((s) => s.id === saved) ? saved : spaceList[0].id,
    )
  }, [open, spaceList, activeSpaceId])

  const trees = useQueries({
    queries: spaceList.map((s) => ({
      queryKey: spaceTreeKey(s.id),
      queryFn: () => getSpaceTree(api, s.id),
      enabled: open && Boolean(s.id),
    })),
  })

  const activeSpace = spaceList.find((s) => s.id === activeSpaceId) ?? spaceList[0]
  const activeTree = useMemo(() => {
    if (!activeSpace) return null
    const idx = spaceList.findIndex((s) => s.id === activeSpace.id)
    return trees[idx]?.data ?? null
  }, [activeSpace, spaceList, trees])

  const view = useMemo(
    () => (activeTree ? buildSpaceTree(activeTree) : { folders: [], documents: [] }),
    [activeTree],
  )

  const otherSpaces = spaceList.filter((s) => s.id !== activeSpace?.id)

  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  const displayName = me?.displayName ?? 'Compte'
  const initials = me?.avatarInitials || initialsFromName(displayName)
  const path = location.pathname

  function toggleFolder(id: string) {
    setExpandedFolders((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function cycleSpace() {
    if (spaceList.length < 2) {
      onClose()
      return
    }
    const idx = spaceList.findIndex((s) => s.id === activeSpace?.id)
    const next = spaceList[(idx + 1) % spaceList.length]
    setActiveSpaceId(next.id)
    const expanded = loadExpandedSpaces()
    expanded.add(next.id)
    saveExpandedSpaces(expanded)
  }

  return (
    <div className="shell-drawer-root shell-mobile-only" data-mock-id="mobile-menu" data-testid="mobile-menu">
      <div className="shell-drawer-backdrop" onClick={onClose} aria-hidden />
      <div className="shell-drawer" role="dialog" aria-modal="true" aria-label="Menu">
        <div className="shell-drawer-head">
          <div className="shell-drawer-logo">
            {logoUrl ? (
              <img
                className="shell-drawer-logo-mark shell-logo-mark--img"
                src={logoUrl}
                alt=""
                width={24}
                height={24}
                aria-hidden
              />
            ) : (
              <span className="shell-drawer-logo-mark" style={{ background: accent }} aria-hidden />
            )}
            <span className="shell-drawer-logo-name">{brandName}</span>
          </div>
          <button type="button" className="shell-drawer-close" aria-label="Fermer le menu" onClick={onClose}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" strokeLinecap="round" aria-hidden>
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>

        <button
          type="button"
          className="shell-drawer-search"
          onClick={() => {
            onClose()
            onOpenSearch()
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" strokeLinecap="round" aria-hidden>
            <circle cx="11" cy="11" r="7" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          Rechercher
        </button>

        {activeSpace && (
          <button type="button" className="shell-drawer-space" onClick={cycleSpace} data-mock-id="mobile-menu-space">
            <span className="shell-drawer-space-mark" aria-hidden />
            <span style={{ flexGrow: 1, textAlign: 'left' }}>
              <span className="shell-drawer-space-title">{activeSpace.name}</span>
              <span className="shell-drawer-space-sub" style={{ display: 'block' }}>
                Changer d&apos;espace
              </span>
            </span>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" aria-hidden>
              <polyline points="9 18 15 12 9 6" />
            </svg>
          </button>
        )}

        <div className="shell-drawer-nav">
          {view.documents.map((doc, i) => {
            const active = path === documentHref(doc.id) || path.startsWith(`/docs/${doc.id}/`)
            return (
              <Link
                key={doc.id}
                to={documentHref(doc.id)}
                className={`shell-drawer-link${active ? ' active' : ''}`}
                onClick={onClose}
                data-mock-id={i === 0 ? 'mobile-menu-active-doc' : undefined}
              >
                {doc.title}
              </Link>
            )
          })}

          {view.folders.map((node) => {
            const openFolder = expandedFolders.has(node.folder.id) || expandedFolders.size === 0
            return (
              <div key={node.folder.id}>
                <button type="button" className="shell-drawer-folder" onClick={() => toggleFolder(node.folder.id)}>
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" aria-hidden>
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                  {node.folder.name}
                </button>
                {openFolder &&
                  node.documents.map((doc) => (
                    <Link
                      key={doc.id}
                      to={documentHref(doc.id)}
                      className="shell-drawer-link indent"
                      onClick={onClose}
                    >
                      {doc.title}
                    </Link>
                  ))}
              </div>
            )
          })}

          {otherSpaces.length > 0 && (
            <>
              {otherSpaces.map((space) => {
                const idx = spaceList.findIndex((s) => s.id === space.id)
                const tree = trees[idx]?.data
                const rootDocs = (tree?.documents ?? []).filter((d) => d.folderId == null)
                return (
                  <div key={space.id}>
                    <div
                      className="shell-tree-section spaced"
                      style={{ padding: '16px 10px 6px' }}
                    >
                      {space.name}
                    </div>
                    {rootDocs.map((doc) => (
                      <Link
                        key={doc.id}
                        to={documentHref(doc.id)}
                        className="shell-drawer-link"
                        onClick={onClose}
                      >
                        {doc.title}
                      </Link>
                    ))}
                    {rootDocs.length === 0 && (
                      <Link
                        to={spaceBrowseHref(space.id)}
                        className="shell-drawer-link"
                        onClick={onClose}
                      >
                        Parcourir…
                      </Link>
                    )}
                  </div>
                )
              })}
            </>
          )}
        </div>

        <div className="shell-drawer-actions">
          <Link to="/docs/new" className="shell-drawer-action" onClick={onClose}>
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#3730E0" strokeWidth="2.4" strokeLinecap="round" aria-hidden>
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            Document
          </Link>
          <Link
            to={activeSpace ? spaceBrowseHref(activeSpace.id) : '/spaces'}
            className="shell-drawer-action"
            onClick={onClose}
          >
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#3730E0" strokeWidth="2.4" strokeLinecap="round" aria-hidden>
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            Dossier
          </Link>
        </div>

        <Link to="/trash" className="shell-drawer-trash" onClick={onClose}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <polyline points="3 6 5 6 21 6" />
            <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
            <path d="M10 11v6" />
            <path d="M14 11v6" />
          </svg>
          Corbeille
        </Link>

        <Link to="/team" className="shell-drawer-user" onClick={onClose} data-mock-id="mobile-menu-user">
          <span className="shell-avatar" aria-hidden>
            {initials}
          </span>{' '}
          <span>{displayName}</span>
        </Link>
      </div>
    </div>
  )
}
