// SPDX-License-Identifier: AGPL-3.0-or-later
import type { ReliabilityTone } from '../lib/reliability'

/** Badge fraîcheur — dérivé serveur (`stale`), indépendant du score de fiabilité. */
export function StaleBadge({
  stale,
  contentModifiedAt,
  compact = false,
}: {
  stale: boolean | undefined
  contentModifiedAt?: string | null
  compact?: boolean
}) {
  if (stale !== true) {
    if (compact) return null
    return (
      <span
        className="inline-flex items-center gap-1.5 rounded-md bg-[#F1F8F3] px-2.5 py-1 text-[11.5px] font-semibold text-socle-success"
        data-testid="freshness-badge-ok"
      >
        À jour
      </span>
    )
  }

  const when = contentModifiedAt
    ? new Date(contentModifiedAt).toLocaleDateString('fr-FR')
    : null

  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-md bg-[#FBF3E4] px-2.5 py-1 text-[11.5px] font-semibold text-socle-warn"
      data-testid="freshness-badge-stale"
      title={when ? `Dernière écriture de contenu : ${when}` : undefined}
    >
      Contenu obsolète
      {!compact && when ? <span className="font-normal text-socle-muted">· {when}</span> : null}
    </span>
  )
}

export function staleTone(stale: boolean | undefined): ReliabilityTone {
  if (stale === true) return 'warn'
  if (stale === false) return 'success'
  return 'none'
}
