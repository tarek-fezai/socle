// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ShellUserMenu } from './ShellUserMenu'

const logout = vi.fn()

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({
    me: {
      displayName: 'Camille Contributeur',
      avatarInitials: 'CC',
      roles: ['CONTRIBUTEUR', 'ADMINISTRATEUR_SYSTEME'],
    },
    logout,
  }),
}))

describe('ShellUserMenu', () => {
  it('ouvre le menu au clic et propose Paramètres du compte', () => {
    render(
      <MemoryRouter>
        <ShellUserMenu />
      </MemoryRouter>,
    )
    const trigger = screen.getByTestId('shell-user-menu-trigger')
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(trigger)
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('menuitem', { name: /Paramètres du compte/i }).getAttribute('href')).toBe(
      '/account',
    )
  })

  it('ferme au clavier Échap et déclenche la déconnexion', () => {
    render(
      <MemoryRouter>
        <ShellUserMenu />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByTestId('shell-user-menu-trigger'))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    fireEvent.click(screen.getByTestId('shell-user-menu-trigger'))
    fireEvent.click(screen.getByRole('menuitem', { name: /Se déconnecter/i }))
    expect(logout).toHaveBeenCalled()
  })
})
