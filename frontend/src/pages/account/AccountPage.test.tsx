// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest'
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

  describe('téléchargement de l’export', () => {
    const createObjectURL = vi.fn(() => 'blob:socle-export')
    const revokeObjectURL = vi.fn()
    let clickSpy: ReturnType<typeof vi.spyOn>
    let anchorInDocumentAtClick: boolean | null

    beforeEach(() => {
      vi.useFakeTimers()
      createObjectURL.mockClear()
      revokeObjectURL.mockClear()
      anchorInDocumentAtClick = null
      Object.assign(URL, { createObjectURL, revokeObjectURL })
      clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        anchorInDocumentAtClick = document.body.contains(this)
      })
    })

    afterEach(() => {
      clickSpy.mockRestore()
      vi.useRealTimers()
    })

    it('ne révoque l’URL qu’après le délai, pas de façon synchrone après click()', async () => {
      getMock.mockResolvedValue({ data: { profile: { email: 'camille@example.com' } } })
      render(
        <MemoryRouter>
          <AccountPage />
        </MemoryRouter>,
      )
      fireEvent.click(screen.getByTestId('account-export-btn'))
      await act(async () => {})
      expect(clickSpy).toHaveBeenCalledTimes(1)

      const anchor = clickSpy.mock.contexts[0] as HTMLAnchorElement
      expect(anchor.download).toBe('socle-export-11111111-1111-1111-1111-111111111111.json')
      expect(anchor.href).toBe('blob:socle-export')
      expect(anchorInDocumentAtClick).toBe(true)
      expect(document.body.contains(anchor)).toBe(false)
      expect(createObjectURL).toHaveBeenCalledTimes(1)
      expect(revokeObjectURL).not.toHaveBeenCalled()

      vi.advanceTimersByTime(999)
      expect(revokeObjectURL).not.toHaveBeenCalled()
      vi.advanceTimersByTime(1)
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:socle-export')
      expect(screen.queryByRole('alert')).toBeNull()
    })

    it('affiche un message d’erreur sans téléchargement sur une réponse 500', async () => {
      getMock.mockRejectedValue(
        Object.assign(new Error('Request failed with status code 500'), {
          isAxiosError: true,
          response: { status: 500, data: {} },
        }),
      )
      render(
        <MemoryRouter>
          <AccountPage />
        </MemoryRouter>,
      )
      fireEvent.click(screen.getByTestId('account-export-btn'))
      await act(async () => {})

      expect(screen.getByRole('alert').textContent).toBe('Export impossible pour le moment.')
      expect(createObjectURL).not.toHaveBeenCalled()
      expect(clickSpy).not.toHaveBeenCalled()
      vi.runAllTimers()
      expect(revokeObjectURL).not.toHaveBeenCalled()
    })
  })
})
