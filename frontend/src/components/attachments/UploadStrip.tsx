// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { UploadItem } from './useAttachmentUploads'

/** Envois en cours (barre de progression) ou en échec (message serveur, fermable). */
export function UploadStrip({ items, onDismiss }: { items: UploadItem[]; onDismiss: (key: string) => void }) {
  if (items.length === 0) return null
  return (
    <ul className="edit-uploads" data-testid="edit-uploads" aria-label="Envois de pièces jointes">
      {items.map((it) => (
        <li key={it.key} className={`edit-upload${it.status === 'error' ? ' is-error' : ''}`} data-status={it.status}>
          <span className="edit-upload-name">{it.filename}</span>
          {it.status === 'uploading' ? (
            <span
              className="edit-upload-bar"
              role="progressbar"
              aria-label={`Envoi de ${it.filename}`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(it.progress * 100)}
            >
              <span style={{ width: `${Math.round(it.progress * 100)}%` }} />
            </span>
          ) : (
            <>
              <span className="edit-upload-error" role="alert">
                {it.error}
              </span>
              <button type="button" className="edit-upload-dismiss" onClick={() => onDismiss(it.key)}>
                Fermer
              </button>
            </>
          )}
        </li>
      ))}
    </ul>
  )
}
