// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import {
  documentPermissions,
  formatDateTimeFr,
  formatLongDateFr,
  ownerLabel,
  personInitials,
  personLabel,
  statusMeta,
  SYSTEM_AUTHOR_LABEL,
} from './documentPageUtils'

describe('documentPageUtils', () => {
  it('statusMeta mappe les statuts connus', () => {
    expect(statusMeta('valide')).toEqual({ label: 'Validé', color: '#1E8E5A' })
    expect(statusMeta('en_revue').label).toBe('En revue')
    expect(statusMeta('brouillon').label).toBe('Brouillon')
  })

  it('documentPermissions lit uniquement le champ serveur', () => {
    expect(documentPermissions(undefined).canEdit).toBe(false)
    expect(
      documentPermissions({
        canEdit: true,
        canPublish: true,
        canManageAccess: false,
        canComment: true,
        canManageAttestations: false,
      }).canPublish,
    ).toBe(true)
  })

  it('personLabel : null → Système (migration)', () => {
    expect(personLabel(null)).toBe(SYSTEM_AUTHOR_LABEL)
    expect(personLabel({ id: 'u1', displayName: 'Tarek Fezai', initials: 'TF' })).toBe('Tarek Fezai')
    expect(personLabel({ id: 'u2', displayName: 'Utilisateur supprimé', initials: 'US' })).toBe(
      'Utilisateur supprimé',
    )
  })

  it('personInitials : système = ⚙', () => {
    expect(personInitials(null)).toBe('⚙')
    expect(personInitials({ id: 'u1', displayName: 'Tarek', initials: 'TF' })).toBe('TF')
  })

  it('ownerLabel préfixe Équipe', () => {
    expect(ownerLabel('Identité & accès')).toBe('Équipe Identité & accès')
    expect(ownerLabel('')).toBe('Équipe')
  })

  it('formatLongDateFr / formatDateTimeFr', () => {
    expect(formatLongDateFr('2026-09-12T12:22:00.000Z')).toMatch(/12 septembre 2026/)
    expect(formatDateTimeFr('2026-09-12T12:22:00.000Z')).toMatch(/12 septembre 2026 à/)
  })
})
