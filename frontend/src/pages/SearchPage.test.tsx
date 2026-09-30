import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'

vi.mock('../lib/api', () => ({ api: {} }))

const searchDocuments = vi.fn()
const listSpaces = vi.fn()

vi.mock('../lib/search', () => ({
  searchDocuments: (...args: unknown[]) => searchDocuments(...args),
}))

vi.mock('../lib/spaces', () => ({
  listSpaces: (...args: unknown[]) => listSpaces(...args),
}))

import { SearchPage } from './SearchPage'

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/search']}>
        <Routes>
          <Route path="/search" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('SearchPage', () => {
  beforeEach(() => {
    searchDocuments.mockReset()
    listSpaces.mockReset()
    listSpaces.mockResolvedValue([
      {
        id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        name: 'Espace A',
        color: null,
        createdAt: null,
        canManage: true,
        isOwner: true,
        isResponsible: true,
      },
    ])
  })

  it('soumet une requête et affiche titre + extrait', async () => {
    searchDocuments.mockResolvedValue({
      query: 'rotation',
      total: 1,
      results: [
        {
          id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
          title: 'Politique mot de passe',
          excerpt: 'Rotation trimestrielle des secrets',
          spaceId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          spaceName: 'Espace A',
          docType: 'politique',
          status: 'valide',
          updatedAt: '2026-09-01T00:00:00Z',
          rank: 0.1,
        },
      ],
    })

    render(wrap(<SearchPage />))

    fireEvent.change(screen.getByLabelText(/requête/i), { target: { value: 'rotation' } })
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))

    await waitFor(() => {
      expect(searchDocuments).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ q: 'rotation' }),
      )
      expect(screen.getByText('Politique mot de passe')).toBeTruthy()
    })
    expect(screen.getByText(/Rotation trimestrielle/)).toBeTruthy()
  })

  it('affiche un état vide propre sans erreur', async () => {
    searchDocuments.mockResolvedValue({ query: 'xyzzy', total: 0, results: [] })

    render(wrap(<SearchPage />))
    fireEvent.change(screen.getByLabelText(/requête/i), { target: { value: 'xyzzy' } })
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))

    await waitFor(() => expect(screen.getByText(/Aucun résultat/)).toBeTruthy())
  })

  it('passe les filtres espace/tag/type à l’API et lie le résultat vers le document', async () => {
    searchDocuments.mockResolvedValue({
      query: 'rotation',
      total: 1,
      results: [
        {
          id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
          title: 'Politique mot de passe',
          excerpt: 'Rotation',
          spaceId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          spaceName: 'Espace A',
          docType: 'politique',
          status: 'valide',
          updatedAt: '2026-09-01T00:00:00Z',
          rank: 0.1,
        },
      ],
    })

    render(wrap(<SearchPage />))

    await waitFor(() => expect(screen.getByRole('option', { name: 'Espace A' })).toBeTruthy())

    fireEvent.change(screen.getByLabelText(/requête/i), { target: { value: 'rotation' } })
    fireEvent.change(screen.getByDisplayValue('Tous mes espaces'), {
      target: { value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' },
    })
    fireEvent.change(screen.getByPlaceholderText('ex. IAM'), { target: { value: 'IAM' } })
    fireEvent.change(screen.getByPlaceholderText('ex. politique'), {
      target: { value: 'politique' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))

    await waitFor(() => {
      expect(searchDocuments).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
          q: 'rotation',
          spaceId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          tag: 'IAM',
          docType: 'politique',
        }),
      )
    })
    const link = (await screen.findByRole('link', {
      name: /Politique mot de passe/,
    })) as HTMLAnchorElement
    expect(link.getAttribute('href')).toBe('/docs/cccccccc-cccc-cccc-cccc-cccccccccccc')
  })
})
