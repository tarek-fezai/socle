// SPDX-License-Identifier: AGPL-3.0-or-later
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Breadcrumb } from '../components/Breadcrumb'
import { FavoriteStar } from '../components/shell/FavoriteStar'
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
  documentEditHref,
  folderHref,
  getSpaceTree,
  spaceTreeKey,
} from '../lib/folders'

const linkCls = 'font-semibold text-socle-accent hover:underline'

/** Racine d'un espace : arborescence à gauche, dossiers et documents racine à droite. */
export function SpaceBrowsePage() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [newFolderOpen, setNewFolderOpen] = useState(false)
  const [moveTarget, setMoveTarget] = useState<MoveTarget | null>(null)
  const [error, setError] = useState<string | null>(null)

  const tree = useQuery({
    queryKey: spaceTreeKey(spaceId),
    queryFn: () => getSpaceTree(api, spaceId),
    enabled: Boolean(spaceId),
  })

  const folders = useMemo(() => tree.data?.folders ?? [], [tree.data])
  const root = useMemo(
    () => childrenOf({ folders, documents: tree.data?.documents ?? [] }, null),
    [folders, tree.data],
  )
  const spaceName = tree.data?.spaceName ?? ''

  const createDoc = useMutation({
    mutationFn: () => createDocument(api, 'Sans titre', spaceId, emptyDocBody),
    onSuccess: (doc) => {
      void qc.invalidateQueries({ queryKey: spaceTreeKey(spaceId) })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      navigate(documentEditHref(doc.id))
    },
    onError: (e) =>
      setError(apiErrorMessage(e, 'Création refusée — accès editor requis sur l’espace')),
  })

  return (
    <SpaceTreeLayout spaceId={spaceId}>
      <main className="page-shell-wide">
        <div className="mb-8 flex flex-wrap items-start justify-between gap-4">
          <Breadcrumb items={buildBreadcrumb({ spaceId, spaceName, folders })} />
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" className="btn-ghost" onClick={() => setNewFolderOpen(true)}>
              + Nouveau dossier
            </button>
            <Link
              to={`/docs/new?spaceId=${encodeURIComponent(spaceId)}`}
              className="btn-ghost"
            >
              Depuis un modèle
            </Link>
            <button
              type="button"
              className="btn-primary"
              onClick={() => createDoc.mutate()}
              disabled={createDoc.isPending}
            >
              {createDoc.isPending ? 'Création…' : 'Nouveau document'}
            </button>
          </div>
        </div>

        <h1 className="serif-title flex items-center gap-3">
          {spaceName || 'Espace'}
          <FavoriteStar resourceType="space" resourceId={spaceId} />
        </h1>
        <div className="mb-6 mt-3 flex flex-wrap gap-4 text-sm">
          <Link to={`/spaces/${spaceId}`} className={linkCls}>
            Paramètres →
          </Link>
          <Link to={`/spaces/${spaceId}/graph`} className={linkCls}>
            Vue graphe →
          </Link>
          <Link to={`/spaces/${spaceId}/content-health`} className={linkCls}>
            Santé du contenu →
          </Link>
          <Link to={`/spaces/${spaceId}/access`} className={linkCls}>
            Gérer les accès →
          </Link>
        </div>

        {error && (
          <p className="mb-4 text-sm text-socle-danger" role="alert">
            {error}
          </p>
        )}
        {tree.isLoading && <p className="text-sm text-socle-muted">Chargement…</p>}
        {tree.isError && (
          <p className="text-sm text-socle-danger">
            Espace introuvable ou accès refusé.{' '}
            <Link to="/spaces" className="underline">
              ← Espaces
            </Link>
          </p>
        )}

        {tree.data && (
          <FolderContents
            folders={root.folders}
            documents={root.documents}
            parentId={null}
            onMove={setMoveTarget}
            emptyText="Cet espace est vide — créez un dossier ou un document pour commencer."
          />
        )}

        <NewFolderDialog
          open={newFolderOpen}
          onOpenChange={setNewFolderOpen}
          spaceId={spaceId}
          spaceName={spaceName}
          folders={folders}
          defaultParentFolderId={null}
          onCreated={(f) => navigate(folderHref(f.id))}
        />
        <MoveDialog
          open={moveTarget !== null}
          onOpenChange={(o) => {
            if (!o) setMoveTarget(null)
          }}
          spaceId={spaceId}
          spaceName={spaceName}
          folders={folders}
          target={moveTarget}
        />
      </main>
    </SpaceTreeLayout>
  )
}
