// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { createDocument, emptyDocBody } from '../lib/documents'
import {
  ancestorFolderIds,
  buildSpaceTree,
  documentHref,
  folderHref,
  getSpaceTree,
  spaceBrowseHref,
  spaceTreeKey,
  statusLabel,
  type FolderTreeNode,
  type TreeDocument,
} from '../lib/folders'
import { NewFolderDialog } from './NewFolderDialog'

type Props = {
  spaceId: string
  /** Document ouvert (surligné, dossiers parents dépliés). */
  currentDocumentId?: string
  /** Dossier ouvert (surligné, dépliés jusqu'à lui). */
  currentFolderId?: string
}

const iconPlus = (
  <svg
    width="11"
    height="11"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2.4"
    strokeLinecap="round"
    aria-hidden
  >
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  </svg>
)

function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      width="11"
      height="11"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      aria-hidden
      className={`shrink-0 transition-transform ${open ? '' : '-rotate-90'}`}
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  )
}

/** Arborescence repliable de l'espace (maquette Main / FolderProcedures). */
export function SpaceTreeSidebar({ spaceId, currentDocumentId, currentFolderId }: Props) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [collapsed, setCollapsed] = useState(false)
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  const [newFolderOpen, setNewFolderOpen] = useState(false)

  const tree = useQuery({
    queryKey: spaceTreeKey(spaceId),
    queryFn: () => getSpaceTree(api, spaceId),
    enabled: Boolean(spaceId),
  })

  const folders = useMemo(() => tree.data?.folders ?? [], [tree.data])
  const view = useMemo(
    () => buildSpaceTree(tree.data ?? { folders: [], documents: [] }),
    [tree.data],
  )

  const currentDoc = useMemo(
    () => tree.data?.documents.find((d) => d.id === currentDocumentId),
    [tree.data, currentDocumentId],
  )
  const activeFolderId = currentFolderId ?? currentDoc?.folderId ?? null

  // Déplie les ancêtres du dossier courant / du dossier du document courant.
  const toExpand = ancestorFolderIds(folders, activeFolderId).join(',')
  useEffect(() => {
    if (!toExpand) return
    setExpanded((prev) => {
      const ids = toExpand.split(',')
      if (ids.every((id) => prev.has(id))) return prev
      return new Set([...prev, ...ids])
    })
  }, [toExpand])

  function toggle(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const createDoc = useMutation({
    mutationFn: () => createDocument(api, 'Sans titre', spaceId, emptyDocBody, activeFolderId),
    onSuccess: (doc) => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      navigate(documentHref(doc.id))
    },
  })

  if (collapsed) {
    return (
      <aside className="flex w-14 shrink-0 flex-col items-center border-r border-socle-line px-2 py-5">
        <button
          type="button"
          aria-label="Agrandir l'arborescence"
          onClick={() => setCollapsed(false)}
          className="flex h-[26px] w-[26px] items-center justify-center rounded-md border border-socle-line bg-white text-socle-slate hover:bg-[#F5F5F7]"
        >
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden
          >
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </button>
      </aside>
    )
  }

  return (
    <aside
      className="flex w-[268px] shrink-0 flex-col gap-5 border-r border-socle-line px-[22px] py-7"
      data-testid="space-tree-sidebar"
    >
      <div className="flex items-center justify-between gap-2">
        <Link
          to={spaceBrowseHref(spaceId)}
          className="min-w-0 truncate text-[11px] font-semibold uppercase tracking-[0.06em] text-socle-faint hover:text-socle-accent"
          title={tree.data?.spaceName}
        >
          {tree.data?.spaceName || 'Espace'}
        </Link>
        <button
          type="button"
          aria-label="Réduire l'arborescence"
          onClick={() => setCollapsed(true)}
          className="flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-md border border-socle-line bg-white text-socle-slate hover:bg-[#F5F5F7]"
        >
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden
          >
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
      </div>

      <nav aria-label="Arborescence de l'espace" className="min-h-0 flex-1 overflow-y-auto">
        {tree.isLoading && <p className="px-2.5 text-xs text-socle-muted">Chargement…</p>}
        {tree.isError && (
          <p className="px-2.5 text-xs text-socle-danger">Arborescence indisponible.</p>
        )}
        {tree.data && view.folders.length === 0 && view.documents.length === 0 && (
          <p className="px-2.5 text-xs text-socle-muted">Espace vide.</p>
        )}
        <ul className="flex flex-col gap-px">
          {view.folders.map((n) => (
            <FolderBranch
              key={n.folder.id}
              node={n}
              depth={0}
              expanded={expanded}
              onToggle={toggle}
              currentFolderId={currentFolderId}
              currentDocumentId={currentDocumentId}
            />
          ))}
          {view.documents.map((d) => (
            <DocumentRow key={d.id} doc={d} depth={0} active={d.id === currentDocumentId} />
          ))}
        </ul>
      </nav>

      <div className="flex gap-1.5">
        <button
          type="button"
          onClick={() => createDoc.mutate()}
          disabled={createDoc.isPending}
          className="flex flex-1 items-center justify-center gap-[5px] rounded-[7px] border border-socle-line px-1.5 py-[7px] text-xs font-semibold text-socle-accent hover:bg-[#F5F5F7] disabled:opacity-60"
        >
          {iconPlus}
          Document
        </button>
        <button
          type="button"
          onClick={() => setNewFolderOpen(true)}
          className="flex flex-1 items-center justify-center gap-[5px] rounded-[7px] border border-socle-line px-1.5 py-[7px] text-xs font-semibold text-socle-accent hover:bg-[#F5F5F7]"
        >
          {iconPlus}
          Dossier
        </button>
      </div>
      {createDoc.isError && (
        <p className="-mt-3 text-xs text-socle-danger">Création refusée — accès editor requis.</p>
      )}

      <NewFolderDialog
        open={newFolderOpen}
        onOpenChange={setNewFolderOpen}
        spaceId={spaceId}
        spaceName={tree.data?.spaceName ?? ''}
        folders={folders}
        defaultParentFolderId={activeFolderId}
        onCreated={(f) => navigate(folderHref(f.id))}
      />
    </aside>
  )
}

function FolderBranch({
  node,
  depth,
  expanded,
  onToggle,
  currentFolderId,
  currentDocumentId,
}: {
  node: FolderTreeNode
  depth: number
  expanded: Set<string>
  onToggle: (id: string) => void
  currentFolderId?: string
  currentDocumentId?: string
}) {
  const { folder } = node
  const isOpen = expanded.has(folder.id)
  const active = folder.id === currentFolderId
  const hasChildren = node.folders.length > 0 || node.documents.length > 0
  return (
    <li>
      <div
        style={{ paddingLeft: 4 + depth * 12 }}
        className={`mt-1 flex items-center rounded-[7px] pr-1 ${active ? 'bg-socle-mist' : 'hover:bg-[#F5F5F7]'}`}
      >
        <button
          type="button"
          aria-label={`${isOpen ? 'Replier' : 'Déplier'} ${folder.name}`}
          aria-expanded={isOpen}
          disabled={!hasChildren}
          onClick={() => onToggle(folder.id)}
          className={`flex h-6 w-5 shrink-0 items-center justify-center ${active ? 'text-socle-accent' : 'text-socle-muted'} disabled:opacity-30`}
        >
          <Chevron open={isOpen} />
        </button>
        <Link
          to={folderHref(folder.id)}
          aria-current={active ? 'page' : undefined}
          className={`min-w-0 flex-1 truncate py-2 pl-1 text-[11.5px] ${
            active ? 'font-bold text-socle-accent' : 'font-semibold text-socle-slate'
          }`}
        >
          {folder.name}
        </Link>
      </div>
      {isOpen && hasChildren && (
        <ul className="flex flex-col gap-px">
          {node.folders.map((child) => (
            <FolderBranch
              key={child.folder.id}
              node={child}
              depth={depth + 1}
              expanded={expanded}
              onToggle={onToggle}
              currentFolderId={currentFolderId}
              currentDocumentId={currentDocumentId}
            />
          ))}
          {node.documents.map((d) => (
            <DocumentRow key={d.id} doc={d} depth={depth + 1} active={d.id === currentDocumentId} />
          ))}
        </ul>
      )}
    </li>
  )
}

function DocumentRow({
  doc,
  depth,
  active,
}: {
  doc: TreeDocument
  depth: number
  active: boolean
}) {
  const status = statusLabel(doc.status)
  return (
    <li>
      <Link
        to={documentHref(doc.id)}
        aria-current={active ? 'page' : undefined}
        style={{ paddingLeft: 10 + depth * 12 + (depth > 0 ? 8 : 0) }}
        className={`flex items-center rounded-[7px] py-[7px] pr-2.5 text-[13.5px] ${
          active
            ? 'bg-socle-mist font-semibold text-socle-accent'
            : status
              ? 'text-socle-muted hover:bg-[#F5F5F7]'
              : 'text-[#4B4B52] hover:bg-[#F5F5F7]'
        }`}
      >
        <span className="min-w-0 flex-1 truncate">{doc.title || 'Sans titre'}</span>
        {status && <span className="ml-2 shrink-0 text-[10px] text-socle-faint">{status}</span>}
      </Link>
    </li>
  )
}

/** Mise en page : arborescence à gauche, contenu à droite. */
export function SpaceTreeLayout({
  spaceId,
  currentDocumentId,
  currentFolderId,
  children,
}: Props & { children: ReactNode }) {
  return (
    <div className="flex min-h-[calc(100vh-60px)]">
      <SpaceTreeSidebar
        spaceId={spaceId}
        currentDocumentId={currentDocumentId}
        currentFolderId={currentFolderId}
      />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  )
}
