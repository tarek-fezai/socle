// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { AccountPage } from './AccountPage'
import { SocleRole } from '../../lib/auth'

const getMock = vi.fn()
const logout = vi.fn()

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({
    me: authState.me,
    logout,
    organizationName: 'Organisation Démo',
  }),
}))

vi.mock('../../lib/api', () => ({
  api: { get: (...args: unknown[]) => getMock(...args) },
}))

const authState = {
  me: {
    id: '11111111-1111-1111-1111-111111111111',
    email: 'camille@example.com',
    displayName: 'Camille Contributeur',
    avatarInitials: 'CC',
    issuer: 'http://127.0.0.1:8081/realms/socle',
    roles: [SocleRole.CONTRIBUTEUR, SocleRole.ADMINISTRATEUR_SYSTEME] as string[],
  },
}

describe('AccountPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    authState.me.roles = [SocleRole.CONTRIBUTEUR, SocleRole.ADMINISTRATEUR_SYSTEME]
  })

  it('affiche la carte Organisation pour un admin plateforme', () => {
    render(
      <MemoryRouter>
        <AccountPage />
      </MemoryRouter>,
    )
    expect(screen.getByTestId('account-org-admin')).toBeTruthy()
    expect(screen.queryByText(/pat_/i)).toBeNull()
    expect(screen.getByText(/Aucun jeton personnel/i)).toBeTruthy()
    expect(screen.getByText(/Gérées par le fournisseur d'identité/i)).toBeTruthy()
  })

  it('masque la carte Organisation pour un non-admin', () => {
    authState.me.roles = [SocleRole.AUDITEUR]
    render(
      <MemoryRouter>
        <AccountPage />
      </MemoryRouter>,
    )
    expect(screen.queryByTestId('account-org-admin')).toBeNull()
  })

  it('appelle GET /api/v1/me/export au clic Demander mon export', async () => {
    getMock.mockResolvedValue({ data: { profile: { email: 'camille@example.com' } } })
    render(
      <MemoryRouter>
        <AccountPage />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByTestId('account-export-btn'))
    await waitFor(() => expect(getMock).toHaveBeenCalledWith('/api/v1/me/export'))
  })
})
