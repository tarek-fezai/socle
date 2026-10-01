// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

const fetchContentHealthMock = vi.fn()
const getSpaceMock = vi.fn()

vi.mock('../lib/contentHealth', () => ({
  fetchContentHealth: (...args: unknown[]) => fetchContentHealthMock(...args),
}))

vi.mock('../lib/spaces', () => ({
  getSpace: (...args: unknown[]) => getSpaceMock(...args),
}))

vi.mock('../lib/api', () => ({
  api: {},
}))

import { ContentHealthPage } from './ContentHealthPage'

const SPACE = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const DOC = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
const SECRET = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/spaces/${SPACE}/content-health`]}>
        <Routes>
          <Route path="/spaces/:spaceId/content-health" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('ContentHealthPage', () => {
  beforeEach(() => {
    fetchContentHealthMock.mockReset()
    getSpaceMock.mockReset()
  })

  it('liste les docs stale fournis par l’API sans fuite d’id secret', async () => {
    getSpaceMock.mockResolvedValue({
      id: SPACE,
      name: 'Identité',
      color: null,
      createdAt: null,
      canManage: true,
      isOwner: true,
      isResponsible: true,
    })
    fetchContentHealthMock.mockResolvedValue({
      spaceId: SPACE,
      thresholdDays: 90,
      viewableDocumentCount: 2,
      staleCount: 1,
      staleDocuments: [
        {
          id: DOC,
          title: 'Procédure ancienne',
          status: 'brouillon',
          contentModifiedAt: '2026-01-01T00:00:00Z',
          ageDays: 270,
        },
      ],
    })

    const { container } = render(wrap(<ContentHealthPage />))
    await waitFor(() => expect(screen.getByTestId('stale-document-list')).toBeTruthy())
    expect(screen.getByText('Procédure ancienne')).toBeTruthy()
    expect(screen.getByTestId('freshness-badge-stale')).toBeTruthy()
    expect(container.textContent).not.toContain(SECRET)
    expect(fetchContentHealthMock).toHaveBeenCalled()
  })
})
