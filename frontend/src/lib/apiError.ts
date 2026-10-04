// SPDX-License-Identifier: AGPL-3.0-or-later

/** Corps d'erreur API (RFC 9457 problem+json, avec compat. legacy). */
export type ApiProblemBody = {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  /** Code stable pour réactions UI (pas de parsing de texte). */
  code?: string
  correlationId?: string
  /** Legacy ApprovalConflictException / anciens corps. */
  error?: string
  message?: string
  reason?: string
}

export type ApiErrorCodes =
  | 'approval_in_progress'
  | 'edit_lock_held'
  | 'reject_justification_required'
  | 'governed_tag_owner_only'
  | 'diff_too_large'
  | 'already_resolved'
  | 'step_advanced'
  | 'content_invalid'

type AxiosLikeError = {
  response?: {
    status?: number
    data?: ApiProblemBody
  }
}

function asAxiosError(error: unknown): AxiosLikeError | undefined {
  if (error && typeof error === 'object' && 'response' in error) {
    return error as AxiosLikeError
  }
  return undefined
}

/** Extrait le corps problem+json (ou legacy) d'une erreur axios. */
export function apiProblem(error: unknown): ApiProblemBody | undefined {
  return asAxiosError(error)?.response?.data
}

export function apiErrorStatus(error: unknown): number | undefined {
  const response = asAxiosError(error)?.response
  return response?.data?.status ?? response?.status
}

/** Code stable (`code`, sinon legacy `error`). */
export function apiErrorCode(error: unknown): string | undefined {
  const body = apiProblem(error)
  if (!body) return undefined
  if (typeof body.code === 'string' && body.code) return body.code
  if (typeof body.error === 'string' && body.error) return body.error
  return undefined
}

/** Message affichable : `detail` (problem+json) ou `message` legacy. */
export function apiErrorDetail(error: unknown): string | undefined {
  const body = apiProblem(error)
  if (!body) return undefined
  if (typeof body.detail === 'string' && body.detail) return body.detail
  if (typeof body.message === 'string' && body.message) return body.message
  return undefined
}

/**
 * Message UI unique pour les écrans métier.
 * Préfère le détail serveur ; mappe seulement les codes qui ont un libellé produit fixe.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  const status = apiErrorStatus(error)
  const code = apiErrorCode(error)
  const detail = apiErrorDetail(error)

  if (code === 'step_advanced') {
    return 'Cette demande a été escaladée entretemps — l’étape a changé. Rechargez avant de décider.'
  }
  if (code === 'already_resolved') {
    return 'Cette demande a déjà été traitée.'
  }
  // content_invalid : conserver le détail serveur (chemin JSON du nœud + motif).
  if (code === 'content_invalid' && detail) return detail
  if (detail) return detail
  if (status === 409) return 'Conflit — opération refusée.'
  if (status === 403) return 'Accès refusé pour cette action.'
  if (status === 404) return 'Ressource introuvable.'
  if (status === 400) return fallback
  return fallback
}
