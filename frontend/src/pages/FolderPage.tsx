// SPDX-License-Identifier: AGPL-3.0-or-later
import { FormEvent, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Breadcrumb } from '../components/Breadcrumb'
import { FolderContents } from '../components/FolderContents'
import { MoveDialog, type MoveTarget } from '../components/MoveDialog'
import { NewFolderDialog } from '../components/NewFolderDialog'
import { SpaceTreeLayout } from '../components/SpaceTreeSidebar'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import { createDocument, emptyDocBody } from '../lib/documents'
import {
  buildBreadcrumb,
  childrenOf,
  deleteFolder,
  documentHref,
  folderHref,
  getFolder,
  getSpaceTree,
  spaceBrowseHref,
  spaceTreeKey,
  updateFolder,
} from '../lib/folders'

const ghostLink =
  'rounded-[7px] border border-socle-line px-3.5 py-[7px] text-[13px] font-medium text-[#43434A] hover:bg-[#F5F5F7]'

/** Contenu d'un dossier : sous-dossiers, documents, actions (maquette FolderProcedures). */
export function FolderPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const [newFolderOpen, setNewFolderOpen] = useState(false)
  const [moveTarget, setMoveTarget] = useState<MoveTarget | null>(null)
  const [renaming, setRenaming] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [error, setError] = useState<string | null>(null)

  const folder = useQuery({
    queryKey: ['folder', id],
    queryFn: () => getFolder(api, id),
    enabled: Boolean(id),
  })
  const spaceId = folder.data?.spaceId ?? ''

  const tree = useQuery({
    queryKey: spaceTreeKey(spaceId),
    queryFn: () => getSpaceTree(api, spaceId),
    enabled: Boolean(spaceId),
  })

  const treeFolders = useMemo(() => tree.data?.folders ?? [], [tree.data])
  const children = useMemo(
    () => childrenOf({ folders: treeFolders, documents: tree.data?.documents ?? [] }, id),
    [treeFolders, tree.data, id],
  )
  const current = treeFolders.find((f) => f.id === id)
  const parentFolderId = folder.data?.parentFolderId ?? current?.parentFolderId ?? null
  const spaceName = tree.data?.spaceName ?? ''

  const createDoc = useMutation({
    mutationFn: () => createDocument(api, 'Sans titre', spaceId, emptyDocBody, id),
    onSuccess: (doc) => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      navigate(documentHref(doc.id))
    },
    onError: (e) =>
      setError(apiErrorMessage(e, 'Création refusée — accès editor requis sur l’espace')),
  })

  const rename = useMutation({
    mutationFn: () => updateFolder(api, id, { name: nameDraft.trim() }),
    onSuccess: () => {
      setRenaming(false)
      setError(null)
      void qc.invalidateQueries({ queryKey: ['folder', id] })
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Renommage impossible')),
  })

  const remove = useMutation({
    mutationFn: () => deleteFolder(api, id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      navigate(parentFolderId ? folderHref(parentFolderId) : spaceBrowseHref(spaceId), {
        replace: true,
      })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Suppression impossible')),
  })

  function onRename(e: FormEvent) {
    e.preventDefault()
    if (nameDraft.trim() && nameDraft.trim() !== folder.data?.name) rename.mutate()
    else setRenaming(false)
  }

  function onDelete() {
    const n = current ? current.documentCount : children.documents.length
    const msg =
      `Supprimer le dossier « ${folder.data?.name} » ?\n` +
      `Ses sous-dossiers et ses documents (${n} directement) seront déplacés dans la corbeille.`
    if (window.confirm(msg)) remove.mutate()
  }

  if (folder.isLoading) {
    return <main className="page-shell text-socle-muted">Chargement…</main>
  }
  if (folder.isError || !folder.data) {
    return (
      <main className="page-shell">
        <Link to="/spaces" className="text-sm font-semibold text-socle-accent">
          ← Espaces
        </Link>
        <p className="mt-4 text-socle-danger">Dossier introuvable ou accès refusé.</p>
      </main>
    )
  }

  const f = folder.data
  const docCount = current?.documentCount ?? children.documents.length

  return (
    <SpaceTreeLayout spaceId={f.spaceId} currentFolderId={id}>
      <main className="page-shell-wide">
        <div className="mb-8 flex flex-wrap items-start justify-between gap-4">
          <Breadcrumb
            items={buildBreadcrumb({
              spaceId: f.spaceId,
              spaceName,
              folders: treeFolders,
              folderId: id,
              currentIsFolder: true,
            })}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Link to={`/folders/${id}/export`} className={ghostLink}>
              Exporter
            </Link>
            <Link to={`/folders/${id}/access`} className={ghostLink}>
              Accès
            </Link>
            <button
              type="button"
              className={ghostLink}
              onClick={() =>
                setMoveTarget({ kind: 'folder', id, name: f.name, currentParentId: parentFolderId })
              }
            >
              Déplacer
            </button>
            <button type="button" className={ghostLink} onClick={() => setNewFolderOpen(true)}>
              + Sous-dossier
            </button>
            <button
              type="button"
              className="btn-primary"
              onClick={() => createDoc.mutate()}
              disabled={createDoc.isPending}
            >
              {createDoc.isPending ? 'Création…' : 'Nouveau document ici'}
            </button>
          </div>
        </div>

        <div className="mb-1.5 flex items-center gap-2.5">
          {renaming ? (
            <form onSubmit={onRename} className="flex flex-1 items-center gap-2">
              <input
                className="field-input max-w-md font-display text-2xl"
                value={nameDraft}
                onChange={(e) => setNameDraft(e.target.value)}
                aria-label="Nom du dossier"
                autoFocus
                required
              />
              <button type="submit" className="btn-primary" disabled={rename.isPending}>
                Enregistrer
              </button>
              <button type="button" className="btn-ghost" onClick={() => setRenaming(false)}>
                Annuler
              </button>
            </form>
          ) : (
            <>
              <h1 className="serif-title">{f.name}</h1>
              <button
                type="button"
                aria-label="Renommer le dossier"
                onClick={() => {
                  setNameDraft(f.name)
                  setRenaming(true)
                }}
                className="flex h-7 w-7 items-center justify-center rounded-[7px] text-socle-muted hover:bg-[#F5F5F7]"
              >
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden
                >
                  <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
                </svg>
              </button>
            </>
          )}
        </div>

        <p className="mb-6 text-sm text-socle-muted">
          {docCount} document{docCount === 1 ? '' : 's'} ·{' '}
          {parentFolderId ? 'sous-dossier' : 'dossier'} de l&apos;espace{' '}
          <Link
            to={spaceBrowseHref(f.spaceId)}
            className="font-medium text-socle-accent hover:underline"
          >
            {spaceName || 'Espace'}
          </Link>
        </p>

        {error && (
          <p className="mb-4 text-sm text-socle-danger" role="alert">
            {error}
          </p>
        )}
        {tree.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
        {tree.isError && (
          <p className="text-sm text-socle-danger">Impossible de charger le contenu du dossier.</p>
        )}

        {tree.data && (
          <FolderContents
            folders={children.folders}
            documents={children.documents}
            parentId={id}
            onMove={setMoveTarget}
            emptyText="Ce dossier est vide — créez un document ou un sous-dossier."
          />
        )}

        <div className="mt-10 border-t border-socle-line pt-4">
          <button
            type="button"
            onClick={onDelete}
            disabled={remove.isPending}
            className="text-sm font-semibold text-socle-danger hover:underline disabled:opacity-60"
          >
            {remove.isPending ? 'Suppression…' : 'Supprimer le dossier'}
          </button>
        </div>

        <NewFolderDialog
          open={newFolderOpen}
          onOpenChange={setNewFolderOpen}
          spaceId={f.spaceId}
          spaceName={spaceName}
          folders={treeFolders}
          defaultParentFolderId={id}
        />
        <MoveDialog
          open={moveTarget !== null}
          onOpenChange={(o) => {
            if (!o) setMoveTarget(null)
          }}
          spaceId={f.spaceId}
          spaceName={spaceName}
          folders={treeFolders}
          target={moveTarget}
          onMoved={(t) => {
            // Le dossier courant a bougé : le fil d'Ariane est rafraîchi via l'invalidation.
            if (t.kind === 'folder' && t.id === id)
              void qc.invalidateQueries({ queryKey: ['folder', id] })
          }}
        />
      </main>
    </SpaceTreeLayout>
  )
}
