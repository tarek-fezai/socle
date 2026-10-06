// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { listPlaceholderHints, placeholderBannerText } from '../lib/templates'

/** Bandeau « N zones à compléter » tant que le corps contient des zones `placeholder`. */
export function PlaceholderBanner({ body }: { body: unknown }) {
  const hints = listPlaceholderHints(body)
  if (hints.length === 0) return null
  return (
    <div
      role="status"
      data-testid="placeholder-banner"
      className="mb-4 rounded-lg border border-[#E8D9A8] bg-[#FBF3E4] px-4 py-2.5 text-sm text-socle-warn"
    >
      <p className="font-semibold">{placeholderBannerText(hints.length)}</p>
      <p className="mt-0.5 text-xs font-normal text-socle-slate">
        Complétez ou supprimez ces zones avant de soumettre le document pour approbation.
      </p>
    </div>
  )
}
