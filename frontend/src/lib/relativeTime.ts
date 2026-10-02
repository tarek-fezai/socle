// SPDX-License-Identifier: AGPL-3.0-or-later

/** Format relatif FR court pour l’accueil (maquette). */
export function formatRelativeFr(iso: string, now = Date.now()): string {
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return ''
  const diffMs = now - t
  const abs = Math.abs(diffMs)
  const min = Math.round(abs / 60_000)
  const hour = Math.round(abs / 3_600_000)
  const day = Math.round(abs / 86_400_000)

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

/** « 4 302 » (espace fine pour milliers). */
export function formatFrInteger(n: number): string {
  return new Intl.NumberFormat('fr-FR').format(Math.round(n))
}

export function statusDraftLabel(status: string): string {
  const s = status.toLowerCase()
  if (s === 'brouillon' || s === 'draft') return 'Brouillon'
  if (s === 'en_revue' || s === 'review') return 'En revue'
  if (s === 'valide' || s === 'published') return 'Publié'
  return status
}
