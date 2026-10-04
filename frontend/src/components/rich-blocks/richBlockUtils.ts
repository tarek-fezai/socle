// SPDX-License-Identifier: AGPL-3.0-or-later
import { isSafeHttpUrl } from '../../lib/comments'

export const DATE_NODE_TYPE = 'date'
export const BUTTON_NODE_TYPE = 'button'
export const VIDEO_NODE_TYPE = 'video'

export const DEFAULT_BUTTON_LABEL = 'Action'
/** Lien de départ d'un bouton inséré : à remplacer par l'auteur. */
export const DEFAULT_BUTTON_HREF = 'https://example.org'

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

/** « 2026-10-15 » → Date locale (construite en local, sans décalage de fuseau). */
export function parseIsoDate(value: unknown): Date | null {
  if (typeof value !== 'string') return null
  const m = ISO_DATE.exec(value.trim())
  if (!m) return null
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const date = new Date(y, mo - 1, d)
  if (date.getFullYear() !== y || date.getMonth() !== mo - 1 || date.getDate() !== d) return null
  return date
}

/** « 15 octobre 2026 » ; valeur invalide → texte d'origine (ou « Date »). */
export function formatDateFr(value: unknown): string {
  const date = parseIsoDate(value)
  if (!date) return typeof value === 'string' && value.trim() ? value : 'Date'
  return date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
}

/** Date du jour (fuseau local) au format AAAA-MM-JJ. */
export function todayIsoDate(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

export type ButtonTarget =
  | { kind: 'external'; href: string }
  | { kind: 'document'; documentId: string; to: string }
  | { kind: 'none' }

/** Cible d'un bloc bouton : le document interne prime, sinon lien http(s) ; le reste est ignoré. */
export function buttonTarget(attrs: { href?: unknown; documentId?: unknown }): ButtonTarget {
  const documentId = typeof attrs.documentId === 'string' ? attrs.documentId.trim() : ''
  if (documentId) return { kind: 'document', documentId, to: `/docs/${encodeURIComponent(documentId)}` }
  const href = typeof attrs.href === 'string' ? attrs.href.trim() : ''
  if (isSafeHttpUrl(href)) return { kind: 'external', href }
  return { kind: 'none' }
}
