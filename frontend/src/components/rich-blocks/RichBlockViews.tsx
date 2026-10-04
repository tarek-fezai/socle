// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from 'react'
import { Link, useInRouterContext } from 'react-router-dom'
import { api } from '../../lib/api'
import { attachmentObjectUrl } from '../../lib/attachments'
import { buttonTarget, formatDateFr } from './richBlockUtils'

export const DOCUMENT_INACCESSIBLE_LABEL = 'Document inaccessible'
export const VIDEO_UNAVAILABLE_LABEL = 'Vidéo indisponible'

/* ------------------------------------------------------------------ */
/* Date                                                                 */
/* ------------------------------------------------------------------ */

/** Puce de date (lecture) : « 15 octobre 2026 ». */
export function DateChip({ value }: { value: unknown }) {
  const iso = typeof value === 'string' ? value : ''
  return (
    <time className="doc-date" dateTime={iso || undefined} data-testid="doc-date">
      {formatDateFr(value)}
    </time>
  )
}

/* ------------------------------------------------------------------ */
/* Bouton                                                               */
/* ------------------------------------------------------------------ */

const accessCache = new Map<string, Promise<boolean>>()

/** Vide le cache d'accès aux documents (tests). */
export function clearDocumentAccessCache(): void {
  accessCache.clear()
}

/** Le document est-il lisible par l'utilisateur courant ? (GET ; toute erreur → inaccessible) */
function documentAccessible(id: string): Promise<boolean> {
  let p = accessCache.get(id)
  if (!p) {
    p = api.get(`/api/v1/documents/${encodeURIComponent(id)}`).then(
      () => true,
      () => false,
    )
    accessCache.set(id, p)
  }
  return p
}

function useDocumentAccess(id: string | null): 'loading' | 'ok' | 'denied' {
  const [state, setState] = useState<{ id: string | null; ok?: boolean }>({ id: null })
  useEffect(() => {
    if (!id) return
    let cancelled = false
    void documentAccessible(id).then((ok) => !cancelled && setState({ id, ok }))
    return () => {
      cancelled = true
    }
  }, [id])
  if (!id || state.id !== id) return 'loading'
  return state.ok ? 'ok' : 'denied'
}

function DocumentButton({ label, documentId, to }: { label: string; documentId: string; to: string }) {
  const access = useDocumentAccess(documentId)
  const inRouter = useInRouterContext()
  if (access === 'denied') {
    return (
      <button type="button" className="doc-cta is-disabled" disabled data-testid="doc-button-denied">
        {DOCUMENT_INACCESSIBLE_LABEL}
      </button>
    )
  }
  if (access === 'loading') {
    return (
      <button type="button" className="doc-cta is-disabled" disabled aria-busy="true" data-testid="doc-button-loading">
        {label}
      </button>
    )
  }
  return inRouter ? (
    <Link to={to} className="doc-cta" data-testid="doc-button-internal">
      {label}
    </Link>
  ) : (
    <a href={to} className="doc-cta" data-testid="doc-button-internal">
      {label}
    </a>
  )
}

/** Bloc bouton (CTA) : lien externe, document interne (si accessible) ou bouton inerte. */
export function RichButton({ label, href, documentId }: { label?: unknown; href?: unknown; documentId?: unknown }) {
  const text = typeof label === 'string' && label.trim() ? label : 'Action'
  const target = buttonTarget({ href, documentId })
  let inner
  if (target.kind === 'document') {
    inner = <DocumentButton label={text} documentId={target.documentId} to={target.to} />
  } else if (target.kind === 'external') {
    inner = (
      <a className="doc-cta" href={target.href} target="_blank" rel="noopener noreferrer" data-testid="doc-button-external">
        {text}
      </a>
    )
  } else {
    inner = (
      <span className="doc-cta is-disabled" aria-disabled="true" data-testid="doc-button-inert">
        {text}
      </span>
    )
  }
  return (
    <div className="doc-cta-wrap" data-testid="doc-button">
      {inner}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Vidéo                                                                */
/* ------------------------------------------------------------------ */

/** Vidéo d'une pièce jointe : chargée via l'API (jeton) puis lue par URL d'objet. */
export function RichVideo({ id, filename }: { id: string; filename?: string }) {
  const [state, setState] = useState<{ id: string; url?: string; failed?: boolean }>({ id })

  useEffect(() => {
    let cancelled = false
    attachmentObjectUrl(id).then(
      (url) => !cancelled && setState({ id, url }),
      () => !cancelled && setState({ id, failed: true }),
    )
    return () => {
      cancelled = true
    }
  }, [id])

  const current = state.id === id ? state : { id }
  if (current.failed) {
    return (
      <span className="doc-attachment-image is-error" role="note" data-testid="attachment-video-error">
        {VIDEO_UNAVAILABLE_LABEL}
        {filename ? ` — ${filename}` : ''}
      </span>
    )
  }
  if (!current.url) {
    return (
      <span
        className="doc-attachment-image is-loading"
        role="img"
        aria-busy="true"
        aria-label={filename || 'Vidéo en cours de chargement'}
        data-testid="attachment-video-loading"
      />
    )
  }
  return (
    <video
      className="doc-video"
      controls
      preload="metadata"
      src={current.url}
      aria-label={filename || 'Vidéo'}
      data-attachment-id={id}
      data-testid="attachment-video"
    />
  )
}
