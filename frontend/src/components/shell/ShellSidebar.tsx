// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import {
  ancestorFolderIds,
  buildSpaceTree,
  documentHref,
  folderHref,
  getSpaceTree,
  spaceBrowseHref,
  spaceTreeKey,
  shellDocumentIndent,
  shellFolderIndent,
  type FolderTreeNode,
  type TreeDocument,
} from '../../lib/folders'
import { listSpaces } from '../../lib/spaces'
import {
  loadExpandedSpaces,
  saveExpandedSpaces,
  searchShortcutLabel,
} from './shellUtils'
import { useAuth } from '../../auth/AuthProvider'
import { loadDevicePreferences, saveDevicePreferences } from '../../lib/devicePreferences'
import { isPlatformAdmin } from '../../lib/platformRoles'
import { ShellUserMenu } from './ShellUserMenu'
import {
  useBrandingAccent,
  useBrandingInstanceName,
  useBrandingLogoUrl,
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

const iconPlus = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden>
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  </svg>
)

export function ShellSidebar({ onOpenSearch }: Props) {
  const location = useLocation()
  const { me } = useAuth()
  const admin = isPlatformAdmin(me?.roles)
  const [expanded, setExpanded] = useState<Set<string>>(() => loadExpandedSpaces())
  const [openFolders, setOpenFolders] = useState<Set<string>>(() => new Set())
  const [kbd, setKbd] = useState('⌘K')
  const [collapsed, setCollapsed] = useState(() => loadDevicePreferences().sidebarCollapsed)

  const toggleCollapsed = useCallback(() => {
    setCollapsed((prev) => {
      const next = !prev
      saveDevicePreferences({ sidebarCollapsed: next })
      return next
    })
  }, [])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== '\\' || e.altKey || e.shiftKey || !(e.metaKey || e.ctrlKey)) return
      const target = e.target
      if (target instanceof HTMLElement) {
        const tag = target.tagName
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable) return
      }
      e.preventDefault()
      toggleCollapsed()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [toggleCollapsed])

  useEffect(() => {
    setKbd(searchShortcutLabel())
  }, [])

  const spaces = useQuery({
    queryKey: ['spaces'],
    queryFn: () => listSpaces(api),
  })

  const spaceList = spaces.data ?? []

  const path = location.pathname
  const routeDocId = docIdFromPath(path)
  const routeFolderId = folderIdFromPath(path)
  const locateInTree = Boolean(routeDocId || routeFolderId)

  const trees = useQueries({
    queries: spaceList.map((s) => ({
      queryKey: spaceTreeKey(s.id),
      queryFn: () => getSpaceTree(api, s.id),
      enabled: locateInTree || expanded.has(s.id) || spaceList.length <= 8,
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

  function toggleFolder(id: string) {
    setOpenFolders((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const openTarget = useMemo(() => {
    for (const space of spaceList) {
      const tree = treeBySpace.get(space.id)
      if (!tree) continue
      if (routeFolderId && tree.folders.some((f) => f.id === routeFolderId)) {
        return { spaceId: space.id, folderIds: ancestorFolderIds(tree.folders, routeFolderId) }
      }
      if (routeDocId) {
        const doc = tree.documents.find((d) => d.id === routeDocId)
        if (doc) {
          return { spaceId: space.id, folderIds: ancestorFolderIds(tree.folders, doc.folderId) }
        }
      }
    }
    return null
  }, [spaceList, treeBySpace, routeFolderId, routeDocId])

  const toOpen = openTarget ? `${openTarget.spaceId}|${openTarget.folderIds.join(',')}` : ''
  useEffect(() => {
    if (!toOpen) return
    const [spaceId, raw = ''] = toOpen.split('|')
    const folderIds = raw ? raw.split(',').filter(Boolean) : []
    setExpanded((prev) => {
      if (prev.has(spaceId)) return prev
      const next = new Set(prev)
      next.add(spaceId)
      saveExpandedSpaces(next)
      return next
    })
    setOpenFolders((prev) => {
      if (folderIds.every((id) => prev.has(id))) return prev
      return new Set([...prev, ...folderIds])
    })
  }, [toOpen])
  const logoUrl = useBrandingLogoUrl()
  const accent = useBrandingAccent()
  const brandName = useBrandingInstanceName() || 'Socle'

  return (
    <aside
      className={`shell-sidebar shell-desktop-only${collapsed ? ' is-collapsed' : ''}`}
      data-mock-id="shell-sidebar"
      data-collapsed={collapsed ? 'true' : 'false'}
      aria-label="Navigation"
    >
      <div className="shell-brand">
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
          className="shell-collapse"
          aria-label="Réduire ou agrandir le menu"
          aria-expanded={!collapsed}
          onClick={toggleCollapsed}
        >
          <CollapseChevron points={collapsed ? '9 18 15 12 9 6' : '15 18 9 12 15 6'} />
        </button>
      </div>

      {!collapsed && (
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
      )}

      {!collapsed && (
        <Link
          to="/favorites"
          className={`shell-nav-item${path.startsWith('/favorites') ? ' active' : ''}`}
          data-mock-id="shell-nav-favorites"
        >
          {iconStar}
          Favoris
        </Link>
      )}

      {!collapsed && (
        <Link
          to="/spaces"
          className={`shell-nav-item${path === '/spaces' ? ' active' : ''}`}
          data-mock-id="shell-nav-spaces"
        >
          {iconSpaces}
          Tous les espaces
        </Link>
      )}

      {!collapsed && admin && (
        <Link
          to="/admin/team"
          className={`shell-nav-item${path.startsWith('/admin/team') || path === '/team' ? ' active' : ''}`}
          data-mock-id="shell-nav-team"
        >
          {iconTeam}
          Membres &amp; équipes
        </Link>
      )}

      {collapsed ? (
        <div className="shell-collapsed-rail">
          {spaceList.flatMap((space) => {
            if (!expanded.has(space.id)) return []
            const tree = treeBySpace.get(space.id)
            if (!tree) return []
            return collapsedEntries(buildSpaceTree(tree), openFolders, path, routeFolderId).map((item) => (
              <Link
                key={item.key}
                to={item.to}
                className={`shell-collapsed-item${item.active ? ' active' : ''}`}
                title={item.name}
                aria-label={item.name}
                aria-current={item.active ? 'page' : undefined}
              >
                <span className="shell-collapsed-dot" />
              </Link>
            ))
          })}
        </div>
      ) : null}

      {collapsed ? (
        <Link to="/docs/new" className="shell-collapsed-plus" title="Nouveau document ou dossier" aria-label="Nouveau document ou dossier">
          {iconPlus}
        </Link>
      ) : null}

      {!collapsed && (
      <div className="shell-tree" data-mock-id="shell-tree">
        {spaceList.map((space, idx) => {
          const open = expanded.has(space.id)
          const tree = treeBySpace.get(space.id)
          const view = tree ? buildSpaceTree(tree) : { folders: [], documents: [] }
          const empty = Boolean(tree) && view.documents.length === 0 && view.folders.length === 0
          return (
            <Fragment key={space.id}>
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
                view.documents.map((doc, di) => (
                  <DocLink
                    key={doc.id}
                    doc={doc}
                    depth={0}
                    path={path}
                    mockId={idx === 0 && di === 0 ? 'shell-tree-doc' : undefined}
                  />
                ))}
              {open &&
                view.folders.map((node) => (
                  <FolderBranch
                    key={node.folder.id}
                    node={node}
                    depth={0}
                    path={path}
                    openFolders={openFolders}
                    onToggle={toggleFolder}
                    currentFolderId={routeFolderId}
                  />
                ))}
              {open && empty && (
                <Link to={spaceBrowseHref(space.id)} className="shell-nav-link">
                  Parcourir…
                </Link>
              )}
            </Fragment>
          )
        })}
      </div>
      )}

      <ShellUserMenu />
    </aside>
  )
}

function docIdFromPath(path: string): string | null {
  const m = /^\/docs\/([^/]+)/.exec(path)
  if (!m || m[1] === 'new') return null
  return m[1]
}

function folderIdFromPath(path: string): string | null {
  const m = /^\/folders\/([^/]+)/.exec(path)
  return m?.[1] ?? null
}

function docIsActive(path: string, id: string) {
  return path === documentHref(id) || path.startsWith(`/docs/${id}/`)
}

function CollapseChevron({ points }: { points: string }) {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <polyline points={points} />
    </svg>
  )
}

type CollapsedEntry = { key: string; name: string; to: string; active: boolean }

/** Une icône par ligne d'arbre actuellement affichée (documents, puis dossiers ouverts). */
function collapsedEntries(
  view: { folders: FolderTreeNode[]; documents: TreeDocument[] },
  openFolders: Set<string>,
  path: string,
  currentFolderId: string | null,
): CollapsedEntry[] {
  const out: CollapsedEntry[] = []
  const pushDoc = (doc: TreeDocument) => {
    out.push({
      key: doc.id,
      name: doc.title,
      to: documentHref(doc.id),
      active: docIsActive(path, doc.id),
    })
  }
  const pushFolder = (node: FolderTreeNode) => {
    out.push({
      key: node.folder.id,
      name: node.folder.name,
      to: folderHref(node.folder.id),
      active: node.folder.id === currentFolderId,
    })
    if (!openFolders.has(node.folder.id)) return
    node.documents.forEach(pushDoc)
    node.folders.forEach(pushFolder)
  }
  view.documents.forEach(pushDoc)
  view.folders.forEach(pushFolder)
  return out
}

function Chevron() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
      <polyline points="6 9 12 15 18 9" />
    </svg>
  )
}

function DocLink({
  doc,
  depth,
  path,
  mockId,
}: {
  doc: TreeDocument
  depth: number
  path: string
  mockId?: string
}) {
  const active = docIsActive(path, doc.id)
  const draft = doc.status === 'brouillon'
  const nested = depth > 0
  return (
    <Link
      to={documentHref(doc.id)}
      style={nested ? { paddingLeft: shellDocumentIndent(depth) } : undefined}
      className={['shell-nav-link', draft ? 'shell-nav-link--draft' : '', active ? 'active' : '']
        .filter(Boolean)
        .join(' ')}
      data-mock-id={mockId}
      aria-current={active ? 'page' : undefined}
    >
      {draft ? (
        <>
          <span>{doc.title}</span>
          <span className="shell-draft-badge">brouillon</span>
        </>
      ) : (
        doc.title
      )}
    </Link>
  )
}

function FolderBranch({
  node,
  depth,
  path,
  openFolders,
  onToggle,
  currentFolderId,
}: {
  node: FolderTreeNode
  depth: number
  path: string
  openFolders: Set<string>
  onToggle: (id: string) => void
  currentFolderId: string | null
}) {
  const { folder } = node
  const open = openFolders.has(folder.id)
  const active = folder.id === currentFolderId
  return (
    <div className="shell-folder-branch" data-mock-id={active ? 'shell-tree-folders' : undefined}>
      <div
        className={`shell-folder-row${active ? ' active' : ''}`}
        style={depth > 0 ? { paddingLeft: shellFolderIndent(depth) } : undefined}
      >
        <button
          type="button"
          className="shell-folder-toggle"
          aria-expanded={open}
          aria-label={`${open ? 'Replier' : 'Déplier'} ${folder.name}`}
          onClick={() => onToggle(folder.id)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowRight' && !open) {
              e.preventDefault()
              onToggle(folder.id)
            } else if (e.key === 'ArrowLeft' && open) {
              e.preventDefault()
              onToggle(folder.id)
            }
          }}
        >
          <Chevron />
        </button>
        <Link
          to={folderHref(folder.id)}
          className="shell-folder-name"
          aria-current={active ? 'page' : undefined}
        >
          {folder.name}
        </Link>
      </div>
      {open &&
        node.documents.map((doc) => (
          <DocLink key={doc.id} doc={doc} depth={depth + 1} path={path} />
        ))}
      {open &&
        node.folders.map((child) => (
          <FolderBranch
            key={child.folder.id}
            node={child}
            depth={depth + 1}
            path={path}
            openFolders={openFolders}
            onToggle={onToggle}
            currentFolderId={currentFolderId}
          />
        ))}
    </div>
  )
}
