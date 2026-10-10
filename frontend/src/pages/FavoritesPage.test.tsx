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
        { resourceType: 'document', resourceId: DOC, title: 'Doc', createdAt: '2026-09-01T00:00:00Z' },
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

  it('liste les favoris (tableau API), phrase d’accroche issue des décomptes réels, type seul en méta', async () => {
    getMock.mockImplementation((url: string) => {
      if (url === '/api/v1/favorites')
        return Promise.resolve({
          data: [
            { targetType: 'document', targetId: DOC, createdAt: '2026-09-01T00:00:00Z', title: 'Politique' },
            { targetType: 'space', targetId: SPACE, createdAt: '2026-09-02T00:00:00Z', title: 'Identité' },
          ],
        })
      if (url === '/api/v1/spaces') return Promise.resolve({ data: [{ id: SPACE, name: 'Identité', color: '#0D8A7C' }] })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    renderPage()
    expect(await screen.findByText('Politique')).toBeTruthy()
    expect(screen.getByText('1 document et 1 espace que vous avez marqués comme favoris.')).toBeTruthy()
    expect(screen.getByText('Document')).toBeTruthy()
    expect(screen.getByText('Espace')).toBeTruthy()
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
