import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DiffViewer } from '../components/DiffViewer'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  fetchVersionDiff,
  getDocument,
  listVersions,
  restoreVersion,
  type VersionSummary,
} from '../lib/documents'

export function DocumentHistoryPage() {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()
  const [compareA, setCompareA] = useState<number | null>(null)
  const [compareB, setCompareB] = useState<number | null>(null)
  const [restoreTarget, setRestoreTarget] = useState<VersionSummary | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [statusNote, setStatusNote] = useState<string | null>(null)

  const doc = useQuery({
    queryKey: ['document', id],
    queryFn: () => getDocument(api, id),
    enabled: Boolean(id),
  })

  const versions = useQuery({
    queryKey: ['document-versions', id],
    queryFn: () => listVersions(api, id),
    enabled: Boolean(id) && doc.isSuccess,
  })

  const currentVersionNo = doc.data?.currentVersionNo
  const status = doc.data?.status
  const isArchived = status === 'archive'
  const isValide = status === 'valide'

  const sorted = useMemo(() => {
    const items = versions.data?.items ?? []
    return [...items].sort((a, b) => b.versionNo - a.versionNo)
  }, [versions.data])

  const canCompare = compareA != null && compareB != null && compareA !== compareB

  const diff = useQuery({
    queryKey: ['document-diff', id, compareA, compareB],
    queryFn: () =>
      fetchVersionDiff(api, id, Math.min(compareA!, compareB!), Math.max(compareA!, compareB!)),
    enabled: Boolean(id) && canCompare,
  })

  const restore = useMutation({
    mutationFn: (versionNo: number) =>
      restoreVersion(api, id, versionNo, doc.data?.currentVersionNo ?? null),
    onSuccess: (updated) => {
      setActionError(null)
      setRestoreTarget(null)
      setStatusNote(
        updated.status === 'en_revue'
          ? `Version restaurée — statut ${updated.status} (nouvelle approbation requise si publication).`
          : `Version restaurée — statut ${updated.status}.`,
      )
      void queryClient.setQueryData(['document', id], updated)
      void queryClient.invalidateQueries({ queryKey: ['document-versions', id] })
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
      void queryClient.invalidateQueries({ queryKey: ['document', id] })
    },
    onError: (err) => {
      setActionError(apiErrorMessage(err, 'Échec de la restauration'))
    },
  })

  function toggleCompare(versionNo: number) {
    setActionError(null)
    if (compareA === versionNo) {
      setCompareA(null)
      return
    }
    if (compareB === versionNo) {
      setCompareB(null)
      return
    }
    if (compareA == null) {
      setCompareA(versionNo)
      return
    }
    if (compareB == null) {
      setCompareB(versionNo)
      return
    }
    setCompareA(compareB)
    setCompareB(versionNo)
  }

  if (doc.isLoading) {
    return <main className="page-shell text-socle-muted">Chargement…</main>
  }

  if (doc.isError || !doc.data) {
    return (
      <main className="page-shell">
        <div className="breadcrumb mb-6">
          <Link to="/docs">Documents</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Historique</span>
        </div>
        <p className="text-socle-danger">
          Accès refusé ou document introuvable — l&apos;historique n&apos;est pas accessible.
        </p>
      </main>
    )
  }

  return (
    <main className="page-shell">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="breadcrumb">
          <Link to={`/docs/${id}`}>Retour à la page</Link>
          <span className="text-[#DEDEE1]">→</span>
          <span className="font-medium text-socle-ink">Historique des versions</span>
        </div>
      </div>

      <h1 className="serif-title">Historique des versions</h1>
      <p className="mt-2 text-sm text-socle-muted">
        {doc.data.title} · statut {status}
        {currentVersionNo != null ? (
          <>
            {' '}
            · version courante{' '}
            <span className="font-medium text-socle-ink">v{currentVersionNo}</span>
          </>
        ) : null}
        {sorted.length > 0 ? (
          <>
            {' '}
            — {sorted.length} version{sorted.length === 1 ? '' : 's'} archivée
            {sorted.length === 1 ? '' : 's'}
          </>
        ) : null}
      </p>

      {isArchived && (
        <p className="mt-4 rounded-lg border border-[#F2CFC2] bg-[#FCEEEA] px-4 py-3 text-sm text-[#7C2D12]">
          Document archivé — la restauration est désactivée (le backend refuse toute mutation).
        </p>
      )}

      {versions.isLoading && <p className="mt-6 text-socle-muted">Chargement des versions…</p>}
      {versions.isError && (
        <p className="mt-6 text-socle-danger">
          {apiErrorMessage(versions.error, 'Impossible de charger l’historique.')}
        </p>
      )}
      {versions.isSuccess && sorted.length === 0 && (
        <p className="mt-6 text-socle-muted">
          Aucune version archivée pour l&apos;instant (seule la version courante v
          {currentVersionNo ?? '?'} existe).
        </p>
      )}

      <ul className="mt-8 space-y-0 border-l-2 border-socle-line pl-5">
        {sorted.map((v) => {
          const isCurrent = currentVersionNo != null && v.versionNo === currentVersionNo
          const selected = compareA === v.versionNo || compareB === v.versionNo
          return (
            <li key={v.versionNo} className="relative pb-6 last:pb-0">
              <span
                className={`absolute -left-[1.4rem] top-1.5 h-2.5 w-2.5 rounded-full border-2 border-white ${
                  isCurrent ? 'bg-socle-success' : 'bg-[#C2C2C6]'
                }`}
                aria-hidden
              />
              <div
                className={`rounded-xl border px-4 py-3 ${
                  isCurrent
                    ? 'border-[#D3EBD9] bg-[#F1F8F3]'
                    : selected
                      ? 'border-[#C7C6F5] bg-socle-mist'
                      : 'border-socle-line bg-white'
                }`}
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <p className="flex flex-wrap items-center gap-2 font-medium text-socle-ink">
                      v{v.versionNo}
                      {isCurrent && (
                        <span className="status-badge-ok">
                          <span className="h-1.5 w-1.5 rounded-full bg-socle-success" />
                          version courante
                        </span>
                      )}
                    </p>
                    <p className="mt-1 text-xs text-socle-muted">
                      {new Date(v.createdAt).toLocaleString('fr-FR')}
                      {v.authorId ? ` · auteur ${v.authorId.slice(0, 8)}…` : ' · auteur inconnu'}
                    </p>
                    <p className="mt-1 text-sm text-socle-slate">
                      {v.changeSummary?.trim() ? v.changeSummary : 'Aucun résumé'}
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => toggleCompare(v.versionNo)}
                      className={`rounded-lg border px-3 py-1.5 text-xs font-medium ${
                        selected
                          ? 'border-socle-accent text-socle-accent'
                          : 'border-socle-line text-socle-slate hover:bg-socle-soft'
                      }`}
                    >
                      {selected ? 'Sélectionnée' : 'Comparer'}
                    </button>
                    {!isCurrent && (
                      <button
                        type="button"
                        disabled={isArchived || restore.isPending}
                        title={
                          isArchived
                            ? 'Document archivé — restauration impossible'
                            : 'Restaurer cette version'
                        }
                        onClick={() => {
                          setActionError(null)
                          setStatusNote(null)
                          setRestoreTarget(v)
                        }}
                        className="rounded-lg border border-socle-line px-3 py-1.5 text-xs font-medium text-socle-slate hover:bg-socle-soft disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        Restaurer
                      </button>
                    )}
                  </div>
                </div>
                {!isCurrent && isArchived && (
                  <p className="mt-2 text-xs text-socle-danger">
                    Restauration désactivée : document archivé.
                  </p>
                )}
              </div>
            </li>
          )
        })}
      </ul>

      <section className="mt-10 rounded-xl border border-socle-line bg-white p-6">
        <h2 className="font-display text-2xl font-normal text-socle-ink">
          Comparer les versions
        </h2>
        <p className="mt-1 text-sm text-socle-muted">
          Sélectionnez deux versions dans la liste pour comparer.
          {canCompare
            ? ` (v${Math.min(compareA!, compareB!)} → v${Math.max(compareA!, compareB!)})`
            : ''}
        </p>
        {!canCompare && (
          <p className="mt-3 text-sm text-socle-muted">Deux versions distinctes requises.</p>
        )}
        {canCompare && (
          <div className="mt-4">
            <DiffViewer
              changes={diff.data?.changes ?? null}
              loading={diff.isLoading}
              error={diff.isError}
            />
          </div>
        )}
      </section>

      {restoreTarget && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="restore-title"
          className="fixed inset-0 z-20 flex items-center justify-center bg-socle-ink/40 px-4"
        >
          <div className="w-full max-w-md rounded-xl border border-socle-line bg-white p-6 shadow-lg">
            <h2 id="restore-title" className="font-display text-2xl font-normal text-socle-ink">
              Restaurer la v{restoreTarget.versionNo} ?
            </h2>
            <p className="mt-3 text-sm text-socle-slate">
              Le contenu courant sera archivé, puis remplacé par le snapshot de cette version. La
              version restaurée devient la nouvelle version courante ; l&apos;historique conserve
              les versions précédentes.
            </p>
            {isValide && (
              <p className="mt-3 rounded-lg bg-[#FBF3E7] px-3 py-2 text-sm text-socle-warn">
                Ce document est actuellement <strong>valide</strong>. La restauration le fera
                repasser en <strong>en_revue</strong> — une nouvelle approbation sera nécessaire
                avant de republier.
              </p>
            )}
            {actionError && <p className="mt-3 text-sm text-socle-danger">{actionError}</p>}
            <div className="mt-6 flex flex-wrap justify-end gap-2">
              <button
                type="button"
                disabled={restore.isPending}
                onClick={() => {
                  setRestoreTarget(null)
                  setActionError(null)
                }}
                className="btn-ghost"
              >
                Annuler
              </button>
              <button
                type="button"
                disabled={restore.isPending || isArchived}
                onClick={() => restore.mutate(restoreTarget.versionNo)}
                className="btn-primary"
              >
                {restore.isPending ? 'Restauration…' : 'Confirmer la restauration'}
              </button>
            </div>
          </div>
        </div>
      )}

      {statusNote && <p className="mt-6 text-sm text-socle-slate">{statusNote}</p>}
      {actionError && !restoreTarget && (
        <p className="mt-4 text-sm text-socle-danger">{actionError}</p>
      )}
    </main>
  )
}
