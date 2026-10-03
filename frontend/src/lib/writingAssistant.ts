// SPDX-License-Identifier: AGPL-3.0-or-later
import type { AxiosInstance } from 'axios'
import type { TipTapNode } from './documents'

/** Seuil client par défaut (aligné sur socle.writing-assistant.long-paragraph-words). */
export const DEFAULT_LONG_PARAGRAPH_WORDS = 120

/** Libellé fixe renvoyé par le serveur pour toute cible illisible (jamais de titre divulgué). */
export const INACCESSIBLE_LABEL = 'Document inaccessible'

export type BrokenLink = { targetId: string; label: string; accessible: boolean }
export type LongParagraph = { index: number; wordCount: number; excerpt: string }

export type WritingHints = {
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

/** Libellé d'un lien cassé : « Document inaccessible » dès que la cible n'est pas lisible. */
export function brokenLinkLabel(link: Pick<BrokenLink, 'label' | 'accessible'>): string {
  if (!link.accessible) return INACCESSIBLE_LABEL
  return link.label.trim() || 'Document supprimé'
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
  return `Ce paragraphe dépasse ${threshold} mots (${p.wordCount}) — envisagez de le diviser en deux idées.`
}
