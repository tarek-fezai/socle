// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { FavoriteStar } from '../../components/shell/FavoriteStar'
import { isApplePlatform } from '../../components/shell/shellUtils'
import { api } from '../../lib/api'
import { getCachedAuthConfig } from '../../lib/auth'
import { formatAttestationDueDate, type ActiveAttestation } from '../../lib/attestations'
import { listNotifications } from '../../lib/notifications'
import { attestationNoun, attestationScopeLabel } from './documentPageUtils'

export type Crumb = { label: string; to?: string }

const stroke = {
  fill: 'none',
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

function PrintIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" stroke="#43434A" strokeWidth="2" {...stroke} aria-hidden>
      <polyline points="6 9 6 2 18 2 18 9" />
      <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
      <rect x="6" y="14" width="12" height="8" />
    </svg>
  )
}

function FileIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2" {...stroke} aria-hidden>
      <path d="M14 3v4a1 1 0 0 0 1 1h4" />
      <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
    </svg>
  )
}

/* ------------------------------------------------------------------ */
/* Export                                                              */
/* ------------------------------------------------------------------ */

export function ExportMenu({
  documentId,
  onPrint,
  onDownloadPdf,
  busy,
}: {
  documentId: string
  onPrint: () => void
  onDownloadPdf: () => void
  busy: boolean
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLSpanElement>(null)
  const printHint = isApplePlatform() ? '⌘P' : 'Ctrl+P'

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <span className="doc-export" ref={ref}>
      <button
        type="button"
        className="doc-icon-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Imprimer ou exporter en PDF"
        title={`Imprimer / Exporter en PDF (${printHint})`}
        data-testid="doc-export-btn"
        data-mock-id="doc-export-btn"
        onClick={() => setOpen((v) => !v)}
      >
        <PrintIcon />
      </button>
      {open && (
        <div className="doc-menu" role="menu" data-testid="doc-export-menu">
          <button
            type="button"
            role="menuitem"
            className="doc-menu-item"
            onClick={() => {
              setOpen(false)
              onPrint()
            }}
          >
            <PrintIcon />
            <span className="doc-menu-label">Imprimer</span>
            <span className="doc-menu-kbd">{printHint}</span>
          </button>
          <button
            type="button"
            role="menuitem"
            className="doc-menu-item"
            disabled={busy}
            onClick={() => {
              setOpen(false)
              onDownloadPdf()
            }}
          >
            <FileIcon />
            <span className="doc-menu-label">{busy ? 'Export en cours…' : 'Télécharger en PDF'}</span>
          </button>
          <span className="doc-menu-sep" />
          <Link
            to={`/docs/${documentId}/export`}
            role="menuitem"
            className="doc-menu-item doc-menu-item--link"
            onClick={() => setOpen(false)}
          >
            Options d&apos;export avancées →
          </Link>
        </div>
      )}
    </span>
  )
}

/* ------------------------------------------------------------------ */
/* Barre haute desktop (60 px)                                         */
/* ------------------------------------------------------------------ */

/** Fil d'Ariane de la barre haute (partagé Lire / Historique / Comparer). */
export function DocumentCrumbs({ crumbs, mockPrefix = 'doc' }: { crumbs: Crumb[]; mockPrefix?: string }) {
  return (
    <nav className="doc-crumbs" aria-label="Fil d'Ariane" data-mock-id={`${mockPrefix}-breadcrumb`}>
      {crumbs.map((c, i) => {
        const last = i === crumbs.length - 1
        return (
          <span key={`${c.label}-${i}`} className="doc-crumb-wrap">
            {i > 0 && <span className="doc-crumb-sep">→</span>}
            {last || !c.to ? (
              <span
                className={last ? 'doc-crumb is-current' : 'doc-crumb'}
                aria-current={last ? 'page' : undefined}
                data-mock-id={last ? `${mockPrefix}-breadcrumb-current` : undefined}
              >
                {c.label}
              </span>
            ) : (
              <Link to={c.to} className="doc-crumb">
                {c.label}
              </Link>
            )}
          </span>
        )
      })}
    </nav>
  )
}

export function DocumentTopBar({
  documentId,
  spaceId,
  crumbs,
  showPublish,
  publishing,
  onPublish,
  onPrint,
  onDownloadPdf,
  exporting,
}: {
  documentId: string
  spaceId: string
  crumbs: Crumb[]
  showPublish: boolean
  publishing: boolean
  onPublish: () => void
  onPrint: () => void
  onDownloadPdf: () => void
  exporting: boolean
}) {
  // S'abonner à AuthProvider pour relire supportContact une fois auth-config chargé.
  useAuth()
  const support = getCachedAuthConfig()?.supportContact?.trim() || ''
  return (
    <div className="doc-topbar" data-mock-id="doc-topbar">
      <DocumentCrumbs crumbs={crumbs} />


      <div className="doc-actions">
        <a
          href="https://docs.example.com"
          className="doc-icon-btn"
          aria-label="Centre d'aide"
          title="Centre d'aide"
          target="_blank"
          rel="noreferrer"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" stroke="#6B6B72" strokeWidth="2" {...stroke} aria-hidden>
            <circle cx="12" cy="12" r="10" />
            <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
            <line x1="12" y1="17" x2="12.01" y2="17" />
          </svg>
        </a>
        {support ? (
          <a
            href={`mailto:${support}`}
            className="doc-icon-btn doc-icon-btn--text"
            aria-label="Contacter le support"
            title="Contacter le support"
            data-mock-id="doc-help-shortcuts"
          >
            ?
          </a>
        ) : null}
        <FavoriteStar resourceType="document" resourceId={documentId} variant="page" />

        <span className="doc-switch is-active" aria-current="page" data-mock-id="doc-switch-page">
          Page
        </span>
        <Link to={`/spaces/${spaceId}/graph`} className="doc-switch" data-mock-id="doc-switch-graph">
          Graphe
        </Link>
        <button type="button" className="doc-switch is-disabled" disabled title="bientôt" data-mock-id="doc-switch-index">
          Index
        </button>

        <ExportMenu documentId={documentId} onPrint={onPrint} onDownloadPdf={onDownloadPdf} busy={exporting} />

        {showPublish && (
          <button
            type="button"
            className="doc-cta"
            disabled={publishing}
            onClick={onPublish}
            data-testid="doc-publish"
            data-mock-id="doc-publish"
          >
            {publishing ? 'Publication…' : 'Publier'}
          </button>
        )}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Onglets documentaires                                               */
/* ------------------------------------------------------------------ */

export type DocTabsProps = {
  documentId: string
  /** Onglet courant (défaut : Lire). */
  current?: 'read' | 'edit' | 'history'
  /** Préfixe des `data-mock-id` (défaut : `doc`). */
  mockPrefix?: 'doc' | 'edit' | 'hist'
  showEdit: boolean
  showAccess: boolean
  openComments: number
  commentsOpen: boolean
  onToggleComments: () => void
}

export function DocumentTabs({
  documentId,
  current = 'read',
  mockPrefix = 'doc',
  showEdit,
  showAccess,
  openComments,
  commentsOpen,
  onToggleComments,
}: DocTabsProps) {
  return (
    <div className="doc-tabs" role="tablist" aria-label="Sections du document" data-mock-id={`${mockPrefix}-tabs`}>
      <Link
        to={`/docs/${documentId}`}
        role="tab"
        aria-selected={current === 'read'}
        className={`doc-tab${current === 'read' ? ' is-active' : ''}`}
        data-mock-id={current === 'read' ? `${mockPrefix}-tab-read` : undefined}
      >
        Lire
      </Link>
      {showEdit && (
        <Link
          to={`/docs/${documentId}/edit`}
          role="tab"
          aria-selected={current === 'edit'}
          className={`doc-tab${current === 'edit' ? ' is-active' : ''}`}
          data-testid="tab-edit"
          data-mock-id={current === 'edit' ? `${mockPrefix}-tab-edit` : undefined}
        >
          Modifier
        </Link>
      )}
      <Link
        to={`/docs/${documentId}/history`}
        role="tab"
        aria-selected={current === 'history'}
        className={`doc-tab${current === 'history' ? ' is-active' : ''}`}
        data-mock-id={current === 'history' ? `${mockPrefix}-tab-history` : undefined}
      >
        Historique
      </Link>
      <button
        type="button"
        role="tab"
        aria-selected={commentsOpen}
        className={`doc-tab${commentsOpen ? ' is-open' : ''}`}
        onClick={onToggleComments}
        data-testid="toggle-comments"
      >
        Commentaires
        {openComments > 0 && (
          <span className="doc-badge" data-testid="comments-badge">
            {openComments}
          </span>
        )}
      </button>
      {showAccess && (
        <Link to={`/documents/${documentId}/access`} role="tab" aria-selected={false} className="doc-tab" data-testid="tab-access">
          Accès
        </Link>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Mobile                                                              */
/* ------------------------------------------------------------------ */

export function DocumentMobileTop({
  title,
  spaceName,
  onOpenMenu,
  onOpenSearch,
}: {
  title: string
  spaceName: string
  onOpenMenu?: () => void
  onOpenSearch?: () => void
}) {
  const { authenticated } = useAuth()
  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: () => listNotifications(api, { unreadOnly: true, limit: 1 }),
    enabled: authenticated,
    refetchInterval: 30_000,
  })
  const count = unread.data?.unreadCount ?? 0
  return (
    <div className="doc-mobile-top" data-mock-id="doc-mobile-top">
      <button
        type="button"
        className="doc-mobile-icon"
        aria-label="Ouvrir le menu"
        onClick={onOpenMenu}
        data-testid="mobile-menu-btn"
      >
        <svg width="19" height="19" viewBox="0 0 24 24" stroke="#0E0E10" strokeWidth="2" {...stroke} aria-hidden>
          <line x1="3" y1="6" x2="21" y2="6" />
          <line x1="3" y1="12" x2="21" y2="12" />
          <line x1="3" y1="18" x2="21" y2="18" />
        </svg>
      </button>
      <div className="doc-mobile-heading">
        <div className="doc-mobile-title" data-mock-id="doc-mobile-title">
          {title}
        </div>
        <div className="doc-mobile-space" data-mock-id="doc-mobile-space">
          {spaceName}
        </div>
      </div>
      <Link
        to="/notifications"
        className="doc-mobile-icon"
        aria-label={count > 0 ? `Notifications, ${count} non lues` : 'Notifications'}
      >
        <svg width="17" height="17" viewBox="0 0 24 24" stroke="#43434A" strokeWidth="2" {...stroke} aria-hidden>
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {count > 0 && <span className="doc-mobile-dot" aria-hidden />}
      </Link>
      <button type="button" className="doc-mobile-icon" aria-label="Rechercher" onClick={onOpenSearch}>
        <svg width="17" height="17" viewBox="0 0 24 24" stroke="#43434A" strokeWidth="2" {...stroke} aria-hidden>
          <circle cx="11" cy="11" r="7" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
      </button>
    </div>
  )
}

function MobileTab({
  to,
  label,
  active,
  icon,
  badge,
  onClick,
  id,
}: {
  to?: string
  label: string
  active?: boolean
  icon: ReactNode
  badge?: number
  onClick?: () => void
  id?: string
}) {
  const body = (
    <>
      {icon}
      {badge ? <span className="doc-mobile-badge">{badge}</span> : null}
      <span className="doc-mobile-tab-label" data-mock-id={id}>
        {label}
      </span>
    </>
  )
  const cls = `doc-mobile-tab${active ? ' is-active' : ''}`
  return to ? (
    <Link to={to} className={cls} aria-current={active ? 'page' : undefined}>
      {body}
    </Link>
  ) : (
    <button type="button" className={cls} onClick={onClick}>
      {body}
    </button>
  )
}

const svgProps = {
  width: 17,
  height: 17,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  'aria-hidden': true,
} as const

/** Barre d'onglets du bas (MobilePage.dc.html) : Lire / Modifier / Historique / Commentaires / Accès. */
export function DocumentMobileTabs({
  documentId,
  current = 'read',
  showEdit,
  showAccess,
  openComments,
  commentsOpen,
  onToggleComments,
}: DocTabsProps) {
  return (
    <nav className="doc-mobile-tabs" aria-label="Sections du document" data-mock-id="doc-mobile-tabs">
      <MobileTab
        to={`/docs/${documentId}`}
        label="Lire"
        active={current === 'read' && !commentsOpen}
        id="doc-mobile-tab-read"
        icon={
          <svg {...svgProps}>
            <path d="M14 3v4a1 1 0 0 0 1 1h4" />
            <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
          </svg>
        }
      />
      {showEdit && (
        <MobileTab
          to={`/docs/${documentId}/edit`}
          label="Modifier"
          active={current === 'edit' && !commentsOpen}
          icon={
            <svg {...svgProps}>
              <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z" />
            </svg>
          }
        />
      )}
      <MobileTab
        to={`/docs/${documentId}/history`}
        label="Historique"
        active={current === 'history' && !commentsOpen}
        id="doc-mobile-tab-history"
        icon={
          <svg {...svgProps}>
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7v5l3 3" />
          </svg>
        }
      />
      <MobileTab
        label="Commentaires"
        active={commentsOpen}
        badge={openComments}
        onClick={onToggleComments}
        icon={
          <svg {...svgProps}>
            <path d="M21 11.5a8.38 8.38 0 0 1-8.5 8.5 8.5 8.5 0 0 1-4-.9L3 21l1.9-5.5a8.5 8.5 0 1 1 16-4z" />
          </svg>
        }
      />
      {showAccess && (
        <MobileTab
          to={`/documents/${documentId}/access`}
          label="Accès"
          icon={
            <svg {...svgProps}>
              <rect x="3" y="11" width="18" height="11" rx="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          }
        />
      )}
    </nav>
  )
}

/* ------------------------------------------------------------------ */
/* Attestation                                                         */
/* ------------------------------------------------------------------ */

export function AttestationBanner({
  attestation,
  docType,
  spaceName,
  pending,
  error,
  onAcknowledge,
}: {
  attestation: ActiveAttestation
  docType: string | null | undefined
  spaceName: string
  pending: boolean
  error: string | null
  onAcknowledge: () => void
}) {
  const due = formatAttestationDueDate(attestation.dueDate)
  return (
    <div className="doc-attestation" role="region" aria-label="Accusé de lecture" data-testid="attestation-banner" data-mock-id="doc-attestation">
      <span className="doc-attestation-icon">
        <svg width="16" height="16" viewBox="0 0 24 24" stroke="#B7791F" strokeWidth="2" {...stroke} aria-hidden>
          <path d="M12 9v4" />
          <path d="M12 17h.01" />
          <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        </svg>
      </span>
      <div className="doc-attestation-body">
        <div className="doc-attestation-title" data-mock-id="doc-attestation-title">
          Document obligatoire — accusé de lecture requis
        </div>
        <div className="doc-attestation-text" data-mock-id="doc-attestation-text">
          Chaque collaborateur {attestationScopeLabel(attestation, spaceName)} doit attester avoir lu et
          compris {attestationNoun(docType)}
          {due ? ` avant le ${due}` : ''}.{' '}
          <strong data-testid="attestation-progress">
            {attestation.ackCount}/{attestation.audienceSize}
          </strong>{' '}
          attestations reçues à ce jour.
          {error ? (
            <span className="doc-attestation-error" role="alert">
              {' '}
              {error}
            </span>
          ) : null}
        </div>
      </div>
      <button
        type="button"
        className="doc-attestation-cta"
        disabled={pending}
        onClick={onAcknowledge}
        data-testid="attestation-acknowledge"
        data-mock-id="doc-attestation-cta"
      >
        <svg width="13" height="13" viewBox="0 0 24 24" stroke="#FFFFFF" strokeWidth="2.4" {...stroke} aria-hidden>
          <polyline points="20 6 9 17 4 12" />
        </svg>
        {pending ? 'Enregistrement…' : "J'ai lu et compris"}
      </button>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Feedback                                                            */
/* ------------------------------------------------------------------ */

export function FeedbackRow({
  myVote,
  pending,
  onVote,
  totals,
}: {
  myVote: boolean | null | undefined
  pending: boolean
  onVote: (helpful: boolean) => void
  totals?: { yes: number; no: number } | null
}) {
  return (
    <div className="doc-feedback" data-testid="doc-feedback" data-mock-id="doc-feedback">
      <span className="doc-feedback-label" data-mock-id="doc-feedback-label">
        <span className="doc-only-desktop">Cette page vous a-t-elle été utile ?</span>
        <span className="doc-only-mobile">Utile ?</span>
      </span>
      <div className="doc-feedback-actions">
        {totals ? (
          <span className="doc-feedback-totals" data-testid="feedback-totals">
            {totals.yes} oui · {totals.no} non
          </span>
        ) : null}
        <button
          type="button"
          className={`doc-vote${myVote === true ? ' is-on' : ''}`}
          aria-label="Oui, cette page m'a été utile"
          aria-pressed={myVote === true}
          disabled={pending}
          onClick={() => onVote(true)}
          data-testid="feedback-yes"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2" {...stroke} aria-hidden>
            <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3z" />
            <path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3" />
          </svg>
        </button>
        <button
          type="button"
          className={`doc-vote${myVote === false ? ' is-on' : ''}`}
          aria-label="Non, cette page ne m'a pas été utile"
          aria-pressed={myVote === false}
          disabled={pending}
          onClick={() => onVote(false)}
          data-testid="feedback-no"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2" {...stroke} aria-hidden>
            <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3z" />
            <path d="M17 2h3a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-3" />
          </svg>
        </button>
      </div>
    </div>
  )
}
