// SPDX-License-Identifier: AGPL-3.0-or-later
import { useRef, type ReactNode, type Ref } from 'react'
import { Link } from 'react-router-dom'
import { formatSlaRail, type ApprovalItem } from '../../lib/approvals'
import { formatLinesAdded, formatLinesRemoved, formatVersionDateTime } from '../document/versionHistoryUtils'
import {
  CIRCUIT_STATUS_LABEL,
  approvalDiffHref,
  approveLabel,
  circuitStepLabel,
  compareTitle,
  ledeTail,
  requesterInitials,
  requesterName,
  revisionLabel,
} from './approvalsUtils'
import type { ApprovalContext } from './useApprovalContext'

export type ApprovalPanelProps = {
  item: ApprovalItem
  ctx: ApprovalContext
  nowMs: number
  /** Initiales de l'utilisateur courant (approbateur de l'étape en cours). */
  meInitials: string
  comment: string
  onComment: (v: string) => void
  onApprove: () => void
  onReject: () => void
  deciding: boolean
  actionError: string | null
  stepAdvanced: boolean
  onReload: () => void
}

function CheckIcon({ size, stroke }: { size: number; stroke: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="#FFFFFF"
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  )
}

function LockIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9B9BA1" strokeWidth="2" aria-hidden>
      <rect x="3" y="11" width="18" height="10" rx="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  )
}

/** Circuit d'approbation : N1 … Nn puis « Publication » verrouillée. */
function Circuit({ ctx, meInitials }: { ctx: ApprovalContext; meInitials: string }) {
  const { steps } = ctx.circuit
  return (
    <section className="appr-circuit" aria-label="Circuit d'approbation" data-mock-id="appr-circuit">
      <div className="appr-label" data-mock-id="appr-circuit-label">
        Circuit d&apos;approbation
      </div>
      <ol className="appr-steps" style={{ margin: 0, padding: 0, listStyle: 'none' }}>
        {steps.map((s, i) => (
          <li key={s.order} style={{ display: 'contents' }}>
            {i > 0 && <div className="appr-link-line" aria-hidden />}
            <div className="appr-step" data-testid={`appr-step-${s.order}`} data-state={s.state}>
              <div className={`appr-step-node is-${s.state}`} data-mock-id={`appr-step-${i + 1}-node`}>
                {s.state === 'done' && <CheckIcon size={15} stroke={2.5} />}
                {s.state === 'current' && <span className="appr-step-initials">{meInitials}</span>}
              </div>
              <div className="appr-step-name" data-mock-id={`appr-step-${i + 1}-name`}>
                {circuitStepLabel(s)}
              </div>
              <div className={`appr-step-status is-${s.state}`} data-mock-id={`appr-step-${i + 1}-status`}>
                {CIRCUIT_STATUS_LABEL[s.state]}
              </div>
            </div>
          </li>
        ))}
        <li style={{ display: 'contents' }}>
          <div className="appr-link-line is-dashed" aria-hidden />
          <div className="appr-step is-locked" data-testid="appr-step-publication">
            <div className="appr-step-node is-locked">
              <LockIcon />
            </div>
            <div className="appr-step-name" data-mock-id="appr-publication-name">
              Publication
            </div>
            <div className="appr-step-status" data-mock-id="appr-publication-status">
              Verrouillée
            </div>
          </div>
        </li>
      </ol>
    </section>
  )
}

export function JustificationField({
  comment,
  onComment,
  invalid,
  inputRef,
}: {
  comment: string
  onComment: (v: string) => void
  invalid: boolean
  inputRef?: Ref<HTMLTextAreaElement>
}) {
  return (
    <div className={`appr-field${invalid ? ' is-invalid' : ''}`} data-mock-id="appr-field">
      <textarea
        ref={inputRef}
        value={comment}
        onChange={(e) => onComment(e.target.value)}
        placeholder="Facultatif pour une approbation, requis pour un refus…"
        aria-label="Justification de la décision"
        aria-invalid={invalid || undefined}
        data-testid="approval-comment"
      />
    </div>
  )
}

export function ApprovalDesktop(p: ApprovalPanelProps & { switcher?: ReactNode }) {
  const { item, ctx } = p
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const name = requesterName(item)
  const links = item.impactedLinks ?? []
  const hasCompare = item.baselineVersionNo != null && item.submittedVersionNo != null
  const added = formatLinesAdded(ctx.added)
  const removed = formatLinesRemoved(ctx.removed)
  const blocked = p.deciding || p.stepAdvanced

  return (
    <div className="appr-body">
      <div className="appr-main">
        <div className="appr-column">
          {p.switcher}
          <div className="appr-badge" data-mock-id="appr-badge" data-testid="approval-badge">
            <span className="appr-badge-dot" aria-hidden />
            EN ATTENTE DE VOTRE DÉCISION
          </div>
          <h1 className="appr-title" data-mock-id="appr-title">
            {item.documentTitle}
          </h1>
          <p className="appr-lede" data-mock-id="appr-lede">
            {name} demande l&apos;approbation de la révision{' '}
            <strong>{revisionLabel(item.submittedVersionNo)}</strong>
            {ledeTail(ctx.summary)}.
          </p>

          <Circuit ctx={ctx} meInitials={p.meInitials} />

          <div className="appr-label appr-justif-label" data-mock-id="appr-justif-label">
            Justification de la décision
          </div>
          <JustificationField
            comment={p.comment}
            onComment={p.onComment}
            invalid={Boolean(p.actionError)}
            inputRef={textareaRef}
          />
          {p.actionError && (
            <p className="appr-error" role="alert" data-testid="approval-error">
              {p.actionError}
            </p>
          )}
          {p.stepAdvanced && (
            <button type="button" onClick={p.onReload} className="hist-ghost appr-reload">
              Recharger
            </button>
          )}

          <div className="appr-actions">
            <button
              type="button"
              className="appr-btn appr-approve"
              disabled={blocked}
              onClick={p.onApprove}
              data-mock-id="appr-approve"
            >
              {p.deciding ? 'Envoi…' : approveLabel(item.submittedVersionNo)}
            </button>
            <button
              type="button"
              className="appr-btn appr-reject"
              disabled={blocked}
              onClick={() => {
                if (!p.comment.trim()) textareaRef.current?.focus()
                p.onReject()
              }}
              data-mock-id="appr-reject"
            >
              Refuser
            </button>
          </div>
        </div>
      </div>

      <aside className="appr-rail" aria-label="Informations de la demande" data-mock-id="appr-rail">
        <div>
          <div className="appr-rail-label" data-mock-id="appr-rail-label-requester">
            Demandeur
          </div>
          <div className="appr-requester">
            <div className="appr-avatar" data-mock-id="appr-requester-avatar" aria-hidden>
              {requesterInitials(item)}
            </div>
            <span className="appr-requester-name" data-mock-id="appr-requester-name">
              {name}
            </span>
          </div>
        </div>

        <div>
          <div className="appr-rail-label" data-mock-id="appr-rail-label-submitted">
            Soumis le
          </div>
          <div className="appr-rail-value" data-mock-id="appr-submitted">
            {formatVersionDateTime(item.createdAt)}
          </div>
        </div>

        <div>
          <div className="appr-rail-label" data-mock-id="appr-rail-label-sla">
            Échéance
          </div>
          <div
            className="appr-rail-value is-sla"
            data-mock-id="appr-sla"
            data-testid="approval-sla"
            title={item.slaDeadlineAt ? new Date(item.slaDeadlineAt).toLocaleString('fr-FR') : undefined}
          >
            {formatSlaRail(item.slaDeadlineAt, item.createdAt, p.nowMs)}
          </div>
        </div>

        <div>
          <div className="appr-rail-label" data-mock-id="appr-rail-label-changes">
            Modifications
          </div>
          {hasCompare ? (
            <Link
              to={approvalDiffHref(item.approvalRequestId)}
              className="appr-compare-link"
              data-testid="approval-compare-link"
              data-mock-id="appr-compare-link"
            >
              <span className="appr-compare-label" data-mock-id="appr-compare-label">
                {compareTitle(item.baselineVersionNo, item.submittedVersionNo)}
              </span>
              {(added || removed) && (
                <span className="appr-compare-counts" data-mock-id="appr-compare-counts">
                  {[added, removed].filter(Boolean).join(' ')}
                </span>
              )}
            </Link>
          ) : (
            <div className="appr-rail-muted" data-testid="approval-no-baseline">
              Première soumission — pas de version approuvée à comparer
            </div>
          )}
        </div>

        <div>
          <div className="appr-rail-label" data-mock-id="appr-rail-label-links">
            Documents liés impactés
          </div>
          {links.length > 0 ? (
            <div className="appr-links" data-testid="approval-impacted-links">
              {links.map((l, i) => (
                <Link
                  key={l.id}
                  to={`/docs/${l.id}`}
                  className="appr-link"
                  data-mock-id={`appr-link-${i}`}
                >
                  {l.title}
                </Link>
              ))}
            </div>
          ) : (
            <div className="appr-rail-muted">Aucun document lié impacté</div>
          )}
        </div>
      </aside>
    </div>
  )
}
