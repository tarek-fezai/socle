import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

const getMock = vi.fn()

vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
  },
}))

import { CompositePage } from './CompositePage'

const PAGE_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const TARGET_ID = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/docs/${PAGE_ID}/view`]}>
        <Routes>
          <Route path="/docs/:id/view" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('CompositePage — transclusion', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  it('affiche le contenu résolu et un lien vers la source', async () => {
    getMock.mockResolvedValue({
      data: {
        id: PAGE_ID,
        spaceId: 'dddddddd-dddd-dddd-dddd-dddddddddddd',
        title: 'Guide composite',
        body: {
          type: 'doc',
          content: [
            {
              type: 'transclusion',
              attrs: {
                accessible: true,
                documentId: TARGET_ID,
                title: 'Procédure cible',
              },
              content: [
                {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Contenu autorisé' }],
                    },
                  ],
                },
              ],
            },
          ],
        },
        status: 'brouillon',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    })

    render(wrap(<CompositePage />))

    await waitFor(() => {
      expect(screen.getByText('Guide composite')).toBeTruthy()
    })
    expect(screen.getByTestId('transclusion-ok')).toBeTruthy()
    expect(screen.getByText('Contenu autorisé')).toBeTruthy()
    expect(screen.getByRole('link', { name: /Ouvrir la source/i }).getAttribute('href')).toBe(
      `/docs/${TARGET_ID}`,
    )
    expect(getMock).toHaveBeenCalledWith(`/api/v1/documents/${PAGE_ID}/resolved`)
  })

  it('affiche l’indicateur sans fuite de titre ni contenu interdit', async () => {
    getMock.mockResolvedValue({
      data: {
        id: PAGE_ID,
        spaceId: 'dddddddd-dddd-dddd-dddd-dddddddddddd',
        title: 'Guide composite',
        body: {
          type: 'doc',
          content: [
            {
              type: 'transclusion',
              attrs: {
                accessible: false,
                deniedReason: 'forbidden',
              },
            },
          ],
        },
        status: 'brouillon',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    })

    render(wrap(<CompositePage />))

    await waitFor(() => {
      expect(screen.getByTestId('transclusion-denied')).toBeTruthy()
    })
    expect(screen.getByText('Contenu non accessible')).toBeTruthy()
    expect(screen.queryByText(/confidentiel|interdit|secret/i)).toBeNull()
    expect(screen.queryByText(TARGET_ID)).toBeNull()
  })
})
