// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from 'react'
import { apiErrorMessage } from '../../lib/apiError'
import { attachmentObjectUrl, downloadAttachment, formatBytes } from '../../lib/attachments'

export const IMAGE_UNAVAILABLE_LABEL = 'Image indisponible'

function numberOrUndefined(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : undefined
}

/** Image d'une pièce jointe : chargée via l'API (jeton) puis affichée par URL d'objet. */
export function AttachmentImage({
  id,
  alt,
  filename,
  width,
  height,
}: {
  id: string
  alt?: string
  filename?: string
  width?: number | null
  height?: number | null
}) {
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

  const w = numberOrUndefined(width)
  const h = numberOrUndefined(height)
  const current = state.id === id ? state : { id }

  if (current.failed) {
    return (
      <span className="doc-attachment-image is-error" role="note" data-testid="attachment-image-error">
        {IMAGE_UNAVAILABLE_LABEL}
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
        aria-label={alt || filename || 'Image en cours de chargement'}
        style={w && h ? { aspectRatio: `${w} / ${h}`, maxWidth: w } : undefined}
        data-testid="attachment-image-loading"
      />
    )
  }
  return (
    <img
      className="doc-attachment-image"
      src={current.url}
      alt={alt ?? ''}
      width={w}
      height={h}
      data-attachment-id={id}
      data-testid="attachment-image"
    />
  )
}

/** Carte « fichier joint » : nom, taille, téléchargement authentifié. */
export function AttachmentFile({
  id,
  filename,
  sizeBytes,
  mediaType,
}: {
  id: string
  filename?: string
  sizeBytes?: number | null
  mediaType?: string
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const name = filename || 'Fichier joint'
  const meta = [formatBytes(sizeBytes), mediaType].filter(Boolean).join(' · ')

  async function download() {
    setBusy(true)
    setError(null)
    try {
      await downloadAttachment(id, name)
    } catch (e) {
      setError(apiErrorMessage(e, 'Impossible de télécharger ce fichier.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="doc-attachment" data-testid="attachment-file" data-attachment-id={id}>
      <span className="doc-attachment-icon" aria-hidden>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M13.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L13.5 2z" />
          <polyline points="13.5 2 13.5 7.5 19 7.5" />
        </svg>
      </span>
      <span className="doc-attachment-body">
        <span className="doc-attachment-name">{name}</span>
        {meta ? <span className="doc-attachment-meta">{meta}</span> : null}
        {error ? (
          <span className="doc-attachment-error" role="alert">
            {error}
          </span>
        ) : null}
      </span>
      <button
        type="button"
        className="doc-attachment-download"
        onClick={() => void download()}
        disabled={busy}
        aria-label={`Télécharger ${name}`}
      >
        {busy ? 'Téléchargement…' : 'Télécharger'}
      </button>
    </div>
  )
}
