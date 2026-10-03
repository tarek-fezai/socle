// SPDX-License-Identifier: AGPL-3.0-or-later
import type { TipTapNode } from '../../lib/documents'
import type { EditLockStatus } from '../../lib/editLock'
import { initialsOf } from './documentPageUtils'

/* ------------------------------------------------------------------ */
/* Mots / temps de lecture                                              */
/* ------------------------------------------------------------------ */

/** Vitesse de lecture retenue (mots / minute). */
export const WORDS_PER_MINUTE = 200

function collectBlocks(node: TipTapNode | undefined, current: string[], blocks: string[]) {
  if (!node || typeof node !== 'object') return
  if (node.type === 'text') {
    if (typeof node.text === 'string') current.push(node.text)
    return
  }
  if (node.type === 'hardBreak') {
    current.push(' ')
    return
  }
  const kids = Array.isArray(node.content) ? (node.content as TipTapNode[]) : []
  const isTextBlock = node.type === 'paragraph' || node.type === 'heading' || node.type === 'codeBlock'
  if (isTextBlock) {
    const buf: string[] = []
    for (const k of kids) collectBlocks(k, buf, blocks)
    blocks.push(buf.join(''))
    return
  }
  for (const k of kids) collectBlocks(k, current, blocks)
}

/**
 * Nombre de mots du corps TipTap, calculé sur les nœuds texte (jamais sur du HTML).
 * Un « mot » est un segment délimité par des espaces contenant au moins une lettre ou un chiffre
 * (les tirets « — » ou puces « · » isolés ne comptent pas).
 */
export function countWordsFromTipTap(body: TipTapNode | Record<string, unknown> | null | undefined): number {
  if (!body || typeof body !== 'object') return 0
  const blocks: string[] = []
  const loose: string[] = []
  collectBlocks(body as TipTapNode, loose, blocks)
  if (loose.length) blocks.push(loose.join(''))
  let n = 0
  for (const b of blocks) {
    for (const token of b.split(/\s+/)) {
      if (token && /[\p{L}\p{N}]/u.test(token)) n += 1
    }
  }
  return n
}

/** Minutes de lecture : arrondi au plus proche, 1 minute minimum dès le premier mot. */
export function readingMinutes(words: number): number {
  if (words <= 0) return 0
  return Math.max(1, Math.round(words / WORDS_PER_MINUTE))
}

/** « 1 240 mots · 6 min de lecture » */
export function formatWordStats(words: number): string {
  const n = Math.max(0, Math.floor(words))
  const label = n > 1 ? 'mots' : 'mot'
  return `${n.toLocaleString('fr-FR')} ${label} · ${readingMinutes(n)} min de lecture`
}

/* ------------------------------------------------------------------ */
/* Statut d'enregistrement                                              */
/* ------------------------------------------------------------------ */

export type SaveStatus =
  | { kind: 'saved'; at: Date | null }
  | { kind: 'saving' }
  | { kind: 'error'; message?: string }

/** « 14:22 » (fuseau local). */
export function formatClockFr(d: Date | string | null | undefined): string | null {
  if (!d) return null
  const date = d instanceof Date ? d : new Date(d)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', hour12: false })
}

/** Texte de la barre haute : enregistré / en cours / échec (le lien « Réessayer » est rendu à part). */
export function saveStatusText(s: SaveStatus): string {
  switch (s.kind) {
    case 'saving':
      return 'Enregistrement…'
    case 'error':
      return "Échec de l'enregistrement"
    default: {
      const t = formatClockFr(s.at)
      return t ? `Brouillon enregistré à ${t}` : 'Brouillon'
    }
  }
}

/* ------------------------------------------------------------------ */
/* Contenu non éditable sans perte                                      */
/* ------------------------------------------------------------------ */

/** Nœuds / marques que le schéma TipTap (StarterKit + zones à compléter) sait représenter. */
export const EDITOR_NODE_TYPES = new Set([
  'doc',
  'paragraph',
  'text',
  'heading',
  'bulletList',
  'orderedList',
  'listItem',
  'blockquote',
  'codeBlock',
  'horizontalRule',
  'hardBreak',
  'placeholder',
])
export const EDITOR_MARK_TYPES = new Set(['bold', 'italic', 'strike', 'code'])

export type UnsupportedContent = { nodes: string[]; marks: string[] }

/**
 * Blocs / marques hors schéma : TipTap les supprimerait silencieusement au premier enregistrement
 * automatique. Quand il y en a, le corps passe en lecture seule (le titre et les métadonnées restent
 * modifiables, le corps d'origine est renvoyé tel quel).
 */
export function findUnsupportedContent(body: TipTapNode | Record<string, unknown> | null | undefined): UnsupportedContent {
  const nodes = new Set<string>()
  const marks = new Set<string>()
  const walk = (n: TipTapNode | undefined) => {
    if (!n || typeof n !== 'object') return
    if (typeof n.type === 'string' && !EDITOR_NODE_TYPES.has(n.type)) nodes.add(n.type)
    const m = (n as { marks?: Array<{ type?: string }> }).marks
    if (Array.isArray(m)) for (const mk of m) if (mk?.type && !EDITOR_MARK_TYPES.has(mk.type)) marks.add(mk.type)
    for (const c of Array.isArray(n.content) ? (n.content as TipTapNode[]) : []) walk(c)
  }
  walk((body ?? undefined) as TipTapNode | undefined)
  return { nodes: [...nodes], marks: [...marks] }
}

export function hasUnsupportedContent(u: UnsupportedContent): boolean {
  return u.nodes.length > 0 || u.marks.length > 0
}

/* ------------------------------------------------------------------ */
/* Présence / verrou                                                    */
/* ------------------------------------------------------------------ */

export type PresenceAvatar = {
  key: string
  initials: string
  name: string
  self: boolean
}

/**
 * Avatars de la barre haute (co-édition hors V1) : seul le détenteur du verrou exclusif est présent.
 * - verrou à moi (ou pas encore connu) → mon avatar ;
 * - verrou d'un autre → son avatar (nom et initiales réels).
 */
export function presenceAvatars(
  lock: EditLockStatus | null | undefined,
  me: { id: string; displayName: string; avatarInitials?: string } | null | undefined,
): PresenceAvatar[] {
  if (lock && lock.active && !lock.heldByCurrentUser && lock.holderDisplayName) {
    return [
      {
        key: lock.holderUserId ?? 'holder',
        initials: initialsOf(lock.holderDisplayName),
        name: lock.holderDisplayName,
        self: false,
      },
    ]
  }
  if (!me) return []
  return [
    {
      key: me.id,
      initials: me.avatarInitials?.trim() || initialsOf(me.displayName),
      name: me.displayName,
      self: true,
    },
  ]
}

/** Détenteur d'un verrou exclusif tiers (null si libre ou à moi). */
export function lockHeldByOther(lock: EditLockStatus | null | undefined) {
  if (!lock || !lock.active || lock.heldByCurrentUser || !lock.holderDisplayName) return null
  return {
    name: lock.holderDisplayName,
    initials: initialsOf(lock.holderDisplayName),
    since: lock.acquiredAt,
  }
}

/* ------------------------------------------------------------------ */
/* Envoi en révision                                                    */
/* ------------------------------------------------------------------ */

export type ReviewButtonInput = {
  canPublish: boolean
  /** Workflow applicable chargé et défini. */
  hasWorkflow: boolean
  workflowLoading?: boolean
  /** Une demande d'approbation est déjà en cours. */
  pending: boolean
  /** Verrou tiers → lecture seule. */
  lockedByOther: boolean
  submitting: boolean
}

export type ReviewButtonState = { disabled: boolean; reason: string | null }

export function reviewButtonState(i: ReviewButtonInput): ReviewButtonState {
  if (i.submitting) return { disabled: true, reason: null }
  if (!i.canPublish) {
    return { disabled: true, reason: "Vous n'avez pas le droit d'envoyer ce document en révision." }
  }
  if (i.pending) return { disabled: true, reason: 'Une demande de révision est déjà en cours.' }
  if (i.lockedByOther) {
    return { disabled: true, reason: 'Document en cours de modification par un autre utilisateur.' }
  }
  if (i.workflowLoading) return { disabled: true, reason: null }
  if (!i.hasWorkflow) return { disabled: true, reason: "Aucun workflow d'approbation applicable à ce document." }
  return { disabled: false, reason: null }
}

/* ------------------------------------------------------------------ */
/* Métadonnées                                                          */
/* ------------------------------------------------------------------ */

/** « Élevée » / « Moyenne » / « Faible » / « Non évaluée » (colonne Fiabilité de l'écran Modifier). */
export function reliabilityShortLabel(score: number | null | undefined): string {
  if (score == null || Number.isNaN(score)) return 'Non évaluée'
  if (score >= 80) return 'Élevée'
  if (score >= 50) return 'Moyenne'
  return 'Faible'
}

/** Cadence de revue dérivée du seuil d'obsolescence (jours). */
export function reviewCadenceLabel(days: number | null | undefined): string {
  if (!days || days <= 0) return '—'
  if (days <= 8) return 'Hebdomadaire'
  if (days <= 35) return 'Mensuelle'
  if (days <= 100) return 'Trimestrielle'
  if (days <= 200) return 'Semestrielle'
  if (days <= 400) return 'Annuelle'
  return `Tous les ${days} jours`
}

const TAG_CLOSE: Record<string, string> = {
  '#3730E0': '#9B95E6',
  '#B7791F': '#D9B27A',
  '#B54708': '#E2A388',
}

/** Couleur de la croix de suppression d'un tag (teinte atténuée de la couleur du tag). */
export function tagCloseColor(fg: string): string {
  return TAG_CLOSE[fg.toUpperCase()] ?? `${fg}99`
}
