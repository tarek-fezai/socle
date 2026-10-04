// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { initialsFromName } from '../components/shell/shellUtils'
import { api } from '../lib/api'
import { formatSlaCountdown, listMyApprovals } from '../lib/approvals'
import { useIsMobile } from '../lib/useMediaQuery'
import { HistoryMobileTop, HistoryTopBar } from './document/DocumentHistoryChrome'
import { ApprovalDesktop, type ApprovalPanelProps } from './approvals/ApprovalDesktop'
import { ApprovalMobile } from './approvals/ApprovalMobile'
import { useApprovalContext } from './approvals/useApprovalContext'
import { useApprovalDecision } from './approvals/useApprovalDecision'
import './document/document-page.css'
import './document/document-history.css'
import './approvals/approvals.css'

/**
 * Écran « Approbation requise » (/approvals) — Approval.dc.html (desktop) et MobileApproval.dc.html.
 * Une seule demande est affichée à la fois ; un sélecteur n'apparaît que s'il y en a plusieurs.
 */
export function ApprovalsPage() {
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()
  const { me } = useAuth()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [comment, setComment] = useState('')
  const [nowMs, setNowMs] = useState(() => Date.now())
  /** Étape affichée à l'ouverture de chaque demande — envoyée comme expectedStepOrder. */
  const seenSteps = useRef(new Map<string, number>())

  const mine = useQuery({
    queryKey: ['approvals', 'mine'],
    queryFn: () => listMyApprovals(api),
    refetchInterval: 5_000,
  })

  useEffect(() => {
    const id = window.setInterval(() => setNowMs(Date.now()), 30_000)
    return () => window.clearInterval(id)
  }, [])

  const items = mine.data ?? []
  const selected = items.find((a) => a.approvalRequestId === selectedId) ?? items[0] ?? null
  if (selected && !seenSteps.current.has(selected.approvalRequestId)) {
    seenSteps.current.set(selected.approvalRequestId, selected.currentStepOrder)
  }
  const ctx = useApprovalContext(selected)

  const decision = useApprovalDecision({
    onDecided: () => {
      setComment('')
      setSelectedId(null)
      if (selected) seenSteps.current.delete(selected.approvalRequestId)
    },
  })

  function select(id: string) {
    setSelectedId(id)
    setComment('')
    decision.reset()
  }

  function decide(kind: 'approuve' | 'rejete') {
    if (!selected) return
    decision.submit({
      item: selected,
      decision: kind,
      expectedStepOrder: seenSteps.current.get(selected.approvalRequestId) ?? selected.currentStepOrder,
      comment,
    })
  }

  function reload() {
    if (selected) seenSteps.current.delete(selected.approvalRequestId)
    decision.reset()
    setSelectedId(null)
    setComment('')
    void queryClient.invalidateQueries({ queryKey: ['approvals', 'mine'] })
  }

  const meInitials = me?.avatarInitials || initialsFromName(me?.displayName ?? '') || '?'
  const panel: ApprovalPanelProps | null = selected
    ? {
        item: selected,
        ctx,
        nowMs,
        meInitials,
        comment,
        onComment: (v) => {
          setComment(v)
          if (decision.error) decision.clearError()
        },
        onApprove: () => decide('approuve'),
        onReject: () => decide('rejete'),
        deciding: decision.pending,
        actionError: decision.error,
        stepAdvanced: decision.stepAdvanced,
        onReload: reload,
      }
    : null

  const switcher = items.length > 1 && (
    <ul className="appr-switcher" aria-label="Demandes en attente" data-testid="approval-switcher">
      {items.map((it) => (
        <li key={it.approvalRequestId}>
          <button
            type="button"
            className={`appr-switch${it.approvalRequestId === selected?.approvalRequestId ? ' is-selected' : ''}`}
            aria-pressed={it.approvalRequestId === selected?.approvalRequestId}
            onClick={() => select(it.approvalRequestId)}
          >
            <span>{it.documentTitle}</span>
            <span className="appr-switch-meta">{formatSlaCountdown(it.slaDeadlineAt, nowMs)}</span>
          </button>
        </li>
      ))}
    </ul>
  )

  let content
  if (mine.isLoading) {
    content = (
      <div className="appr-state">
        <p className="appr-state-text">Chargement…</p>
      </div>
    )
  } else if (mine.isError) {
    content = (
      <div className="appr-state">
        <p className="appr-state-text is-error" role="alert">
          Impossible de charger vos approbations.
        </p>
      </div>
    )
  } else if (!panel) {
    content = (
      <div className="appr-state">
        <h1 className="appr-state-title">Mes approbations</h1>
        <p className="appr-state-text">Aucune demande en attente pour vous.</p>
      </div>
    )
  } else if (isMobile) {
    content = (
      <>
        {switcher && <div style={{ padding: '16px 16px 0' }}>{switcher}</div>}
        <ApprovalMobile {...panel} />
      </>
    )
  } else {
    content = <ApprovalDesktop {...panel} switcher={switcher || null} />
  }

  return (
    <div className="doc-page appr-page" data-testid="approvals-page" data-mock-id="appr-page">
      {isMobile ? (
        <HistoryMobileTop title="Approbation" backTo="/" mockPrefix="appr" />
      ) : (
        <HistoryTopBar mockPrefix="appr" crumbs={[{ label: 'Accueil', to: '/' }, { label: 'Approbation requise' }]}>
          {selected && (
            <Link
              to={`/docs/${selected.documentId}?comments=open`}
              className="hist-ghost"
              data-testid="approval-open-comments-link"
              data-mock-id="appr-comments"
            >
              Voir les commentaires
            </Link>
          )}
        </HistoryTopBar>
      )}
      {content}
    </div>
  )
}