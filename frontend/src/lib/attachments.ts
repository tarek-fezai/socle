// SPDX-License-Identifier: AGPL-3.0-or-later
import { api } from './api'

/** Types de nœuds TipTap portés par une pièce jointe (JSON du corps). */
export const IMAGE_NODE_TYPE = 'image'
export const ATTACHMENT_NODE_TYPE = 'attachment'

/** Métadonnées d'une pièce jointe, telles que stockées dans les attributs du nœud. */
export type AttachmentInfo = {
  id: string
  filename: string
  mediaType: string
  sizeBytes: number
  width?: number | null
  height?: number | null
}

/** Délai d'un envoi (les fichiers peuvent être volumineux) — le défaut d'axios est trop court. */
const UPLOAD_TIMEOUT_MS = 10 * 60_000

export function attachmentPath(id: string): string {
  return `/api/v1/attachments/${encodeURIComponent(id)}`
}

export function uploadPath(documentId: string): string {
  return `/api/v1/documents/${encodeURIComponent(documentId)}/attachments`
}

export function isImageMediaType(mediaType: string | null | undefined): boolean {
  return typeof mediaType === 'string' && mediaType.toLowerCase().startsWith('image/')
}

/** Vidéos acceptées pour le bloc `video` (lecture native du navigateur). */
export const VIDEO_MEDIA_TYPES = ['video/mp4', 'video/webm']

export function isVideoMediaType(mediaType: string | null | undefined): boolean {
  return typeof mediaType === 'string' && VIDEO_MEDIA_TYPES.includes(mediaType.toLowerCase())
}

/** « 1,4 Mo », « 320 Ko », « 12 o » — unités décimales, virgule française. */
export function formatBytes(bytes: number | null | undefined): string {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return ''
  if (bytes < 1000) return `${Math.round(bytes)} o`
  const units = ['Ko', 'Mo', 'Go']
  let value = bytes / 1000
  let i = 0
  while (value >= 1000 && i < units.length - 1) {
    value /= 1000
    i++
  }
  const text = value >= 100 || Number.isInteger(value) ? String(Math.round(value)) : value.toFixed(1)
  return `${text.replace('.', ',')} ${units[i]}`
}

function asNumber(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined
}

/** Normalise la réponse du serveur (tolère quelques variantes de nommage). */
export function normalizeAttachment(raw: unknown, fallback: { filename: string; mediaType: string; sizeBytes: number }): AttachmentInfo {
  const o = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const id = typeof o.id === 'string' ? o.id : ''
  if (!id) throw new Error('Réponse invalide du serveur : identifiant de pièce jointe manquant.')
  const str = (...keys: string[]): string | undefined => {
    for (const k of keys) if (typeof o[k] === 'string' && o[k]) return o[k] as string
    return undefined
  }
  return {
    id,
    filename: str('filename', 'fileName', 'name') ?? fallback.filename,
    mediaType: str('mediaType', 'contentType') ?? fallback.mediaType,
    sizeBytes: asNumber(o.sizeBytes) ?? asNumber(o.size) ?? fallback.sizeBytes,
    width: asNumber(o.width) ?? null,
    height: asNumber(o.height) ?? null,
  }
}

/**
 * Envoie un fichier vers `POST /api/v1/documents/{id}/attachments` (champ multipart `file`).
 * `onProgress` reçoit une fraction 0..1 (si la taille totale est connue).
 */
export async function uploadAttachment(
  documentId: string,
  file: File,
  opts: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<AttachmentInfo> {
  const form = new FormData()
  form.append('file', file)
  const res = await api.post<unknown>(uploadPath(documentId), form, {
    timeout: UPLOAD_TIMEOUT_MS,
    signal: opts.signal,
    onUploadProgress: (e) => {
      if (opts.onProgress && e.total) opts.onProgress(Math.min(1, e.loaded / e.total))
    },
  })
  return normalizeAttachment(res.data, {
    filename: file.name,
    mediaType: file.type || 'application/octet-stream',
    sizeBytes: file.size,
  })
}

/** Contenu binaire d'une pièce jointe (requête authentifiée : `<img src>` ne porte pas le jeton). */
export async function fetchAttachmentBlob(id: string): Promise<Blob> {
  const res = await api.get<Blob>(attachmentPath(id), { responseType: 'blob', timeout: UPLOAD_TIMEOUT_MS })
  return res.data
}

const urlCache = new Map<string, Promise<string>>()

/** URL d'objet (mise en cache par id) pour afficher une image protégée. */
export function attachmentObjectUrl(id: string): Promise<string> {
  let p = urlCache.get(id)
  if (!p) {
    p = fetchAttachmentBlob(id).then((blob) => URL.createObjectURL(blob))
    p.catch(() => urlCache.delete(id))
    urlCache.set(id, p)
  }
  return p
}

/** Vide le cache (tests). */
export function clearAttachmentUrlCache(): void {
  urlCache.clear()
}

/** Télécharge la pièce jointe sous son nom d'origine. */
export async function downloadAttachment(id: string, filename: string): Promise<void> {
  const blob = await fetchAttachmentBlob(id)
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename || 'fichier'
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  }
}

/** Attributs de nœud TipTap (`image` ou `attachment`) à partir des métadonnées du serveur. */
export function attachmentNodeAttrs(info: AttachmentInfo): Record<string, unknown> {
  const attrs: Record<string, unknown> = {
    id: info.id,
    filename: info.filename,
    sizeBytes: info.sizeBytes,
    mediaType: info.mediaType,
  }
  if (isImageMediaType(info.mediaType)) {
    attrs.width = info.width ?? null
    attrs.height = info.height ?? null
  }
  return attrs
}
