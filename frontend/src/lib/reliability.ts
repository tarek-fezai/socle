// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Affichage du score de fiabilité documentaire.
 *
 * Seuils de couleur (simples, non configurables) :
 * - score &gt;= 80 → succès (élevée)
 * - score &gt;= 50 → attention (moyenne)
 * - score &lt; 50 → danger (faible)
 * - null / non numérique → « Non évalué » (jamais 0% inventé)
 */
export const RELIABILITY_HIGH = 80
export const RELIABILITY_MID = 50

export type ReliabilityTone = 'success' | 'warn' | 'danger' | 'none'

export function reliabilityTone(score: number | null | undefined): ReliabilityTone {
  if (score == null || Number.isNaN(score)) return 'none'
  if (score >= RELIABILITY_HIGH) return 'success'
  if (score >= RELIABILITY_MID) return 'warn'
  return 'danger'
}

/** Libellé court pour la ligne de statut (Main.dc.html style). */
export function reliabilityStatusLabel(score: number | null | undefined): string {
  if (score == null || Number.isNaN(score)) return 'Non évalué'
  const pct = formatReliabilityPercent(score)
  const tone = reliabilityTone(score)
  if (tone === 'success') return `Fiabilité ${pct}`
  if (tone === 'warn') return `Fiabilité ${pct}`
  return `Fiabilité ${pct}`
}

export function formatReliabilityPercent(score: number): string {
  const rounded = Math.round(score * 100) / 100
  const text = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2).replace(/\.?0+$/, '')
  return `${text}%`
}

export function formatReliabilityComputedAt(iso: string | null | undefined): string | null {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  return `Calculé le ${d.toLocaleString('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })}`
}

/**
 * Moyenne des scores non-NULL uniquement.
 * Ensemble vide → null (« Non évalué »), jamais division par zéro / NaN.
 */
export function averageReliabilityScores(
  scores: Array<number | null | undefined>,
): number | null {
  const values = scores.filter((s): s is number => s != null && !Number.isNaN(s))
  if (values.length === 0) return null
  const sum = values.reduce((a, b) => a + b, 0)
  return Math.round((sum / values.length) * 100) / 100
}
