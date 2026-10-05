// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import type { AxiosInstance } from 'axios'
import type { components } from './api-types'
import type { TipTapNode } from './documents'

/** Seuil client par défaut (aligné sur socle.writing-assistant.long-paragraph-words). */
export const DEFAULT_LONG_PARAGRAPH_WORDS = 120

/** Libellé fixe renvoyé par le serveur quand aucune ancre source n'est disponible. */
export const INACCESSIBLE_LABEL = 'Document inaccessible'

export type BrokenLinkReason = 'deleted' | 'inaccessible'

export type BrokenLink = components['schemas']['BrokenLink'] & {
  targetId: string
  accessible: boolean
  reason?: BrokenLinkReason | string
}

export type LongParagraph = components['schemas']['LongParagraph'] & {
  index: number
  wordCount: number
  excerpt: string
}

export type WritingHints = components['schemas']['Hints'] & {
  longParagraphThresholdWords: number
  brokenLinks: BrokenLink[]
  longParagraphs: LongParagraph[]
}

/** GET /api/v1/documents/{id}/writing-assistant — liens cassés + paragraphes longs (corps enregistré). */
export async function getWritingHints(api: Pick<AxiosInstance, 'get'>, documentId: string) {
  const { data } = await api.get<WritingHints>(`/api/v1/documents/${documentId}/writing-assistant`)
  return data
}

export function writingHintsKey(documentId: string) {
  return ['writing-assistant', documentId] as const
}

/** Libellé d'un lien cassé : texte d'ancre source (ou libellé fixe serveur). */
export function brokenLinkLabel(link: Pick<BrokenLink, 'label'>): string {
  return (link.label ?? '').trim() || INACCESSIBLE_LABEL
}

/** Message carte « Lien cassé » — `reason=deleted` → « n'existe plus », sinon « inaccessible ». */
export function brokenLinkMessage(link: BrokenLink): string {
  const label = brokenLinkLabel(link)
  if (link.reason === 'deleted') {
    return `La référence vers « ${label} » n'existe plus. Mettre à jour le lien.`
  }
  return `La référence vers « ${label} » n'est pas accessible. Mettre à jour le lien.`
}

function collectText(node: TipTapNode | undefined, out: string[]) {
  if (!node || typeof node !== 'object') return
  if (node.type === 'text' && typeof node.text === 'string') {
    out.push(node.text)
    return
  }
  if (node.type === 'hardBreak') {
    out.push(' ')
    return
  }
  for (const c of Array.isArray(node.content) ? node.content : []) collectText(c as TipTapNode, out)
}

/**
 * Paragraphes de plus de `threshold` mots — même indexation que le serveur
 * (rang du paragraphe, 0-based, tous paragraphes confondus, transclusions ignorées).
 */
export function findLongParagraphs(
  body: TipTapNode | null | undefined,
  threshold: number = DEFAULT_LONG_PARAGRAPH_WORDS,
): LongParagraph[] {
  const out: LongParagraph[] = []
  const counter = { n: 0 }
  const walk = (node: TipTapNode | undefined) => {
    if (!node || typeof node !== 'object') return
    if (node.type === 'transclusion') return
    if (node.type === 'paragraph') {
      const index = counter.n++
      const parts: string[] = []
      collectText(node, parts)
      const plain = parts.join('').trim()
      const words = plain ? plain.split(/\s+/).length : 0
      if (words > threshold) {
        const flat = plain.replace(/\s+/g, ' ')
        out.push({ index, wordCount: words, excerpt: flat.length <= 80 ? flat : `${flat.slice(0, 80).trim()}…` })
      }
      return
    }
    for (const c of Array.isArray(node.content) ? node.content : []) walk(c as TipTapNode)
  }
  walk(body ?? undefined)
  return out
}

/** Texte de la carte « Paragraphe long ». */
export function longParagraphMessage(p: LongParagraph, threshold: number): string {
  return `Ce paragraphe dépasse ${threshold} mots (${p.wordCount ?? 0}) — envisagez de le diviser en deux idées.`
}
