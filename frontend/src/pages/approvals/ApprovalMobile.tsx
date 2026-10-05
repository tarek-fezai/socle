// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { formatSlaRemaining } from '../../lib/approvals'
import { JustificationField, type ApprovalPanelProps } from './ApprovalDesktop'
import { approvalDiffHref, circuitStepLabelMobile, requesterName } from './approvalsUtils'

/** MobileApproval.dc.html — contenu défilant + barre d'actions collée en bas. */
export function ApprovalMobile(p: ApprovalPanelProps) {
  const { item, ctx } = p
  const [justifOpen, setJustifOpen] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const { steps, simplified } = ctx.circuit
  const current = item.currentStepOrder
  const badge = simplified
    ? `Étape ${current} — En attente de vous`
    : `Étape ${current} sur ${steps.length} — En attente de vous`
  const links = item.impactedLinks ?? []
  const hiddenCount = item.hiddenImpactedCount ?? 0
  const hasCompare = item.baselineVersionNo != null && item.submittedVersionNo != null
  const canDecide = p.canDecide !== false
  const blocked = p.deciding || p.stepAdvanced
  const showJustif = canDecide && (justifOpen || Boolean(p.comment) || Boolean(p.actionError))
  const badgeText = canDecide
    ? badge
    : simplified
      ? `Étape ${current} — Lecture`
      : `Étape ${current} sur ${steps.length} — Lecture`

  function onRejectTap() {
    // Pas de champ dans la maquette : le premier appui ouvre la justification, le second envoie.
    if (!showJustif && !p.comment.trim()) {
      setJustifOpen(true)
      window.setTimeout(() => textareaRef.current?.focus(), 0)
      return
    }
    if (!p.comment.trim()) textareaRef.current?.focus()
    p.onReject()
  }

  return (
    <>
      <div className="appr-m-scroll" data-testid="approval-mobile">
        <span
          className={`appr-m-badge${canDecide ? '' : ' is-readonly'}`}
          data-mock-id="appr-m-badge"
          data-testid="approval-badge"
        >
          {badgeText}
        </span>

        <h1 className="appr-m-title" data-mock-id="appr-m-title">
          {item.documentTitle}
        </h1>
        <div className="appr-m-sub" data-mock-id="appr-m-sub">
          Demandé par {requesterName(item)} · {formatSlaRemaining(item.slaDeadlineAt, p.nowMs)}
        </div>

        <div className="appr-m-card" data-mock-id="appr-m-chain">
          <div className="appr-m-card-label" data-mock-id="appr-m-chain-label">
            Chaîne d&apos;approbation
          </div>
          <div className="appr-m-chain">
            {steps.map((s, i) => (
              <div className="appr-m-step" key={s.order} data-state={s.state}>
                <span className={`appr-m-node ${s.state === 'todo' ? 'is-todo' : 'is-reached'}`}>
                  {s.state !== 'todo' && (
                    <svg
                      width="11"
                      height="11"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="#FFFFFF"
                      strokeWidth="3"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                  )}
                </span>
                <span
                  className={`appr-m-step-name${s.state === 'todo' ? ' is-todo' : ''}`}
                  data-mock-id={`appr-m-step-${i + 1}`}
                >
                  {circuitStepLabelMobile(s)}
                  {s.state === 'current' && (
                    <>
                      {' — '}
                      <strong>vous</strong>
                    </>
                  )}
                </span>
              </div>
            ))}
          </div>
        </div>

        {hasCompare && (
          <Link
            to={approvalDiffHref(item.approvalRequestId)}
            className="appr-m-card appr-m-diff-link"
            data-testid="approval-compare-link"
            data-mock-id="appr-m-diff-link"
          >
            <span className="appr-m-diff-text" data-mock-id="appr-m-diff-text">
              Voir les modifications proposées
            </span>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#3730E0" strokeWidth="2" aria-hidden>
              <polyline points="9 18 15 12 9 6" />
            </svg>
          </Link>
        )}

        {ctx.summary && (
          <div className="appr-m-quote" data-mock-id="appr-m-quote">
            <p data-mock-id="appr-m-quote-text">{ctx.summary}</p>
          </div>
        )}

        {(links.length > 0 || hiddenCount > 0) && (
          <div className="appr-m-links" data-testid="approval-impacted-links">
            <div className="appr-m-card-label">Documents liés impactés</div>
            {links.map((l) => (
              <Link key={l.id} to={`/docs/${l.id}`} className="appr-link">
                {l.title}
              </Link>
            ))}
            {hiddenCount > 0 && (
              <p className="appr-hidden-links" data-testid="approval-hidden-links">
                {hiddenCount} document{hiddenCount > 1 ? 's' : ''} non accessible
                {hiddenCount > 1 ? 's' : ''}
              </p>
            )}
          </div>
        )}

        {!canDecide && p.readOnlyReason && (
          <p className="appr-readonly-reason" role="status" data-testid="approval-readonly-reason">
            {p.readOnlyReason}
          </p>
        )}

        {showJustif && (
          <div className="appr-m-justif">
            <div className="appr-label">Justification de la décision</div>
            <JustificationField
              comment={p.comment}
              onComment={p.onComment}
              invalid={Boolean(p.actionError)}
              inputRef={textareaRef}
            />
          </div>
        )}
        {canDecide && p.actionError && (
          <p className="appr-error" role="alert" data-testid="approval-error" style={{ marginTop: 0 }}>
            {p.actionError}
          </p>
        )}
        {canDecide && p.stepAdvanced && (
          <button type="button" onClick={p.onReload} className="hist-ghost appr-reload">
            Recharger
          </button>
        )}
      </div>

      {canDecide && (
        <div className="appr-m-bar" data-mock-id="appr-m-bar">
          <button
            type="button"
            className="appr-m-reject"
            disabled={blocked}
            onClick={onRejectTap}
            data-mock-id="appr-m-reject"
          >
            Rejeter
          </button>
          <button
            type="button"
            className="appr-m-approve"
            disabled={blocked}
            onClick={p.onApprove}
            data-mock-id="appr-m-approve"
          >
            {p.deciding ? 'Envoi…' : 'Approuver'}
          </button>
        </div>
      )}
    </>
  )
}
