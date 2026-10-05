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
  /** Propriétés RFC 9457 (ex. champs obligatoires manquants). */
  fields?: unknown
  [key: string]: unknown
}

export type RequiredFieldRef = {
  id: string
  name?: string
  slug?: string
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
  | 'tag_name_conflict'
  | 'governed_tag_in_use'
  | 'tag_creation_restricted'
  | 'field_type_locked'
  | 'field_option_in_use'
  | 'required_field_missing'

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

/** Propriétés additionnelles du problem+json (hors champs standard). */
export function apiErrorProperties(error: unknown): Record<string, unknown> {
  const body = apiProblem(error)
  if (!body) return {}
  const skip = new Set([
    'type',
    'title',
    'status',
    'detail',
    'instance',
    'code',
    'correlationId',
    'error',
    'message',
    'reason',
  ])
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(body)) {
    if (!skip.has(k) && v !== undefined) out[k] = v
  }
  return out
}

/** IDs des champs obligatoires manquants (`required_field_missing`). */
export function apiRequiredFieldRefs(error: unknown): RequiredFieldRef[] {
  const props = apiErrorProperties(error)
  const raw = props.fields ?? apiProblem(error)?.fields
  if (!Array.isArray(raw)) return []
  const out: RequiredFieldRef[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const rec = item as { id?: unknown; name?: unknown; slug?: unknown }
    if (typeof rec.id !== 'string' || !rec.id) continue
    out.push({
      id: rec.id,
      name: typeof rec.name === 'string' ? rec.name : undefined,
      slug: typeof rec.slug === 'string' ? rec.slug : undefined,
    })
  }
  return out
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
  if (code === 'required_field_missing' && detail) return detail
  if (code === 'tag_name_conflict' && detail) return detail
  if (code === 'governed_tag_in_use' && detail) return detail
  if (code === 'field_type_locked' && detail) return detail
  if (code === 'field_option_in_use' && detail) return detail
  if (code === 'tag_creation_restricted') {
    return detail ?? 'La création de nouveaux tags est réservée aux administrateurs.'
  }
  if (detail) return detail
  if (status === 409) return 'Conflit — opération refusée.'
  if (status === 403) return 'Accès refusé pour cette action.'
  if (status === 404) return 'Ressource introuvable.'
  if (status === 400) return fallback
  return fallback
}
