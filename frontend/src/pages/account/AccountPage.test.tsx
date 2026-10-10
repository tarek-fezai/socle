// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

const tokensGetMock = vi.fn()
const postMock = vi.fn()
const deleteMock = vi.fn()

vi.mock('../../lib/api', () => ({
  api: {
    get: (url: string, ...rest: unknown[]) =>
      url === '/api/v1/me/tokens' ? tokensGetMock(url) : getMock(url, ...rest),
    post: (...args: unknown[]) => postMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
  },
}))

const NOW = Date.parse('2026-10-10T12:00:00.000Z')
const TOKENS = [
  {
    id: 'aaaaaaaa-0000-0000-0000-000000000001',
    name: 'Script local — export de mes brouillons',
    last4: '7e21',
    scope: 'read',
    createdAt: '2026-09-14T12:00:00.000Z',
    expiresAt: '2026-12-12T12:00:00.000Z',
    lastUsedAt: new Date(NOW - 4 * 24 * 60 * 60_000).toISOString(),
    status: 'active',
  },
  {
    id: 'aaaaaaaa-0000-0000-0000-000000000002',
    name: 'CLI Socle (poste de travail)',
    last4: '3c48',
    scope: 'read_write',
    createdAt: '2026-10-01T12:00:00.000Z',
    expiresAt: '2026-12-30T12:00:00.000Z',
    lastUsedAt: new Date(NOW - 40 * 60_000).toISOString(),
    status: 'active',
  },
  {
    id: 'aaaaaaaa-0000-0000-0000-000000000003',
    name: 'Ancien jeton révoqué',
    last4: '9999',
    scope: 'read',
    createdAt: '2026-08-01T12:00:00.000Z',
    expiresAt: '2026-09-01T12:00:00.000Z',
    lastUsedAt: null,
    status: 'revoked',
  },
]

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
    tokensGetMock.mockReset()
    tokensGetMock.mockResolvedValue({ data: [] })
    postMock.mockReset()
    deleteMock.mockReset()
    authState.me.roles = [SocleRole.CONTRIBUTEUR, SocleRole.ADMINISTRATEUR_SYSTEME]
  })

  it('affiche la carte Organisation pour un admin plateforme', async () => {
    render(
      <MemoryRouter>
        <AccountPage />
      </MemoryRouter>,
    )
    expect(screen.getByTestId('account-org-admin')).toBeTruthy()
    expect(screen.queryByText(/pat_/i)).toBeNull()
    expect(await screen.findByText(/Aucun jeton personnel/i)).toBeTruthy()
    expect(screen.getByText(/Gérées par le fournisseur d'identité/i)).toBeTruthy()
  })

  it('masque la carte Organisation pour un non-admin', async () => {
    authState.me.roles = [SocleRole.AUDITEUR]
    render(
      <MemoryRouter>
        <AccountPage />
      </MemoryRouter>,
    )
    expect(screen.queryByTestId('account-org-admin')).toBeNull()
    expect(await screen.findByText(/Aucun jeton personnel/i)).toBeTruthy()
  })

  describe('jetons d’accès personnels', () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(NOW)
      tokensGetMock.mockResolvedValue({ data: TOKENS })
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('liste réelle : jeton masqué, portée, expiration, dernier usage ; révoqués exclus', async () => {
      render(
        <MemoryRouter>
          <AccountPage />
        </MemoryRouter>,
      )
      const rows = await screen.findAllByTestId('pat-row')
      expect(rows).toHaveLength(2)
      expect(rows[0].textContent).toContain('Script local — export de mes brouillons')
      expect(rows[0].textContent).toContain('pat_••••••••••••7e21 · lecture seule · expire le 12 déc. 2026')
      expect(rows[0].textContent).toContain('utilisé il y a 4j')
      expect(rows[1].textContent).toContain('pat_••••••••••••3c48 · lecture + écriture · expire le 30 déc. 2026')
      expect(rows[1].textContent).toContain('utilisé il y a 40 min')
      expect(screen.queryByText(/Ancien jeton révoqué/)).toBeNull()
      expect(screen.queryByText(/sans expiration/i)).toBeNull()
      expect(screen.getByTestId('pat-generate-link').getAttribute('href')).toBe('/account/tokens/new')
    })

    it('Révoquer : confirmation dans la page (pas de window.confirm), puis DELETE', async () => {
      const confirmSpy = vi.spyOn(window, 'confirm')
      deleteMock.mockResolvedValue({ status: 204 })
      render(
        <MemoryRouter>
          <AccountPage />
        </MemoryRouter>,
      )
      const rows = await screen.findAllByTestId('pat-row')
      fireEvent.click(within(rows[1]).getByRole('button', { name: 'Révoquer' }))
      expect(within(rows[1]).getByText('Révoquer ce jeton ?')).toBeTruthy()
      expect(deleteMock).not.toHaveBeenCalled()

      fireEvent.click(within(rows[1]).getByRole('button', { name: 'Annuler' }))
      expect(within(rows[1]).queryByText('Révoquer ce jeton ?')).toBeNull()

      fireEvent.click(within(rows[1]).getByRole('button', { name: 'Révoquer' }))
      tokensGetMock.mockResolvedValue({ data: [TOKENS[0]] })
      fireEvent.click(within(rows[1]).getByRole('button', { name: 'Confirmer' }))
      await waitFor(() =>
        expect(deleteMock).toHaveBeenCalledWith('/api/v1/me/tokens/aaaaaaaa-0000-0000-0000-000000000002'),
      )
      await waitFor(() => expect(screen.getAllByTestId('pat-row')).toHaveLength(1))
      expect(confirmSpy).not.toHaveBeenCalled()
      confirmSpy.mockRestore()
    })

    it('génération : préréglages 7/30/60/90 sans « sans expiration », jeton affiché une fois + Copier', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      Object.assign(navigator, { clipboard: { writeText } })
      const plaintext = 'pat_AbCdEfGhIjKl_' + 'x'.repeat(43)
      postMock.mockResolvedValue({
        data: { plaintext, token: { ...TOKENS[1], id: 'new', name: 'CI nocturne', last4: 'xxxx' } },
      })
      render(
        <MemoryRouter>
          <AccountPage generateToken />
        </MemoryRouter>,
      )
      const modal = screen.getByTestId('pat-generate-modal')
      const presets = within(modal).getAllByRole('radio', { name: /jours/ })
      expect(presets.map((p) => p.textContent)).toEqual(['7 jours', '30 jours', '60 jours', '90 jours'])
      expect(within(modal).queryByText(/sans expiration/i)).toBeNull()
      expect(within(modal).queryByText(/1 an/i)).toBeNull()
      expect(presets[3].getAttribute('aria-checked')).toBe('true')

      fireEvent.change(within(modal).getByLabelText('Nom du jeton'), { target: { value: 'CI nocturne' } })
      fireEvent.click(within(modal).getByText('Lecture + écriture'))
      fireEvent.click(presets[1])
      fireEvent.click(within(modal).getByRole('button', { name: 'Générer le jeton' }))

      await waitFor(() =>
        expect(postMock).toHaveBeenCalledWith('/api/v1/me/tokens', {
          name: 'CI nocturne',
          scope: 'read_write',
          expiresInDays: 30,
        }),
      )
      expect(await screen.findByTestId('pat-plaintext')).toBeTruthy()
      expect(screen.getByTestId('pat-plaintext').textContent).toBe(plaintext)
      expect(screen.getByText(/il ne sera plus affiché/)).toBeTruthy()
      fireEvent.click(screen.getByRole('button', { name: 'Copier' }))
      await waitFor(() => expect(writeText).toHaveBeenCalledWith(plaintext))
      expect(await screen.findByRole('button', { name: 'Copié' })).toBeTruthy()
    })

    it('génération : limite atteinte → message pat_limit_reached', async () => {
      postMock.mockRejectedValue(
        Object.assign(new Error('409'), { response: { status: 409, data: { code: 'pat_limit_reached' } } }),
      )
      render(
        <MemoryRouter>
          <AccountPage generateToken />
        </MemoryRouter>,
      )
      fireEvent.click(screen.getByRole('button', { name: 'Générer le jeton' }))
      expect((await screen.findByRole('alert')).textContent).toContain('Nombre maximal de jetons actifs atteint')
      expect(screen.queryByTestId('pat-plaintext')).toBeNull()
    })
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
