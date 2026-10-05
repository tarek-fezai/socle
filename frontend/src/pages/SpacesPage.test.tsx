// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))

const listSpaces = vi.fn()
const createSpace = vi.fn()

vi.mock('../lib/spaces', () => ({
  listSpaces: (...args: unknown[]) => listSpaces(...args),
  createSpace: (...args: unknown[]) => createSpace(...args),
}))

import { SpacesPage } from './SpacesPage'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/spaces']}>
        <Routes>
          <Route path="/spaces" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('SpacesPage', () => {
  beforeEach(() => {
    listSpaces.mockReset()
    createSpace.mockReset()
  })

  it('liste les espaces et crée un nouvel espace', async () => {
    listSpaces
      .mockResolvedValueOnce([
        {
          id: '00000000-0000-0000-0000-000000000001',
          name: 'Espace par défaut',
          color: '#2F6FED',
          createdAt: '2026-01-01T00:00:00Z',
          isOwner: true,
          isResponsible: true,
        },
      ])
      .mockResolvedValueOnce([
        {
          id: '00000000-0000-0000-0000-000000000001',
          name: 'Espace par défaut',
          color: '#2F6FED',
          createdAt: '2026-01-01T00:00:00Z',
          isOwner: true,
          isResponsible: true,
        },
        {
          id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          name: 'Identité',
          color: '#3730E0',
          createdAt: '2026-09-29T00:00:00Z',
          isOwner: true,
          isResponsible: true,
        },
      ])
    createSpace.mockResolvedValue({
      id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      name: 'Identité',
      color: '#3730E0',
      createdAt: '2026-09-29T00:00:00Z',
      isOwner: true,
      isResponsible: true,
    })

    render(wrap(<SpacesPage />))

    await waitFor(() => expect(screen.getByText('Espace par défaut')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText(/Identité/i), {
      target: { value: 'Identité' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Créer un espace' }))

    await waitFor(() => {
      expect(createSpace).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ name: 'Identité' }),
      )
    })
    await waitFor(() => expect(screen.getByText('Identité')).toBeTruthy())
  })
})
