import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FolderReliabilityRail } from '../components/ReliabilityDisplay'
import { StaleBadge } from '../components/StaleBadge'
import { api } from '../lib/api'
import { createDocument, listDocuments } from '../lib/documents'
import { listSpaces } from '../lib/spaces'

/**
 * Liste documents + rail « Fiabilité ».
 * Création rattachée à un espace choisi dynamiquement (plus de DEFAULT_SPACE_ID).
 */
export function DocumentsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [spaceId, setSpaceId] = useState('')

  const spaces = useQuery({
    queryKey: ['spaces'],
    queryFn: async () => {
      const list = await listSpaces(api)
      if (!spaceId && list.length > 0) {
        setSpaceId(list[0].id)
      }
      return list
    },
  })

  const docs = useQuery({
    queryKey: ['documents'],
    queryFn: () => listDocuments(api, { limit: 20, offset: 0 }),
  })

  const create = useMutation({
    mutationFn: () => createDocument(api, 'Sans titre', spaceId),
    onSuccess: (doc) => {
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
      navigate(`/docs/${doc.id}`)
    },
  })

  const items = docs.data?.results ?? []
  const scores = items.map((d) => d.reliabilityScore)
  const valideCount = items.filter((d) => d.status === 'valide').length
  const revueCount = items.filter((d) => d.status === 'en_revue').length
  const caption =
    items.length === 0
      ? undefined
      : `${valideCount} document${valideCount === 1 ? '' : 's'} validé${valideCount === 1 ? '' : 's'} · ${revueCount} en revue`

  return (
    <main className="page-shell-wide">
      <div className="mb-8 flex items-center justify-between gap-4">
        <div>
          <div className="breadcrumb mb-3">
            <Link to="/">Accueil</Link>
            <span className="text-[#DEDEE1]">→</span>
            <span className="font-medium text-socle-ink">Documents</span>
          </div>
          <h1 className="serif-title">Documents</h1>
          <p className="mt-2 text-sm text-socle-muted">
            Verticale create / read / update — React → API → Postgres.
          </p>
          <p className="mt-2 text-sm">
            <Link to="/approvals" className="font-semibold text-socle-accent hover:underline">
              Mes approbations →
            </Link>
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select
            className="rounded-lg border border-socle-line bg-white px-3 py-2 text-sm"
            value={spaceId}
            onChange={(e) => setSpaceId(e.target.value)}
            aria-label="Espace de création"
          >
            {spaces.data?.length === 0 && <option value="">Aucun espace</option>}
            {spaces.data?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => create.mutate()}
            disabled={create.isPending || !spaceId}
            className="btn-primary"
          >
            {create.isPending ? 'Création…' : 'Nouveau document'}
          </button>
        </div>
      </div>

      {docs.isLoading && <p className="text-socle-muted">Chargement…</p>}
      {docs.isError && (
        <p className="text-socle-danger">
          Impossible de charger les documents (backend démarré ?).
        </p>
      )}
      {create.isError && (
        <p className="mb-4 text-socle-danger">
          Création refusée — accès editor requis sur l&apos;espace. Gérez les droits via{' '}
          <Link to={spaceId ? `/spaces/${spaceId}/access` : '/spaces'} className="underline">
            Accès
          </Link>
          .
        </p>
      )}

      <div className="flex flex-col gap-8 lg:flex-row lg:items-start">
        <div className="min-w-0 flex-1">
          {docs.data && docs.data.results.length === 0 && (
            <p className="rounded-xl border border-dashed border-[#DEDEE1] px-4 py-10 text-center text-sm text-socle-muted">
              Aucun document — créez-en un pour commencer.
            </p>
          )}

          <ul className="space-y-2">
            {docs.data?.results.map((doc) => (
              <li key={doc.id}>
                <Link
                  to={`/docs/${doc.id}`}
                  className="flex items-center justify-between rounded-xl border border-socle-line bg-white px-4 py-3 transition hover:border-[#C7C6F5] hover:bg-socle-mist/40"
                >
                  <span className="font-medium text-socle-ink">{doc.title}</span>
                  <span className="flex items-center gap-2">
                    <StaleBadge stale={doc.stale} contentModifiedAt={doc.contentModifiedAt} compact />
                    <span className="text-xs text-socle-muted">{doc.status}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
        <FolderReliabilityRail scores={scores} caption={caption} />
      </div>
    </main>
  )
}
