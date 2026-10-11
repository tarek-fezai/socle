// SPDX-License-Identifier: LicenseRef-Socle-Proprietary

/**
 * Format relatif FR court de la maquette d'accueil (« il y a 20 min », « il y a 3h », « hier »).
 * Unités tronquées (1 h 59 → « il y a 1h ») ; horodatage futur (horloge décalée) → « à l’instant ».
 */
export function formatRelativeFr(iso: string, now = Date.now()): string {
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return ''
  const diffMs = Math.max(0, now - t)
  const min = Math.floor(diffMs / 60_000)
  const hour = Math.floor(diffMs / 3_600_000)
  const day = Math.floor(diffMs / 86_400_000)

  if (min < 1) return 'à l’instant'
  if (min < 60) return `il y a ${min} min`
  if (hour < 24) return `il y a ${hour}h`
  if (day === 1) return 'hier'
  if (day < 7) return `il y a ${day} j`
  return formatShortDateFr(iso)
}

export function formatShortDateFr(iso: string): string {
  const d = new Date(iso)
  if (!Number.isFinite(d.getTime())) return ''
  return new Intl.DateTimeFormat('fr-FR', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(d)
}

/** « 12 septembre 2026 » — ligne méta favoris (maquette). */
export function formatLongDateFr(iso: string): string {
  const d = new Date(iso)
  if (!Number.isFinite(d.getTime())) return ''
  return new Intl.DateTimeFormat('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(d)
}

/** « 4 302 » : séparateur de milliers insécable de la largeur d'une espace (U+202F d'Intl trop étroit). */
export function formatFrInteger(n: number): string {
  return new Intl.NumberFormat('fr-FR').format(Math.round(n)).replace(/\u202f/g, '\u00a0')
}

export function statusDraftLabel(status: string): string {
  const s = status.toLowerCase()
  if (s === 'brouillon' || s === 'draft') return 'Brouillon'
  if (s === 'en_revue' || s === 'review') return 'En revue'
  if (s === 'valide' || s === 'published') return 'Publié'
  return status
}
