// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthProvider'
import { initialsFromName } from '../components/shell/shellUtils'
import { api } from '../lib/api'
import {
  cannotDecideMessage,
  fetchApprovalDetail,
  formatSlaCountdown,
  listMyApprovals,
} from '../lib/approvals'
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
 * File « Mes approbations » (`/approvals`) et détail lecture/décision (`/approvals/:requestId`).
 * La maquette Approval.dc.html / MobileApproval.dc.html correspond au détail (décision).
 */
export function ApprovalsPage() {
  const { requestId } = useParams<{ requestId?: string }>()
  if (requestId) return <ApprovalDetailPage requestId={requestId} />
  return <ApprovalsListPage />
}

function ApprovalsListPage() {
  const isMobile = useIsMobile()
  const [nowMs, setNowMs] = useState(() => Date.now())
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
  } else if (items.length === 0) {
    content = (
      <div className="appr-state">
        <h1 className="appr-state-title">Mes approbations</h1>
        <p className="appr-state-text">Aucune demande en attente pour vous.</p>
      </div>
    )
  } else {
    content = (
      <div className="appr-list-wrap" data-testid="approvals-list">
        <h1 className="appr-state-title" style={{ marginBottom: 16 }}>
          Mes approbations
        </h1>
        <ul className="appr-switcher" aria-label="Demandes en attente">
          {items.map((it) => (
            <li key={it.approvalRequestId}>
              <Link
                to={`/approvals/${it.approvalRequestId}`}
                className="appr-switch"
                data-testid={`approval-list-item-${it.approvalRequestId}`}
              >
                <span>{it.documentTitle}</span>
                <span className="appr-switch-meta">{formatSlaCountdown(it.slaDeadlineAt, nowMs)}</span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    )
  }

  return (
    <div className="doc-page appr-page" data-testid="approvals-page" data-mock-id="appr-page">
      {isMobile ? (
        <HistoryMobileTop title="Approbation" backTo="/" mockPrefix="appr" />
      ) : (
        <HistoryTopBar
          mockPrefix="appr"
          crumbs={[{ label: 'Accueil', to: '/' }, { label: 'Approbation requise' }]}
        />
      )}
      {content}
    </div>
  )
}

function ApprovalDetailPage({ requestId }: { requestId: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()
  const { me } = useAuth()
  const [comment, setComment] = useState('')
  const [nowMs, setNowMs] = useState(() => Date.now())
  /** Étape affichée à l'ouverture — envoyée comme expectedStepOrder. */
  const seenStep = useRef<number | null>(null)

  const detail = useQuery({
    queryKey: ['approvals', 'detail', requestId],
    queryFn: () => fetchApprovalDetail(api, requestId),
    retry: false,
    refetchInterval: (q) => (q.state.error ? false : 5_000),
  })

  useEffect(() => {
    const id = window.setInterval(() => setNowMs(Date.now()), 30_000)
    return () => window.clearInterval(id)
  }, [])

  useEffect(() => {
    seenStep.current = null
    setComment('')
  }, [requestId])

  const selected = detail.data ?? null
  if (selected && seenStep.current == null) {
    seenStep.current = selected.currentStepOrder
  }
  const ctx = useApprovalContext(selected)

  const decision = useApprovalDecision({
    onDecided: () => {
      setComment('')
      seenStep.current = null
      void queryClient.invalidateQueries({ queryKey: ['approvals', 'detail', requestId] })
      navigate('/approvals')
    },
  })

  function decide(kind: 'approuve' | 'rejete') {
    if (!selected || !selected.canDecide) return
    decision.submit({
      item: selected,
      decision: kind,
      expectedStepOrder: seenStep.current ?? selected.currentStepOrder,
      comment,
    })
  }

  function reload() {
    seenStep.current = null
    decision.reset()
    setComment('')
    void queryClient.invalidateQueries({ queryKey: ['approvals', 'detail', requestId] })
    void queryClient.invalidateQueries({ queryKey: ['approvals', 'mine'] })
  }

  const meInitials = me?.avatarInitials || initialsFromName(me?.displayName ?? '') || '?'
  const canDecide = selected?.canDecide === true
  const readOnlyReason =
    selected && !canDecide
      ? cannotDecideMessage(selected.cannotDecideReason, selected.currentStepOrder)
      : null

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
        canDecide,
        readOnlyReason,
      }
    : null

  let content
  if (detail.isLoading) {
    content = (
      <div className="appr-state">
        <p className="appr-state-text">Chargement…</p>
      </div>
    )
  } else if (detail.isError || !panel) {
    content = (
      <div className="appr-state">
        <Link to="/approvals" className="hist-ghost">
          Retour aux approbations
        </Link>
        <p className="appr-state-text is-error" role="alert" style={{ marginTop: 16 }}>
          Impossible de charger cette demande d&apos;approbation.
        </p>
      </div>
    )
  } else if (isMobile) {
    content = <ApprovalMobile {...panel} />
  } else {
    content = <ApprovalDesktop {...panel} />
  }

  return (
    <div className="doc-page appr-page" data-testid="approvals-page" data-mock-id="appr-page">
      {isMobile ? (
        <HistoryMobileTop title="Approbation" backTo="/approvals" mockPrefix="appr" />
      ) : (
        <HistoryTopBar
          mockPrefix="appr"
          crumbs={[
            { label: 'Accueil', to: '/' },
            { label: 'Approbation requise', to: '/approvals' },
          ]}
        >
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
