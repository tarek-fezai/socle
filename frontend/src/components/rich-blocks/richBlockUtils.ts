// SPDX-License-Identifier: AGPL-3.0-or-later
import { isSafeHttpUrl } from '../../lib/comments'

export const DATE_NODE_TYPE = 'date'
export const BUTTON_NODE_TYPE = 'button'
export const VIDEO_NODE_TYPE = 'video'
export const POLL_NODE_TYPE = 'poll'
export const CHART_NODE_TYPE = 'chart'
export const LINK_PREVIEW_NODE_TYPE = 'linkPreview'

export const DEFAULT_POLL_QUESTION = 'Votre avis ?'
export const DEFAULT_POLL_OPTIONS: readonly [string, string] = ['Oui', 'Non']
export const DEFAULT_LINK_PREVIEW_URL = 'https://example.org'

export type ChartType = 'bar' | 'line' | 'pie'
export type ChartSeries = { name: string; values: number[] }

/** Hôte affiché sur la carte d'aperçu (sans www.). */
export function domainFromUrl(raw: string): string {
  try {
    return new URL(raw.trim()).hostname.replace(/^www\./i, '')
  } catch {
    return ''
  }
}

export function parseStringList(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return raw.filter((x): x is string => typeof x === 'string' && x.trim() !== '')
}

export function parseChartSeries(raw: unknown): ChartSeries[] {
  if (!Array.isArray(raw)) return []
  const out: ChartSeries[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const o = item as { name?: unknown; values?: unknown }
    const name = typeof o.name === 'string' && o.name.trim() ? o.name.trim() : 'Série'
    const values = Array.isArray(o.values) ? o.values.map((v) => (typeof v === 'number' && !Number.isNaN(v) ? v : Number(v) || 0)) : []
    out.push({ name, values })
  }
  return out
}

/** Libellé accessible pour un graphique (lecture / tests). */
export function chartAccessibilityLabel(
  title: string | undefined | null,
  labels: string[],
  series: ChartSeries[],
): string {
  const head = title?.trim() || 'Graphique'
  if (labels.length === 0 || series.length === 0) return head
  const chunks = series.map((s) => {
    const pts = labels.map((l, i) => `${l} ${s.values[i] ?? 0}`).join(', ')
    return `${s.name}: ${pts}`
  })
  return `${head}. ${chunks.join(' ; ')}`
}

export const SAMPLE_CHART_ATTRS: {
  chartType: ChartType
  labels: string[]
  series: ChartSeries[]
  title: string
} = {
  chartType: 'bar',
  labels: ['Janvier', 'Février', 'Mars'],
  series: [{ name: 'Ventes', values: [12, 19, 8] }],
  title: 'Exemple',
}

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
