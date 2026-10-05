// SPDX-License-Identifier: AGPL-3.0-or-later
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

const getMock = vi.fn()
const putMock = vi.fn()
const postMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    put: (...args: unknown[]) => putMock(...args),
    post: (...args: unknown[]) => postMock(...args),
  },
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ organizationName: 'Organisation Démo' }),
}))

import { RetentionAdminPage } from './RetentionAdminPage'

const RETENTION = {
  auditRetentionMonths: 24,
  versionRetentionMode: 'unlimited' as const,
  versionRetentionValue: null,
  archivedDocsRetentionYears: 7,
  processingRegisterReviewedAt: '2026-08-30',
  dataResidenceLabel: 'Union européenne (Paris, fr-par-1)',
  activeLegalHolds: 0,
  lastPurgeRanAt: null,
  updatedAt: '2026-09-01T00:00:00Z',
  gitPurgePendingCount: 0,
  gitPurgeFailedCount: 0,
  gitPurgeLastError: null,
}

const HOLDS = { holds: [], activeCount: 0 }

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <RetentionAdminPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RetentionAdminPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    putMock.mockReset()
    postMock.mockReset()
    getMock.mockImplementation((url: string) => {
      if (url === '/api/v1/admin/retention') return Promise.resolve({ data: RETENTION })
      if (String(url).startsWith('/api/v1/admin/legal-holds')) return Promise.resolve({ data: HOLDS })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
  })

  it('affiche le titre et les durées', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: /Rétention/ })).toBeTruthy()
    expect(await screen.findByText('24 mois')).toBeTruthy()
    expect(screen.getByText('Illimitée')).toBeTruthy()
    expect(screen.getByText('7 ans')).toBeTruthy()
    expect(screen.getByText('Union européenne (Paris, fr-par-1)')).toBeTruthy()
  })

  it('affiche un message si non administrateur (403)', async () => {
    getMock.mockImplementation(() =>
      Promise.reject({ response: { status: 403, data: { detail: 'forbidden' } } }),
    )
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toMatch(/Administrateur système requis/)
    })
  })
})
