// SPDX-License-Identifier: AGPL-3.0-or-later
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import * as Dialog from '@radix-ui/react-dialog'
import { DocumentCrumbs, type Crumb } from './DocumentChrome'
import {
  currentChangeSummaryLabel,
  formatVersionDateTime,
  restoredVersionNo,
  type VersionAuthor,
} from './versionHistoryUtils'

/* ------------------------------------------------------------------ */
/* Barre haute (60 px) — History.dc.html / Diff.dc.html                 */
/* ------------------------------------------------------------------ */

/** Barre haute : fil d'Ariane à gauche, actions à droite (mêmes jetons que `.doc-topbar`). */
export function HistoryTopBar({
  crumbs,
  mockPrefix,
  children,
}: {
  crumbs: Crumb[]
  mockPrefix: 'hist' | 'diff'
  children?: ReactNode
}) {
  return (
    <div className="doc-topbar hist-topbar" data-mock-id={`${mockPrefix}-topbar`} data-testid={`${mockPrefix}-topbar`}>
      <DocumentCrumbs crumbs={crumbs} mockPrefix={mockPrefix} />
      <div className="hist-actions">{children}</div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Barre haute mobile (56 px) — MobileHistory.dc.html                   */
/* ------------------------------------------------------------------ */

export function HistoryMobileTop({ title, backTo }: { title: string; backTo: string }) {
  return (
    <div className="hist-mobile-top" data-mock-id="hist-mobile-top">
      <Link to={backTo} className="hist-mobile-back" aria-label="Retour">
        <svg
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#0E0E10"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden
        >
          <polyline points="15 18 9 12 15 6" />
        </svg>
      </Link>
      <div className="hist-mobile-title" data-mock-id="hist-mobile-title">
        {title}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Avatar                                                               */
/* ------------------------------------------------------------------ */

/** Pastille d'auteur : sombre = utilisateur courant, teintée = autre auteur, ⚙ = système. */
export function VersionAvatar({
  author,
  self,
  large,
  mockId,
}: {
  author: VersionAuthor
  self: boolean
  large?: boolean
  mockId?: string
}) {
  const tone = author.system ? 'system' : self ? 'self' : 'other'
  return (
    <span
      className={`hist-avatar hist-avatar--${tone}${large ? ' hist-avatar--lg' : ''}`}
      aria-hidden
      data-testid={author.system ? 'hist-avatar-system' : undefined}
      data-mock-id={mockId}
    >
      {author.initials}
    </span>
  )
}

/* ------------------------------------------------------------------ */
/* Modale « Restaurer la vX ? » — RestoreVersion.dc.html                */
/* ------------------------------------------------------------------ */

export type RestoreDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Version à restaurer. */
  target: { versionNo: number; createdAt: string; authorName: string } | null
  /** Version actuellement publiée. */
  currentVersionNo: number | null | undefined
  currentChangeSummary: string | null | undefined
  /** Statut du document : `valide` repasse en revue après restauration. */
  status?: string | null
  pending: boolean
  error: string | null
  onConfirm: () => void
}

export function RestoreDialog({
  open,
  onOpenChange,
  target,
  currentVersionNo,
  currentChangeSummary,
  status,
  pending,
  error,
  onConfirm,
}: RestoreDialogProps) {
  if (!target) return null
  const next = restoredVersionNo(currentVersionNo)
  const when = formatVersionDateTime(target.createdAt)
  return (
    <Dialog.Root open={open} onOpenChange={(o) => (pending ? undefined : onOpenChange(o))}>
      <Dialog.Portal>
        <Dialog.Overlay className="hist-modal-overlay" />
        <Dialog.Content className="hist-modal" data-testid="restore-dialog" data-mock-id="restore-modal">
          <div className="hist-modal-head">
            <span className="hist-modal-icon" aria-hidden>
              <svg
                width="17"
                height="17"
                viewBox="0 0 24 24"
                fill="none"
                stroke="#B7791F"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <polyline points="1 4 1 10 7 10" />
                <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
              </svg>
            </span>
            <Dialog.Title asChild>
              <h2 className="hist-modal-title" data-mock-id="restore-title">
                Restaurer la v{target.versionNo} ?
              </h2>
            </Dialog.Title>
          </div>

          <Dialog.Description asChild>
            <p className="hist-modal-text" data-mock-id="restore-text-1">
              Le contenu de la <strong>v{target.versionNo}</strong> ({when}, {target.authorName}) deviendra la
              nouvelle version courante du document
              {next != null ? (
                <>
                  , publiée sous le numéro <strong>v{next}</strong>
                </>
              ) : null}
              .
            </p>
          </Dialog.Description>
          {currentVersionNo != null && (
            <p className="hist-modal-text hist-modal-text--last" data-mock-id="restore-text-2">
              La version actuelle (<strong>v{currentVersionNo}</strong>) n&apos;est pas perdue : elle reste
              consultable et comparable dans l&apos;historique.
            </p>
          )}

          {currentVersionNo != null && (
            <div className="hist-modal-warning" data-mock-id="restore-warning">
              <span className="hist-modal-warning-dot" aria-hidden />
              <div className="hist-modal-warning-text">
                Les modifications propres à la v{currentVersionNo} ({currentChangeSummaryLabel(currentChangeSummary)})
                ne seront plus reflétées dans le contenu affiché.
              </div>
            </div>
          )}

          {status === 'valide' && (
            <p className="hist-modal-note" data-testid="restore-status-note">
              Ce document est actuellement <strong>valide</strong>. La restauration le fera repasser en{' '}
              <strong>en_revue</strong> — une nouvelle approbation sera nécessaire avant de republier.
            </p>
          )}
          {error && (
            <p className="hist-modal-error" role="alert" data-testid="restore-error">
              {error}
            </p>
          )}

          <div className="hist-modal-actions">
            <button
              type="button"
              className="hist-ghost"
              disabled={pending}
              onClick={() => onOpenChange(false)}
              data-mock-id="restore-cancel"
            >
              Annuler
            </button>
            <button
              type="button"
              className="hist-cta"
              disabled={pending}
              onClick={onConfirm}
              data-testid="restore-confirm"
              data-mock-id="restore-confirm"
            >
              {pending ? 'Restauration…' : 'Restaurer cette version'}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
