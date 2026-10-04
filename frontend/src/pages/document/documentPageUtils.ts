// SPDX-License-Identifier: AGPL-3.0-or-later
import type { ActiveAttestation } from '../../lib/attestations'
import type {
  DocumentDetail,
  DocumentPermissions,
  PersonRef,
  TagRef,
} from '../../lib/documents'
import { emptyPermissions } from '../../lib/documents'
import { reliabilityTone, type ReliabilityTone } from '../../lib/reliability'

export type StatusMeta = { label: string; color: string }

/** Pastille de statut (Main.dc.html : Validé = #1E8E5A). */
export function statusMeta(status: string | null | undefined): StatusMeta {
  switch (status) {
    case 'valide':
      return { label: 'Validé', color: '#1E8E5A' }
    case 'en_revue':
      return { label: 'En revue', color: '#B7791F' }
    case 'brouillon':
      return { label: 'Brouillon', color: '#6B6B72' }
    default:
      return { label: status ? String(status) : 'Brouillon', color: '#6B6B72' }
  }
}

function validDate(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

/** « 12 septembre 2026 » */
export function formatLongDateFr(iso: string | null | undefined): string | null {
  const d = validDate(iso)
  return d ? d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' }) : null
}

/** « 12 sept. 2026 » (mobile) */
export function formatShortDateFr(iso: string | null | undefined): string | null {
  const d = validDate(iso)
  return d ? d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' }) : null
}

/** « 12 septembre 2026 à 14:22 » */
export function formatDateTimeFr(iso: string | null | undefined): string | null {
  const d = validDate(iso)
  if (!d) return null
  const date = d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
  const time = d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', hour12: false })
  return `${date} à ${time}`
}

/** Date de dernière révision du contenu (repli : dernière modification). */
export function revisedAt(doc: Pick<DocumentDetail, 'contentModifiedAt' | 'updatedAt'>): string {
  return doc.contentModifiedAt || doc.updatedAt
}

/** Prochaine revue = dernière révision + seuil d'obsolescence. */
export function nextReviewAt(
  doc: Pick<DocumentDetail, 'contentModifiedAt' | 'updatedAt' | 'stalenessThresholdDays'>,
): string | null {
  const base = validDate(revisedAt(doc))
  const days = doc.stalenessThresholdDays
  if (!base || !days || days <= 0) return null
  const next = new Date(base.getTime() + days * 86_400_000)
  return next.toISOString()
}

export const SYSTEM_AUTHOR_LABEL = 'Système (migration)'
export const UNKNOWN_USER_LABEL = 'Utilisateur'

/**
 * Nom affichable d'un utilisateur à partir d'un id : utilisateur courant, propriétaires de
 * l'espace ; sinon libellé neutre (le backend n'expose pas d'annuaire par id).
 */
export function resolveUserName(
  userId: string | null | undefined,
  known: Record<string, string>,
): string {
  if (!userId) return SYSTEM_AUTHOR_LABEL
  return known[userId] || UNKNOWN_USER_LABEL
}

export function ownerLabel(spaceName: string | null | undefined): string {
  const n = (spaceName ?? '').trim()
  return n ? `Équipe ${n}` : 'Équipe'
}

const TAG_BG: Record<string, string> = {
  '#3730E0': '#F0EFFC',
  '#B7791F': '#FDF3E3',
  '#B54708': '#FCEEEA',
  '#7C3AED': '#F3EEFD',
  '#1E8E5A': '#E7F5EC',
  '#0D8A7C': '#E4F4F2',
  '#9B9BA1': '#F1EFEA',
  '#6B6862': '#F1EFEA',
}
const TAG_PALETTE = ['#3730E0', '#B7791F', '#B54708'] as const

/** Couleurs d'une pastille de tag (palette de la maquette, repli par rang). */
export function tagColors(tag: TagRef, index: number): { fg: string; bg: string } {
  const raw = (tag.color ?? '').trim().toUpperCase()
  const fg = /^#[0-9A-F]{6}$/.test(raw) ? raw : TAG_PALETTE[index % TAG_PALETTE.length]!
  const bg = TAG_BG[fg] ?? `${fg}1A`
  return { fg, bg }
}

export function reliabilityDot(score: number | null | undefined): string {
  const tone: ReliabilityTone = reliabilityTone(score)
  switch (tone) {
    case 'success':
      return '#1E8E5A'
    case 'warn':
      return '#B7791F'
    case 'danger':
      return '#B54708'
    default:
      return '#B0B0B5'
  }
}

/** Droits document — uniquement le champ `permissions` serveur. */
export function documentPermissions(
  perms: DocumentPermissions | null | undefined,
): DocumentPermissions {
  return perms ?? emptyPermissions
}

/** Libellé auteur / modificateur ; null → « Système (migration) ». */
export function personLabel(person: PersonRef | null | undefined): string {
  if (!person) return SYSTEM_AUTHOR_LABEL
  return person.displayName
}

export function personInitials(person: PersonRef | null | undefined, fallback = '?'): string {
  if (!person) return '⚙'
  return person.initials || fallback
}

/** Libellé du type pour la bannière (« cette politique » / « ce document »). */
export function attestationNoun(docType: string | null | undefined): string {
  return /politique/i.test(docType ?? '') ? 'cette politique' : 'ce document'
}

/** Périmètre cité dans la bannière d'attestation. */
export function attestationScopeLabel(
  a: Pick<ActiveAttestation, 'audienceType'>,
  spaceName: string | null | undefined,
): string {
  if (a.audienceType === 'space_members') {
    const n = (spaceName ?? '').trim()
    return n ? `du périmètre « ${n} »` : "de l'espace"
  }
  return 'du groupe concerné'
}

export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase()
}
