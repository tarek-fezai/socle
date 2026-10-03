// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi } from 'vitest'
import { isDocumentReadPath } from '../../components/shell/shellUtils'
import {
  formatAttestationDueDate,
  getActiveAttestation,
  shouldShowAttestationBanner,
} from '../../lib/attestations'
import { isEditorFromFeedback } from '../../lib/feedback'
import { mergeRelatedLinks } from '../../lib/documentLinks'
import { reliabilityLevelLabel } from '../../lib/reliability'
import {
  attestationNoun,
  attestationScopeLabel,
  canPublish,
  canSeeAccessTab,
  canSeeEditTab,
  formatDateTimeFr,
  formatLongDateFr,
  nextReviewAt,
  ownerLabel,
  resolveUserName,
  statusMeta,
  tagColors,
} from './documentPageUtils'

describe('isDocumentReadPath (masque ShellHeader / MobileTabBar)', () => {
  it('vrai uniquement pour /docs/:id', () => {
    expect(isDocumentReadPath('/docs/abc')).toBe(true)
    expect(isDocumentReadPath('/docs/abc/')).toBe(true)
    expect(isDocumentReadPath('/docs/new')).toBe(false)
    expect(isDocumentReadPath('/docs')).toBe(false)
    expect(isDocumentReadPath('/docs/abc/edit')).toBe(false)
    expect(isDocumentReadPath('/docs/abc/history')).toBe(false)
    expect(isDocumentReadPath('/')).toBe(false)
  })
})

describe('permissions de la page de lecture', () => {
  it('Modifier / Publier / Accès', () => {
    expect(canSeeEditTab(true)).toBe(true)
    expect(canSeeEditTab(false)).toBe(false)
    expect(canPublish(true, 'brouillon')).toBe(true)
    expect(canPublish(true, 'en_revue')).toBe(false)
    expect(canPublish(true, 'valide')).toBe(false)
    expect(canPublish(false, 'brouillon')).toBe(false)
    expect(canSeeAccessTab(false, false)).toBe(false)
    expect(canSeeAccessTab(false, true)).toBe(true)
    expect(canSeeAccessTab(true, undefined)).toBe(true)
  })

  it('l’éditeur se déduit de la présence de totals dans le feedback', () => {
    expect(isEditorFromFeedback({ myVote: null, totals: { yes: 0, no: 0 } })).toBe(true)
    expect(isEditorFromFeedback({ myVote: true })).toBe(false)
    expect(isEditorFromFeedback(undefined)).toBe(false)
  })
})

describe('libellés', () => {
  it('statut et fiabilité', () => {
    expect(statusMeta('valide').label).toBe('Validé')
    expect(statusMeta('en_revue').label).toBe('En revue')
    expect(statusMeta('brouillon').label).toBe('Brouillon')
    expect(reliabilityLevelLabel(91)).toBe('Fiabilité élevée')
    expect(reliabilityLevelLabel(60)).toBe('Fiabilité moyenne')
    expect(reliabilityLevelLabel(20)).toBe('Fiabilité faible')
    expect(reliabilityLevelLabel(null)).toBe('Non évalué')
  })

  it('dates en français', () => {
    expect(formatLongDateFr('2026-09-12T12:00:00Z')).toBe('12 septembre 2026')
    expect(formatLongDateFr(null)).toBeNull()
    expect(formatDateTimeFr('2026-09-12T12:22:00Z')).toMatch(/^12 septembre 2026 à \d{2}:\d{2}$/)
    expect(formatAttestationDueDate('2026-10-03')).toBe('3 octobre 2026')
    expect(formatAttestationDueDate(null)).toBeNull()
  })

  it('prochaine revue = révision + seuil', () => {
    const next = nextReviewAt({
      contentModifiedAt: '2026-09-12T00:00:00Z',
      updatedAt: '2026-09-12T00:00:00Z',
      stalenessThresholdDays: 181,
    })
    expect(formatLongDateFr(next)).toBe('12 mars 2027')
    expect(nextReviewAt({ updatedAt: '2026-09-12T00:00:00Z' })).toBeNull()
  })

  it('auteurs, propriétaire, tags', () => {
    expect(resolveUserName(null, {})).toBe('Système (migration)')
    expect(resolveUserName('u1', { u1: 'Tarek Fezai' })).toBe('Tarek Fezai')
    expect(resolveUserName('u2', {})).toBe('Utilisateur')
    expect(ownerLabel('Identité')).toBe('Équipe Identité')
    expect(tagColors({ id: '1', name: 'IAM', color: '#3730E0' }, 0)).toEqual({ fg: '#3730E0', bg: '#F0EFFC' })
    expect(tagColors({ id: '2', name: 'x' }, 1)).toEqual({ fg: '#B7791F', bg: '#FDF3E3' })
  })

  it('bannière d’attestation', () => {
    expect(attestationNoun('Politique')).toBe('cette politique')
    expect(attestationNoun(null)).toBe('ce document')
    expect(attestationScopeLabel({ audienceType: 'space_members' }, 'Identité & accès')).toBe(
      'du périmètre « Identité & accès »',
    )
    expect(attestationScopeLabel({ audienceType: 'group' }, 'X')).toBe('du groupe concerné')
  })
})

describe('clients API', () => {
  it('active : 204 → null ; 200 → campagne', async () => {
    const get = vi
      .fn()
      .mockResolvedValueOnce({ status: 204, data: '' })
      .mockResolvedValueOnce({ status: 200, data: { campaignId: 'c', acknowledged: false } })
    const client = { get } as never
    expect(await getActiveAttestation(client, 'd')).toBeNull()
    const a = await getActiveAttestation(client, 'd')
    expect(a?.campaignId).toBe('c')
    expect(shouldShowAttestationBanner(a)).toBe(true)
    expect(shouldShowAttestationBanner({ ...a!, acknowledged: true })).toBe(false)
    expect(shouldShowAttestationBanner(null)).toBe(false)
  })

  it('liens liés dédoublonnés', () => {
    const merged = mergeRelatedLinks({
      outgoing: [{ id: '1', title: 'A' }],
      incoming: [
        { id: '1', title: 'A' },
        { id: '2', title: 'B' },
      ],
    })
    expect(merged.map((m) => m.id)).toEqual(['1', '2'])
  })
})
