// SPDX-License-Identifier: AGPL-3.0-or-later
import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { apiErrorMessage } from '../../lib/apiError'
import { fetchApprovalDetail, type ApprovalItem } from '../../lib/approvals'
import { fetchVersionCompare, listAllVersions } from '../../lib/documents'
import { useIsMobile } from '../../lib/useMediaQuery'
import { DiffView, type FullContext } from '../document/DiffView'
import { HistoryMobileTop, HistoryTopBar } from '../document/DocumentHistoryChrome'
import { formatLinesAdded, formatLinesRemoved, isFoldedHunk } from '../document/versionHistoryUtils'
import '../document/document-page.css'
import '../document/document-history.css'
import './approvals.css'
import { approveLabel, compareTitle } from './approvalsUtils'
import { useApprovalDecision } from './useApprovalDecision'

function parseVersionParam(raw: string | null): number | null {
  if (raw == null || !/^\d+$/.test(raw)) return null
  const n = Number(raw)
  return n > 0 ? n : null
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
  testId: 'from' | 'to'
}) {
  return (
    <label className="diff-sel" data-mock-id={`adiff-sel-${testId}`}>
      <span className="diff-sel-label">{label}</span>
      <select
        className="diff-sel-select"
        value={value ?? ''}
        onChange={(e) => onChange(Number(e.target.value))}
        data-testid={`adiff-select-${testId}`}
      >
        {value == null && <option value="" />}
        {versions.map((n) => (
          <option key={n} value={n}>
            v{n}
          </option>
        ))}
      </select>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" aria-hidden>
        <polyline points="6 9 12 15 18 9" />
      </svg>
    </label>
  )
}

/**
 * Comparaison d'une révision soumise (/approvals/:requestId/diff) — DiffApproval.dc.html.
 * Utilise la comparaison ligne à ligne (`?mode=lines`) comme l'écran Historique → Comparer.
 */
export function ApprovalDiffPage() {
  const { requestId = '' } = useParams()
  const detail = useQuery({
    queryKey: ['approvals', 'detail', requestId],
    queryFn: () => fetchApprovalDetail(api, requestId),
    enabled: Boolean(requestId),
    retry: false,
    refetchInterval: (q) => (q.state.error ? false : 5_000),
  })
  const item = detail.data ?? null

  if (detail.isLoading) {
    return (
      <div className="doc-page adiff-page" data-testid="approval-diff-loading">
        <div className="appr-state">
          <p className="appr-state-text">Chargement…</p>
        </div>
      </div>
    )
  }
  if (detail.isError || !item) {
    return (
      <div className="doc-page adiff-page" data-testid="approval-diff-missing">
        <div className="appr-state">
          <Link to="/approvals" className="hist-ghost">
            Retour aux approbations
          </Link>
          <p className="appr-state-text is-error" role="alert" style={{ marginTop: 16 }}>
            {detail.isError
              ? 'Impossible de charger cette demande d’approbation.'
              : 'Cette demande est introuvable ou inaccessible.'}
          </p>
        </div>
      </div>
    )
  }
  return <DiffSurface item={item} canDecide={item.canDecide} />
}

function DiffSurface({ item, canDecide }: { item: ApprovalItem; canDecide: boolean }) {
  const navigate = useNavigate()
  const isMobile = useIsMobile()
  const [params, setParams] = useSearchParams()
  const id = item.documentId
  const detailHref = `/approvals/${item.approvalRequestId}`
  const expectedStep = useRef(item.currentStepOrder)

  const all = useQuery({
    queryKey: ['document-versions-all', id],
    queryFn: () => listAllVersions(api, id),
  })
  const numbers = useMemo(
    () =>
      (all.data?.items ?? [])
        .map((v) => v.versionNo)
        .filter((n): n is number => typeof n === 'number')
        .sort((a, b) => b - a),
    [all.data],
  )

  const from = parseVersionParam(params.get('from')) ?? item.baselineVersionNo
  const to = parseVersionParam(params.get('to')) ?? item.submittedVersionNo
  const setSelection = (next: { from?: number; to?: number }) => {
    const p = new URLSearchParams(params)
    p.set('from', String(next.from ?? from ?? ''))
    p.set('to', String(next.to ?? to ?? ''))
    setParams(p, { replace: true })
  }

  const sameVersion = from != null && to != null && from === to
  const compare = useQuery({
    queryKey: ['document-compare', id, from, to],
    queryFn: () => fetchVersionCompare(api, id, from!, to!),
    enabled: from != null && to != null && !sameVersion,
  })
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

  const decision = useApprovalDecision({ onDecided: () => navigate('/approvals') })
  const approve = () => {
    if (!canDecide) return
    decision.submit({ item, decision: 'approuve', expectedStepOrder: expectedStep.current, comment: '' })
  }

  const title = compareTitle(from, to)
  const added = compare.data ? formatLinesAdded(compare.data.added) : null
  const removed = compare.data ? formatLinesRemoved(compare.data.removed) : null
  const cta = approveLabel(item.submittedVersionNo)
  const busy = decision.pending || decision.stepAdvanced

  const controls = (
    <div className="diff-controls" data-mock-id="adiff-controls">
      <VersionSelect label="Depuis" value={from} versions={numbers} onChange={(n) => setSelection({ from: n })} testId="from" />
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#B0B0B5" strokeWidth="2" aria-hidden>
        <line x1="5" y1="12" x2="19" y2="12" />
        <polyline points="12 5 19 12 12 19" />
      </svg>
      <VersionSelect label="Vers" value={to} versions={numbers} onChange={(n) => setSelection({ to: n })} testId="to" />
      {added && (
        <span className="diff-added" data-mock-id="adiff-added" data-testid="adiff-added">
          {added}
        </span>
      )}
      {removed && (
        <span className="diff-removed" data-mock-id="adiff-removed" data-testid="adiff-removed">
          {removed}
        </span>
      )}
      <div className="adiff-badge" data-mock-id="adiff-badge">
        <span className="adiff-badge-dot" aria-hidden />
        RÉVISION EN ATTENTE D&apos;APPROBATION
      </div>
    </div>
  )

  const body = (
    <div className="diff-body" data-mock-id="adiff-body">
      {all.isError && (
        <p className="diff-state" role="alert">
          {apiErrorMessage(all.error, 'Impossible de charger les versions.')}
        </p>
      )}
      {from == null && (
        <p className="diff-state" data-testid="adiff-no-baseline">
          Première soumission : aucune version approuvée à comparer.
        </p>
      )}
      {sameVersion && (
        <p className="diff-state" data-testid="diff-same">
          Choisissez deux versions différentes à comparer.
        </p>
      )}
      {compare.isLoading && <p className="diff-state">Chargement de la comparaison…</p>}
      {compare.isError && (
        <p className="diff-state" role="alert">
          {apiErrorMessage(compare.error, 'Impossible de charger la comparaison.')}
        </p>
      )}
      {compare.data && !sameVersion && (
        <DiffView
          compare={compare.data}
          mode={isMobile ? 'unified' : 'side'}
          full={full}
          onRequestFull={() => setWantFull(true)}
        />
      )}
    </div>
  )

  const error = decision.error && (
    <p className="adiff-error" role="alert" data-testid="approval-error">
      {decision.error}
    </p>
  )

  if (isMobile) {
    return (
      <div className="doc-page diff-page adiff-page" data-testid="approval-diff-page" data-mock-id="adiff-page">
        <HistoryMobileTop title={title} backTo={detailHref} mockPrefix="adiff" />
        {controls}
        {error}
        {body}
        {canDecide && (
          <div className="diff-body">
            <button type="button" className="hist-cta" disabled={busy} onClick={approve} data-testid="adiff-approve">
              {decision.pending ? 'Envoi…' : cta}
            </button>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="doc-page diff-page adiff-page" data-testid="approval-diff-page" data-mock-id="adiff-page">
      <HistoryTopBar
        mockPrefix="adiff"
        crumbs={[{ label: 'Approbation', to: detailHref }, { label: title }]}
      >
        <Link to={detailHref} className="hist-ghost" data-mock-id="adiff-back">
          Retour à l&apos;approbation
        </Link>
        {canDecide && (
          <button
            type="button"
            className="hist-cta"
            disabled={busy}
            onClick={approve}
            data-testid="adiff-approve"
            data-mock-id="adiff-approve"
          >
            {decision.pending ? 'Envoi…' : cta}
          </button>
        )}
      </HistoryTopBar>
      {controls}
      {error}
      {body}
    </div>
  )
}
