// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

const getMock = vi.fn()
const deleteMock = vi.fn()
vi.mock('../lib/api', () => ({
  api: {
    get: (...args: unknown[]) => getMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
  },
}))

import { FavoritesPage } from './FavoritesPage'
import { normalizeFavoritesPayload } from '../lib/favorites'

const DOC = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
const SPACE = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('normalizeFavoritesPayload', () => {
  it('accepte le tableau FavoriteItem réel (targetType / targetId)', () => {
    expect(
      normalizeFavoritesPayload([
        { targetType: 'document', targetId: DOC, createdAt: '2026-09-01T00:00:00Z', title: 'Doc' },
      ]),
    ).toEqual({
      items: [
        {
          resourceType: 'document',
          resourceId: DOC,
          title: 'Doc',
          createdAt: '2026-09-01T00:00:00Z',
          status: undefined,
          updatedAt: undefined,
          spaceId: undefined,
          spaceName: undefined,
          documentCount: undefined,
        },
      ],
    })
  })

  it('accepte l’enveloppe historique { items } et ignore les types inconnus', () => {
    const out = normalizeFavoritesPayload({
      items: [
        { resourceType: 'folder', resourceId: 'f1', title: 'Dossier', createdAt: '' },
        { targetType: 'tag', targetId: 't1', title: 'Tag' },
      ],
    })
    expect(out.items).toHaveLength(1)
    expect(out.items[0].resourceType).toBe('folder')
  })
})

describe('FavoritesPage', () => {
  beforeEach(() => {
    getMock.mockReset()
    deleteMock.mockReset()
  })

  it('liste les favoris groupés, accroche réelle, méta statut / espace', async () => {
    getMock.mockImplementation((url: string) => {
      if (url === '/api/v1/favorites')
        return Promise.resolve({
          data: [
            {
              targetType: 'document',
              targetId: DOC,
              createdAt: '2026-09-01T00:00:00Z',
              title: 'Politique',
              status: 'valide',
              updatedAt: '2026-09-12T10:00:00.000Z',
              spaceId: SPACE,
              spaceName: 'Identité & accès',
            },
            {
              targetType: 'space',
              targetId: SPACE,
              createdAt: '2026-09-02T00:00:00Z',
              title: 'Identité',
              spaceId: SPACE,
              spaceName: 'Identité & accès',
              documentCount: 14,
            },
          ],
        })
      if (url === '/api/v1/spaces') return Promise.resolve({ data: [{ id: SPACE, name: 'Identité', color: '#0D8A7C' }] })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    renderPage()
    expect(await screen.findByText('Politique')).toBeTruthy()
    expect(screen.getByText('1 document et 1 espace que vous avez marqués comme favoris.')).toBeTruthy()
    expect(screen.getByText('Identité & accès')).toBeTruthy()
    expect(screen.getByText('Validé · révisé le 12 septembre 2026')).toBeTruthy()
    expect(screen.getByText('Espace · 14 documents')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Politique' }).getAttribute('href')).toBe(`/docs/${DOC}`)
  })

  it('retire un favori via l’étoile', async () => {
    getMock.mockResolvedValue({
      data: [{ targetType: 'document', targetId: DOC, createdAt: '2026-09-01T00:00:00Z', title: 'Politique' }],
    })
    deleteMock.mockResolvedValue({})
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Retirer des favoris' }))
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/api/v1/favorites/document/${DOC}`))
  })

  it('état vide', async () => {
    getMock.mockResolvedValue({ data: [] })
    renderPage()
    expect(await screen.findByText('Aucun favori pour le moment.')).toBeTruthy()
  })
})
