// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest'
import { apiErrorCode, apiErrorDetail, apiErrorMessage, apiProblem } from './apiError'

function axiosErr(status: number, data: Record<string, unknown>) {
  return { response: { status, data } }
}

describe('apiError (problem+json)', () => {
  it('extrait detail et code d’un Problem Detail 409', () => {
    const err = axiosErr(409, {
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: "Demande d'approbation en cours",
      instance: '/api/v1/documents/x',
      code: 'approval_in_progress',
    })
    expect(apiProblem(err)?.code).toBe('approval_in_progress')
    expect(apiErrorCode(err)).toBe('approval_in_progress')
    expect(apiErrorDetail(err)).toBe("Demande d'approbation en cours")
    expect(apiErrorMessage(err, 'fallback')).toBe("Demande d'approbation en cours")
  })

  it('affiche la raison réelle d’un 403 gouverné (pas le générique)', () => {
    const err = axiosErr(403, {
      status: 403,
      detail: 'Étiquette gouvernée : seul un owner peut la rattacher ou la détacher',
      code: 'governed_tag_owner_only',
    })
    expect(apiErrorMessage(err, 'Accès refusé')).toContain('Étiquette gouvernée')
  })

  it('mappe step_advanced / already_resolved via code (ou legacy error)', () => {
    expect(
      apiErrorMessage(
        axiosErr(409, { status: 409, code: 'step_advanced', detail: 'Étape avancée' }),
        'x',
      ),
    ).toContain('escaladée')
    expect(
      apiErrorMessage(
        axiosErr(409, { status: 409, error: 'already_resolved', message: 'Demande déjà résolue' }),
        'x',
      ),
    ).toBe('Cette demande a déjà été traitée.')
  })

  it('fallback sans corps', () => {
    expect(apiErrorMessage(new Error('network'), 'Échec')).toBe('Échec')
  })
})
