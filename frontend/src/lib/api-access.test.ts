// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it } from 'vitest'
import { accessDeniedRedirect } from './api'

describe('accessDeniedRedirect', () => {
  it('laisse le callback et l’acceptation afficher leur propre suite', () => {
    expect(accessDeniedRedirect('/callback', 'not_provisioned')).toBeNull()
    expect(accessDeniedRedirect('/invitations/accept', 'not_provisioned')).toBeNull()
    expect(accessDeniedRedirect('/login/erreur', 'not_provisioned')).toBeNull()
  })

  it('envoie les autres pages vers la connexion refusée', () => {
    expect(accessDeniedRedirect('/', 'not_provisioned')).toBe(
      '/login/erreur?reason=not_provisioned',
    )
    expect(accessDeniedRedirect('/docs', 'account_disabled')).toBe(
      '/login/erreur?reason=account_disabled',
    )
  })

  it('ne redirige pas sans motif', () => {
    expect(accessDeniedRedirect('/', undefined)).toBeNull()
  })
})
