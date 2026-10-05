// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import {
  apiErrorCode,
  apiErrorDetail,
  apiErrorMessage,
  apiProblem,
  apiRequiredFieldRefs,
} from './apiError'

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

  it('required_field_missing préfère detail et expose fields', () => {
    const err = axiosErr(409, {
      status: 409,
      code: 'required_field_missing',
      detail: 'Champs obligatoires manquants pour l\'envoi en révision',
      fields: [{ id: 'f1', name: 'Criticité', slug: 'criticite' }],
    })
    expect(apiErrorMessage(err, 'x')).toContain('Champs obligatoires')
    expect(apiRequiredFieldRefs(err)).toEqual([
      { id: 'f1', name: 'Criticité', slug: 'criticite' },
    ])
  })

  it('content_invalid conserve le chemin JSON du détail', () => {
    const err = axiosErr(400, {
      status: 400,
      code: 'content_invalid',
      detail: '$.content[2].attrs.type: attribut non autorisé : type',
    })
    expect(apiErrorMessage(err, 'Échec')).toBe('$.content[2].attrs.type: attribut non autorisé : type')
  })
})
