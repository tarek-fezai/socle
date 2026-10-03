// SPDX-License-Identifier: AGPL-3.0-or-later
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { DocumentDetail } from '../../lib/documents'
import { formatReliabilityPercent } from '../../lib/reliability'
import type { ReadHeading } from './TipTapReadView'
import {
  formatDateTimeFr,
  formatLongDateFr,
  initialsOf,
  nextReviewAt,
  ownerLabel,
  reliabilityDot,
  resolveUserName,
  SYSTEM_AUTHOR_LABEL,
  tagColors,
} from './documentPageUtils'

/** Champ personnalisé (affiché seulement si une définition existe pour l'espace). */
export type CustomFieldValue = { id: string; label: string; value: string; mono?: boolean }

export type DocumentRailProps = {
  doc: DocumentDetail
  spaceName: string
  headings: ReadHeading[]
  /** Ajoute « Documents liés » au sommaire (section présente dans le corps). */
  hasRelatedLinks: boolean
  activeId: string | null
  onNavigate: (id: string) => void
  /** Éditeur du document : lien « Gérer → » des tags. */
  canEdit: boolean
  /** Éditeur ou gestionnaire d'espace : le bloc Propriétaire mène à la page Accès. */
  canViewAccess: boolean
  userNames: Record<string, string>
  /** Définitions de champs personnalisés + valeurs ; vide / absent → section omise. */
  customFields?: CustomFieldValue[]
  /** Mobile : panneau « Infos » replié/déplié. */
  infoOpen?: boolean
  onToggleInfo?: () => void
  /** Actions mobiles (« Plus ») rendues en tête du panneau. */
  mobileActions?: ReactNode
}

function Label({ children, id }: { children: ReactNode; id?: string }) {
  return (
    <div className="doc-rail-label" data-mock-id={id}>
      {children}
    </div>
  )
}

export function DocumentRail({
  doc,
  spaceName,
  headings,
  hasRelatedLinks,
  activeId,
  onNavigate,
  canEdit,
  canViewAccess,
  userNames,
  customFields,
  infoOpen = false,
  onToggleInfo,
  mobileActions,
}: DocumentRailProps) {
  const owner = ownerLabel(spaceName)
  const authorName = resolveUserName(doc.createdBy, userNames)
  const authorIsSystem = !doc.createdBy
  const modifierName = resolveUserName(doc.updatedBy, userNames)
  const modifierIsSystem = !doc.updatedBy
  const created = formatLongDateFr(doc.createdAt)
  const modified = formatDateTimeFr(doc.updatedAt)
  const score = doc.reliabilityScore ?? null
  const nextReview = formatLongDateFr(nextReviewAt(doc))
  const tags = doc.tags ?? []

  const toc: Array<{ id: string; text: string; level: number }> = headings
    .filter((h) => h.level <= 3)
    .map((h) => ({ id: h.id, text: h.text, level: h.level }))
  if (hasRelatedLinks) toc.push({ id: 'documents-lies', text: 'Documents liés', level: 2 })

  const ownerBlock = (
    <>
      <Label id="rail-owner-label">Propriétaire</Label>
      <div className="doc-rail-person">
        <div className="doc-avatar doc-avatar--dark">{initialsOf(spaceName || 'E')}</div>
        <span className="doc-rail-name" data-mock-id="rail-owner-name">
          {owner}
        </span>
      </div>
    </>
  )

  return (
    <aside
      className={`doc-rail${infoOpen ? ' doc-rail--open' : ''}`}
      id="doc-info-panel"
      aria-label="Informations sur le document"
      data-mock-id="doc-rail"
    >
      <button
        type="button"
        className="doc-rail-toggle"
        aria-expanded={infoOpen}
        aria-controls="doc-info-body"
        onClick={onToggleInfo}
        data-testid="doc-info-toggle"
      >
        Infos du document
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>
      <div className="doc-rail-inner" id="doc-info-body">
        {mobileActions ? <div className="doc-rail-actions">{mobileActions}</div> : null}

        {toc.length > 0 && (
          <nav aria-label="Sur cette page" data-testid="doc-toc">
            <Label id="rail-toc-label">Sur cette page</Label>
            <div className="doc-toc">
              {toc.map((t, i) => (
                <a
                  key={t.id}
                  href={`#${t.id}`}
                  className={`doc-toc-link${activeId === t.id ? ' is-active' : ''}${t.level >= 3 ? ' is-sub' : ''}`}
                  aria-current={activeId === t.id ? 'location' : undefined}
                  data-mock-id={i === 0 ? 'rail-toc-link' : undefined}
                  onClick={(e) => {
                    e.preventDefault()
                    onNavigate(t.id)
                  }}
                >
                  {t.text}
                </a>
              ))}
            </div>
          </nav>
        )}

        {canViewAccess ? (
          <Link to={`/documents/${doc.id}/access`} className="doc-rail-block doc-rail-block--link" data-mock-id="rail-owner">
            {ownerBlock}
          </Link>
        ) : (
          <div className="doc-rail-block" data-mock-id="rail-owner">
            {ownerBlock}
          </div>
        )}

        <div className="doc-rail-block" data-mock-id="rail-author">
          <Label id="rail-author-label">Auteur</Label>
          <Link to={`/docs/${doc.id}/history`} className="doc-rail-person">
            <div className={`doc-avatar ${authorIsSystem ? 'doc-avatar--system' : 'doc-avatar--dark'}`}>
              {authorIsSystem ? '⚙' : initialsOf(authorName)}
            </div>
            <div>
              <div className="doc-rail-name" data-mock-id="rail-author-name">
                {authorIsSystem ? SYSTEM_AUTHOR_LABEL : authorName}
              </div>
              {created && (
                <div className="doc-rail-sub" data-mock-id="rail-author-date">
                  Créé le {created}
                </div>
              )}
            </div>
          </Link>
        </div>

        <Link to={`/docs/${doc.id}/history`} className="doc-rail-block doc-rail-block--link" data-mock-id="rail-modified">
          <Label id="rail-modified-label">Dernière modification</Label>
          <div className="doc-rail-person">
            <div className={`doc-avatar ${modifierIsSystem ? 'doc-avatar--system' : 'doc-avatar--dark'}`}>
              {modifierIsSystem ? '⚙' : initialsOf(modifierName)}
            </div>
            <div>
              <div className="doc-rail-name" data-mock-id="rail-modified-name">
                {modifierName}
              </div>
              <div className="doc-rail-sub" data-mock-id="rail-modified-date">
                {modified ?? '—'}
                {doc.currentVersionNo != null ? ` · v${doc.currentVersionNo}` : ''}
              </div>
            </div>
          </div>
        </Link>

        <div className="doc-rail-block" data-mock-id="rail-tags">
          <div className="doc-rail-head">
            <Label id="rail-tags-label">Tags</Label>
            {canEdit && (
              <Link to={`/docs/${doc.id}/edit#metadata`} className="doc-rail-manage" data-mock-id="rail-tags-manage">
                Gérer →
              </Link>
            )}
          </div>
          {tags.length > 0 ? (
            <div className="doc-tags">
              {tags.map((t, i) => {
                const c = tagColors(t, i)
                return (
                  <Link
                    key={t.id}
                    to={`/search?q=${encodeURIComponent(t.name)}`}
                    className="doc-tag"
                    style={{ color: c.fg, background: c.bg }}
                  >
                    {t.name}
                  </Link>
                )
              })}
            </div>
          ) : (
            <div className="doc-rail-sub">Aucun tag</div>
          )}
        </div>

        <div className="doc-rail-block" data-mock-id="rail-reliability">
          <Label id="rail-reliability-label">Fiabilité</Label>
          <div className="doc-rail-reliability">
            <span className="doc-dot" style={{ background: reliabilityDot(score) }} />
            <span className="doc-rail-name doc-rail-name--medium" data-mock-id="rail-reliability-value">
              {score == null
                ? 'Non évalué'
                : `${formatReliabilityPercent(score)} · ${doc.stale ? 'revue en retard' : 'revue à jour'}`}
            </span>
          </div>
          {score != null && nextReview && (
            <div className="doc-rail-sub doc-rail-sub--lg">Prochaine revue : {nextReview}</div>
          )}
        </div>

        {customFields && customFields.length > 0 && (
          <div className="doc-rail-block" data-mock-id="rail-custom-fields">
            <div className="doc-rail-head">
              <Label id="rail-custom-label">Champs personnalisés</Label>
            </div>
            <div className="doc-fields">
              {customFields.map((f) => (
                <div key={f.id}>
                  <div className="doc-rail-sub">{f.label}</div>
                  <div className={`doc-field-value${f.mono ? ' is-mono' : ''}`}>{f.value}</div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </aside>
  )
}
