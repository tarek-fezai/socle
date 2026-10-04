// SPDX-License-Identifier: AGPL-3.0-or-later
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ChangeSummaryPopover } from './ChangeSummaryPopover'
import { saveStatusText, type PresenceAvatar, type SaveStatus } from './documentEditUtils'
import type { Crumb } from './DocumentChrome'

/* ------------------------------------------------------------------ */
/* Présence                                                             */
/* ------------------------------------------------------------------ */

/** Avatars de présence (24 px, bord blanc) — co-édition hors V1 : un seul détenteur du verrou. */
export function PresenceAvatars({ avatars }: { avatars: PresenceAvatar[] }) {
  if (avatars.length === 0) return null
  const title =
    avatars.length === 1
      ? avatars[0]!.self
        ? 'Vous modifiez ce document'
        : `${avatars[0]!.name} modifie ce document`
      : `${avatars.map((a) => a.name).join(', ')} consultent ce document`
  return (
    <div className="edit-presence" title={title} data-mock-id="edit-presence" data-testid="edit-presence">
      {avatars.map((a) => (
        <div
          key={a.key}
          className={`edit-avatar${a.self ? ' is-self' : ' is-other'}`}
          data-testid={a.self ? 'edit-avatar-self' : 'edit-avatar-holder'}
          aria-label={a.self ? `${a.name} (vous)` : a.name}
        >
          {a.initials}
        </div>
      ))}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Barre haute (60 px)                                                  */
/* ------------------------------------------------------------------ */

export type EditTopBarProps = {
  crumbs: Crumb[]
  save: SaveStatus
  onRetrySave: () => void
  wordStats: string
  presence: PresenceAvatar[]
  previewing: boolean
  onTogglePreview: () => void
  /** Libellé du bouton principal (« Envoyer en révision », « Envoi… », « En révision »). */
  reviewLabel: string
  reviewDisabled: boolean
  reviewReason: string | null
  onSendReview: (changeSummary: string) => void
  saveVersionDisabled: boolean
  onSaveVersion: (changeSummary: string) => void
}

export function EditTopBar({
  crumbs,
  save,
  onRetrySave,
  wordStats,
  presence,
  previewing,
  onTogglePreview,
  reviewLabel,
  reviewDisabled,
  reviewReason,
  onSendReview,
  saveVersionDisabled,
  onSaveVersion,
}: EditTopBarProps) {
  return (
    <div className="edit-topbar" data-mock-id="edit-topbar" data-testid="edit-topbar">
      <div className="edit-meta">
        <nav className="edit-crumbs" aria-label="Fil d'Ariane" data-mock-id="edit-breadcrumb">
          {crumbs.map((c, i) => {
            const last = i === crumbs.length - 1
            return (
              <span key={`${c.label}-${i}`} className="edit-crumb-wrap">
                {i > 0 && <span className="edit-crumb-sep">→</span>}
                {last || !c.to ? (
                  <span
                    className={last ? 'edit-crumb is-current' : 'edit-crumb'}
                    aria-current={last ? 'page' : undefined}
                    data-mock-id={last ? 'edit-breadcrumb-current' : undefined}
                  >
                    {c.label}
                  </span>
                ) : (
                  <Link to={c.to} className="edit-crumb">
                    {c.label}
                  </Link>
                )}
              </span>
            )
          })}
        </nav>
        <span className="edit-dot" aria-hidden />
        <span
          className={`edit-status${save.kind === 'error' ? ' is-error' : ''}`}
          role="status"
          aria-live="polite"
          data-mock-id="edit-save-status"
          data-testid="edit-save-status"
          data-save-state={save.kind}
        >
          {saveStatusText(save)}
          {save.kind === 'error' && (
            <>
              {' — '}
              <button type="button" className="edit-retry" onClick={onRetrySave} data-testid="edit-save-retry">
                Réessayer
              </button>
            </>
          )}
        </span>
        <span className="edit-dot" aria-hidden />
        <span className="edit-words" data-mock-id="edit-word-count" data-testid="edit-word-count">
          {wordStats}
        </span>
      </div>
      <div className="edit-actions">
        <PresenceAvatars avatars={presence} />
        <button
          type="button"
          className={`edit-ghost${previewing ? ' is-on' : ''}`}
          aria-pressed={previewing}
          onClick={onTogglePreview}
          data-mock-id="edit-preview"
          data-testid="edit-preview"
        >
          {previewing ? 'Édition' : 'Aperçu'}
        </button>
        {/* Exception visuelle : popover résumé facultatif (hors maquette pixel-perfect). */}
        <ChangeSummaryPopover
          label="Enregistrer la version"
          buttonClassName="edit-ghost"
          testId="edit-save-version"
          disabled={saveVersionDisabled}
          title="Créer une version (Ctrl/Cmd+S)"
          onConfirm={onSaveVersion}
        />
        <ChangeSummaryPopover
          label={reviewLabel}
          buttonClassName="edit-cta"
          testId="edit-send-review"
          disabled={reviewDisabled}
          title={reviewReason ?? undefined}
          onConfirm={onSendReview}
        />
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Bandeaux                                                             */
/* ------------------------------------------------------------------ */

/** Bandeau « un autre utilisateur édite » (verrou exclusif) — la page passe en lecture seule. */
export function EditLockBanner({
  name,
  initials,
  minutes,
}: {
  name: string
  initials: string
  minutes: number | null
}) {
  return (
    <div className="edit-banner" role="status" data-mock-id="edit-lock-banner" data-testid="edit-lock-banner">
      <div className="edit-banner-avatar" aria-hidden>
        {initials}
      </div>
      <span className="edit-banner-text">
        <strong>{name}</strong> modifie ce document
        {minutes != null ? ` depuis ${minutes} min` : ' en ce moment'} — la page est en lecture seule tant qu&apos;il
        détient le verrou d&apos;édition.
      </span>
    </div>
  )
}

/** Bandeau d'information neutre (lecture seule, blocs non pris en charge…). */
export function EditNotice({
  children,
  testId,
  tone = 'warn',
}: {
  children: ReactNode
  testId: string
  tone?: 'warn' | 'error'
}) {
  return (
    <div className={`edit-notice edit-notice--${tone}`} role={tone === 'error' ? 'alert' : 'status'} data-testid={testId}>
      {children}
    </div>
  )
}
