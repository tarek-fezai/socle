// SPDX-License-Identifier: AGPL-3.0-or-later
import {
  averageReliabilityScores,
  formatReliabilityComputedAt,
  formatReliabilityPercent,
  reliabilityStatusLabel,
  reliabilityTone,
  type ReliabilityTone,
} from '../lib/reliability'

const DOT: Record<Exclude<ReliabilityTone, 'none'>, string> = {
  success: 'bg-socle-success',
  warn: 'bg-socle-warn',
  danger: 'bg-socle-danger',
}

const TEXT: Record<Exclude<ReliabilityTone, 'none'>, string> = {
  success: 'text-socle-success',
  warn: 'text-socle-warn',
  danger: 'text-socle-danger',
}

/** Ligne de statut document (Main.dc.html) — score ou « Non évalué ». */
export function DocumentReliabilityStatus({
  score,
  computedAt,
  status,
  createdAt,
}: {
  score: number | null | undefined
  computedAt?: string | null
  status: string
  createdAt?: string | null
}) {
  const tone = reliabilityTone(score)
  const hasScore = tone !== 'none'
  const computedLabel = hasScore ? formatReliabilityComputedAt(computedAt) : null

  return (
    <div
      className="mb-5 flex flex-wrap items-center gap-x-4 gap-y-2 text-[12.5px]"
      data-testid="doc-reliability-status"
    >
      <span className="inline-flex items-center gap-1.5 font-semibold text-socle-slate">
        <span
          className={`h-1.5 w-1.5 rounded-full ${
            status === 'valide'
              ? 'bg-socle-success'
              : status === 'en_revue'
                ? 'bg-socle-warn'
                : 'bg-socle-faint'
          }`}
          aria-hidden
        />
        {statusLabelFr(status)}
      </span>
      <span className="h-0.5 w-0.5 rounded-full bg-[#DEDEE1]" aria-hidden />
      {hasScore ? (
        <span className={`inline-flex items-center gap-1.5 font-medium ${TEXT[tone]}`}>
          <span className={`h-1.5 w-1.5 rounded-full ${DOT[tone]}`} aria-hidden />
          {reliabilityStatusLabel(score)}
        </span>
      ) : (
        <span className="text-socle-muted" data-testid="reliability-unevaluated">
          Non évalué
        </span>
      )}
      {createdAt && (
        <>
          <span className="h-0.5 w-0.5 rounded-full bg-[#DEDEE1]" aria-hidden />
          <span className="text-socle-muted">
            Créé le{' '}
            {new Date(createdAt).toLocaleDateString('fr-FR', {
              day: 'numeric',
              month: 'long',
              year: 'numeric',
            })}
          </span>
        </>
      )}
      {computedLabel && (
        <>
          <span className="h-0.5 w-0.5 rounded-full bg-[#DEDEE1]" aria-hidden />
          <span className="text-socle-faint" data-testid="reliability-computed-at">
            {computedLabel}
          </span>
        </>
      )}
    </div>
  )
}

/** Bloc rail « Fiabilité » — moyenne des documents (pas folders.reliability_score). */
export function FolderReliabilityRail({
  scores,
  caption,
}: {
  scores: Array<number | null | undefined>
  /** Sous-titre optionnel (ex. « 2 documents validés · 1 en revue ») */
  caption?: string
}) {
  const avg = averageReliabilityScores(scores)
  const tone = reliabilityTone(avg)

  return (
    <div data-testid="folder-reliability-rail">
      <div className="section-label mb-2.5">Fiabilité</div>
      {avg == null || tone === 'none' ? (
        <p className="text-[13px] text-socle-muted" data-testid="reliability-unevaluated">
          Non évalué
        </p>
      ) : (
        <div className="mb-1.5 flex items-center gap-1.5">
          <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${DOT[tone]}`} aria-hidden />
          <span className="text-[13px] font-medium text-[#43434A]">
            {formatReliabilityPercent(avg)} · moyenne du dossier
          </span>
        </div>
      )}
      {caption && <p className="text-xs text-socle-muted">{caption}</p>}
    </div>
  )
}

function statusLabelFr(status: string): string {
  switch (status) {
    case 'valide':
      return 'Validé'
    case 'en_revue':
      return 'En revue'
    case 'brouillon':
      return 'Brouillon'
    case 'archive':
      return 'Archivé'
    default:
      return status
  }
}
