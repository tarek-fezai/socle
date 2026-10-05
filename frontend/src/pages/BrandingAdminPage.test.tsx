// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

const getMock = vi.fn()
const putMock = vi.fn()
const postMock = vi.fn()
const deleteMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    put: (...args: unknown[]) => putMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
  },
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ organizationName: 'Organisation Démo' }),
}))

import { BrandingAdminPage } from './BrandingAdminPage'

const BRANDING = {
  instanceName: 'Organisation Démo',
  accentColor: '#3730E0',
  hidePoweredBy: false,
  senderName: 'Organisation Démo — Socle',
  senderEmail: 'notifications@example.com',
  hasLogo: false,
  hasFavicon: false,
  logoUrl: null,
  faviconUrl: null,
  publicBaseUrl: 'https://docs.example.com',
  publicBaseUrlNote:
    'Défini par la variable d\'environnement SOCLE_PUBLIC_BASE_URL (non modifiable depuis l\'interface).',
  updatedAt: '2026-09-01T00:00:00Z',
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BrandingAdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BrandingAdminPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    putMock.mockReset()
    postMock.mockReset()
    deleteMock.mockReset()
    getMock.mockResolvedValue({ data: BRANDING })
  })

  it('affiche le titre, l’URL publique et l’expéditeur', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: /Personnalisation de marque/ })).toBeTruthy()
    expect(await screen.findByText('docs.example.com')).toBeTruthy()
    expect(screen.getByDisplayValue('Organisation Démo — Socle')).toBeTruthy()
    expect(screen.getByDisplayValue('notifications@example.com')).toBeTruthy()
    expect(screen.queryByText('Entreprise')).toBeNull()
    expect(screen.queryByText(/socle\.app/)).toBeNull()
  })

  it('affiche un message si non administrateur (403)', async () => {
    getMock.mockRejectedValue({ response: { status: 403, data: { detail: 'forbidden' } } })
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toMatch(/Administrateur système requis/)
    })
  })

  it('envoie un e-mail de test', async () => {
    postMock.mockResolvedValue({
      data: {
        status: 'sent',
        to: 'tarek@example.com',
        from: 'notifications@example.com',
        channel: 'log',
      },
    })
    renderPage()
    await screen.findByText('docs.example.com')
    fireEvent.click(screen.getByRole('button', { name: /e-mail de test/ }))
    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith('/api/v1/admin/branding/test-email', {}),
    )
  })
})
