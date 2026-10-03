// SPDX-License-Identifier: AGPL-3.0-or-later
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { apiErrorMessage } from '../lib/approvals'
import {
  fetchVersionCompare,
  getDocument,
  listAllVersions,
  restoreVersion,
  type DocumentDetail,
} from '../lib/documents'
import { useIsMobile } from '../lib/useMediaQuery'
import { DocumentMobileTabs } from './document/DocumentChrome'
import { DiffView, type DiffMode, type FullContext } from './document/DiffView'
import { HistoryMobileTop, HistoryTopBar, RestoreDialog } from './document/DocumentHistoryChrome'
import { documentPermissions } from './document/documentPageUtils'
import { useHistoryTabProps } from './document/useHistoryTabProps'
import {
  buildVersionRows,
  isFoldedHunk,
  formatLinesAdded,
  formatLinesRemoved,
  versionAuthor,
} from './document/versionHistoryUtils'
import './document/document-page.css'
import './document/document-history.css'

function parseVersionParam(raw: string | null): number | null {
  if (raw == null || !/^\d+$/.test(raw)) return null
  const n = Number(raw)
  return n > 0 ? n : null
}

export function DocumentComparePage() {
  const { id = '' } = useParams()
  const doc = useQuery({
    queryKey: ['document', id],
    queryFn: () => getDocument(api, id),
    enabled: Boolean(id),
  })

  if (doc.isLoading) {
    return (
      <div className="doc-page doc-page--state" data-testid="compare-loading">
        <p className="doc-state">Chargement…</p>
      </div>
    )
  }
  if (doc.isError || !doc.data) {
    return (
      <div className="doc-page doc-page--state">
        <Link to="/docs" className="doc-back">
          ← Documents
        </Link>
        <p className="doc-state doc-state--error" role="alert">
          Accès refusé ou document introuvable — la comparaison n&apos;est pas accessible.
        </p>
      </div>
    )
  }
  return <CompareSurface doc={doc.data} />
}

function ChevronDown() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" aria-hidden>
      <polyline points="6 9 12 15 18 9" />
    </svg>
  )
}

function VersionSelect({
  label,
  value,
  versions,
  onChange,
  testId,
}: {
  label: string
  value: number | null
  versions: number[]
  onChange: (n: number) => void
  testId: string
}) {
  return (
    <label className="diff-sel" data-mock-id={`diff-sel-${testId}`}>
      <span className="diff-sel-label">{label}</span>
      <select
        className="diff-sel-select"
        value={value ?? ''}
        onChange={(e) => onChange(Number(e.target.value))}
        data-testid={`diff-select-${testId}`}
      >
        {value == null && <option value="" />}
        {versions.map((n) => (
          <option key={n} value={n}>
            v{n}
          </option>
        ))}
      </select>
      <ChevronDown />
    </label>
  )
}

function CompareSurface({ doc }: { doc: DocumentDetail }) {
  const id = doc.id
  const qc = useQueryClient()
  const navigate = useNavigate()
  const isMobile = useIsMobile()
  const [params, setParams] = useSearchParams()
  const perms = documentPermissions(doc.permissions)
  const isArchived = doc.status === 'archive'
  const tabProps = useHistoryTabProps(doc)

  // Mode d'affichage : état React uniquement (pas de persistance), unifié par défaut sur mobile.
  const [mode, setMode] = useState<DiffMode>(isMobile ? 'unified' : 'side')
  const [restoreOpen, setRestoreOpen] = useState(false)
  const [restoreError, setRestoreError] = useState<string | null>(null)

  const all = useQuery({
    queryKey: ['document-versions-all', id],
    queryFn: () => listAllVersions(api, id),
  })
  const { rows } = useMemo(
    () => buildVersionRows(all.data?.items ?? [], doc, { includeCurrent: all.isSuccess }),
    [all.data, all.isSuccess, doc],
  )
  const numbers = rows.map((r) => r.versionNo)

  // Valeurs par défaut : « Vers » = version courante, « Depuis » = sa précédente.
  const fallbackTo = rows[0]?.versionNo ?? doc.currentVersionNo ?? null
  const toParam = parseVersionParam(params.get('to'))
  const to = toParam ?? fallbackTo
  const fromParam = parseVersionParam(params.get('from'))
  const fallbackFrom = rows.find((r) => r.versionNo === to)?.previousVersionNo ?? null
  const from = fromParam ?? fallbackFrom

  const setSelection = (next: { from?: number | null; to?: number | null }) => {
    const p = new URLSearchParams(params)
    const f = next.from === undefined ? from : next.from
    const t = next.to === undefined ? to : next.to
    if (f != null) p.set('from', String(f))
    if (t != null) p.set('to', String(t))
    setParams(p, { replace: true })
  }

  const sameVersion = from != null && to != null && from === to
  const compare = useQuery({
    queryKey: ['document-compare', id, from, to],
    queryFn: () => fetchVersionCompare(api, id, from!, to!),
    enabled: from != null && to != null && !sameVersion,
  })

  // Blocs repliés par le serveur (sans lignes) : le premier dépliage charge la comparaison à contexte complet.
  const [wantFull, setWantFull] = useState(false)
  const hasFolded = compare.data?.hunks.some(isFoldedHunk) ?? false
  const fullCompare = useQuery({
    queryKey: ['document-compare-full', id, from, to],
    queryFn: () => fetchVersionCompare(api, id, from!, to!, { fullContext: true }),
    enabled: wantFull && hasFolded && from != null && to != null && !sameVersion,
    staleTime: 60_000,
  })
  const full: FullContext = fullCompare.isError
    ? { status: 'error' }
    : fullCompare.data
      ? { status: 'ready', data: fullCompare.data }
      : { status: wantFull ? 'loading' : 'idle' }

  const currentRow = rows.find((r) => r.isCurrent)
  const fromRow = rows.find((r) => r.versionNo === from)
  const canRestore = perms.canEdit && fromRow != null && !fromRow.isCurrent
  const historyHref = `/docs/${id}/history`
  const title = from != null && to != null ? `Comparer v${from} → v${to}` : 'Comparer les versions'

  const restore = useMutation({
    mutationFn: (versionNo: number) => restoreVersion(api, id, versionNo, doc.currentVersionNo ?? null),
    onSuccess: (updated) => {
      setRestoreError(null)
      setRestoreOpen(false)
      qc.setQueryData(['document', id], updated)
      void qc.invalidateQueries({ queryKey: ['document-versions', id] })
      void qc.invalidateQueries({ queryKey: ['document-versions-all', id] })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      void qc.invalidateQueries({ queryKey: ['document-resolved', id] })
      navigate(historyHref)
    },
    onError: (err) => setRestoreError(apiErrorMessage(err, 'Échec de la restauration')),
  })

  const added = compare.data ? formatLinesAdded(compare.data.added) : null
  const removed = compare.data ? formatLinesRemoved(compare.data.removed) : null

  const controls = (
    <div className="diff-controls" data-mock-id="diff-controls">
      <VersionSelect label="Depuis" value={from} versions={numbers} onChange={(n) => setSelection({ from: n })} testId="from" />
      <svg
        className="diff-arrow"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="#B0B0B5"
        strokeWidth="2"
        aria-hidden
      >
        <line x1="5" y1="12" x2="19" y2="12" />
        <polyline points="12 5 19 12 12 19" />
      </svg>
      <VersionSelect label="Vers" value={to} versions={numbers} onChange={(n) => setSelection({ to: n })} testId="to" />
      {added && (
        <span className="diff-added" data-mock-id="diff-added" data-testid="diff-added">
          {added}
        </span>
      )}
      {removed && (
        <span className="diff-removed" data-mock-id="diff-removed" data-testid="diff-removed">
          {removed}
        </span>
      )}
      <div className="diff-toggle" role="group" aria-label="Mode d’affichage" data-mock-id="diff-toggle">
        <button
          type="button"
          className={`diff-seg${mode === 'side' ? ' is-active' : ''}`}
          aria-pressed={mode === 'side'}
          onClick={() => setMode('side')}
          data-testid="diff-mode-side"
          data-mock-id="diff-seg-side"
        >
          Côte à côte
        </button>
        <button
          type="button"
          className={`diff-seg${mode === 'unified' ? ' is-active' : ''}`}
          aria-pressed={mode === 'unified'}
          onClick={() => setMode('unified')}
          data-testid="diff-mode-unified"
          data-mock-id="diff-seg-unified"
        >
          Unifié
        </button>
      </div>
    </div>
  )

  const body = (
    <div className="diff-body" data-mock-id="diff-body">
      {all.isError && (
        <p className="diff-state" role="alert">
          {apiErrorMessage(all.error, 'Impossible de charger les versions.')}
        </p>
      )}
      {sameVersion && (
        <p className="diff-state" data-testid="diff-same">
          Choisissez deux versions différentes à comparer.
        </p>
      )}
      {!sameVersion && from == null && all.isSuccess && (
        <p className="diff-state">Aucune version précédente à comparer.</p>
      )}
      {compare.isLoading && <p className="diff-state">Chargement de la comparaison…</p>}
      {compare.isError && (
        <p className="diff-state" role="alert">
          {apiErrorMessage(compare.error, 'Impossible de charger la comparaison.')}
        </p>
      )}
      {compare.data && !sameVersion && (
        <DiffView compare={compare.data} mode={mode} full={full} onRequestFull={() => setWantFull(true)} />
      )}
    </div>
  )

  const dialog = (
    <RestoreDialog
      open={restoreOpen}
      onOpenChange={(o) => {
        setRestoreOpen(o)
        if (!o) setRestoreError(null)
      }}
      target={
        fromRow
          ? { versionNo: fromRow.versionNo, createdAt: fromRow.createdAt, authorName: versionAuthor(fromRow).name }
          : null
      }
      currentVersionNo={doc.currentVersionNo}
      currentChangeSummary={currentRow?.changeSummary}
      status={doc.status}
      pending={restore.isPending}
      error={restoreError}
      onConfirm={() => fromRow && restore.mutate(fromRow.versionNo)}
    />
  )

  if (isMobile) {
    return (
      <div className="doc-page diff-page" data-testid="document-compare-page" data-mock-id="diff-page">
        <HistoryMobileTop title={title} backTo={historyHref} />
        {controls}
        {body}
        {canRestore && (
          <div className="diff-body">
            <button
              type="button"
              className="hist-cta"
              disabled={isArchived}
              onClick={() => setRestoreOpen(true)}
              data-testid="compare-restore"
            >
              Restaurer v{from}
            </button>
          </div>
        )}
        <DocumentMobileTabs {...tabProps} />
        {dialog}
      </div>
    )
  }

  return (
    <div className="doc-page diff-page" data-testid="document-compare-page" data-mock-id="diff-page">
      <HistoryTopBar
        mockPrefix="diff"
        crumbs={[{ label: 'Historique', to: historyHref }, { label: title }]}
      >
        <Link to={historyHref} className="hist-ghost" data-mock-id="diff-back">
          Retour à l&apos;historique
        </Link>
        {canRestore && (
          <button
            type="button"
            className="hist-cta"
            disabled={isArchived}
            title={isArchived ? 'Document archivé — restauration impossible' : undefined}
            onClick={() => setRestoreOpen(true)}
            data-testid="compare-restore"
            data-mock-id="diff-restore"
          >
            Restaurer v{from}
          </button>
        )}
      </HistoryTopBar>
      {controls}
      {body}
      <DocumentMobileTabs {...tabProps} />
      {dialog}
    </div>
  )
}
