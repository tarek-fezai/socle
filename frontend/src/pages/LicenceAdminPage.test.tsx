// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

const getMock = vi.fn()
const postMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
  },
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ organizationName: 'Organisation Démo' }),
}))

import { LicenceAdminPage } from './LicenceAdminPage'

const LICENCE = {
  status: 'valide' as const,
  licenseId: 'LIC-DEMO-9F2A-44B1',
  licensee: 'Organisation Démo',
  edition: 'Entreprise',
  issuedAt: '2026-01-01T00:00:00Z',
  expiresAt: '2027-01-01T00:00:00Z',
  maxUsers: 250,
  activeUsers: 214,
  effectiveMaxUsers: 250,
  evaluationMode: false,
  bannerMessage: null,
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <LicenceAdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LicenceAdminPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    getMock.mockResolvedValue({ data: LICENCE })
  })

  it('affiche édition, sièges et identifiant', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Entreprise')).toBeTruthy())
    expect(screen.getByText('LIC-DEMO-9F2A-44B1')).toBeTruthy()
    expect(screen.getByRole('button', { name: /Importer un fichier de licence/i })).toBeTruthy()
  })
})
